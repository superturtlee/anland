#define _GNU_SOURCE
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <poll.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <dirent.h>
#include <unistd.h>

#include "anw_hidden.h"
#include "display_consumer.h"
#include "protocol.h"

#define TAG "Anland"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define PIXEL_FORMAT_RGBA_8888 1
#define MAX_COLLECT_BUFS 8

/* Saved JVM / activity reference for event-thread JNI callbacks. */
static JavaVM *g_jvm = NULL;
static jobject g_activity_obj = NULL;

static struct anw_api api;
static bool api_loaded = false;
static void on_fallback(void *userdata);

static void on_exit_fallback(void *userdata);
struct consumer_state {
    pthread_mutex_t lock;
    ANativeWindow *window;
    display_ctx *ctx;
    pthread_t render_thread;
    volatile bool running;

    //Note: it is Deamon's Reconnect, not Fallback Flag
    //Fallback is maintained by display lib, and the consumer should not care about it.
    volatile bool need_reconnect;

    int buf_count;
    int dmabuf_fds[MAX_COLLECT_BUFS];
    struct buf_info dmabuf_infos[MAX_COLLECT_BUFS];
    ANativeWindowBuffer *buf_anb[MAX_COLLECT_BUFS];

    int screen_w;
    int screen_h;

    // Latest display refresh rate (milli-Hz) reported from Java. Read on
    // (re)connect to seed the producer; updated live by nativeSetRefreshRate.
    volatile uint32_t refresh_mhz;

    // Event (output) thread
    pthread_t event_thread;
    volatile bool event_running;
};

static struct consumer_state g_state = {
    .lock = PTHREAD_MUTEX_INITIALIZER,
};

static bool motion_has_last = false;
static float motion_last_x = 0.0f;
static float motion_last_y = 0.0f;

static int collect_dmabufs(struct consumer_state *s)
{
    ANativeWindow *win = s->window;
    int target = s->buf_count;
    int found = 0;

    LOGI("collecting %d dma-bufs via dequeue/queue", target);

    for (int attempt = 0; attempt < target * 4 && found < target; attempt++) {
        ANativeWindowBuffer *anb = NULL;
        int fence = -1;
        if (api.dequeueBuffer(win, &anb, &fence) != 0 || !anb) {
            LOGE("dequeueBuffer failed on attempt %d", attempt);
            if (fence >= 0)
                close(fence);
            break;
        }
        if (fence >= 0)
            close(fence);   /* enumeration only: no need to wait the fence */

        if (!anb->handle || anb->handle->numFds < 1) {
            LOGE("dequeued buffer has no dma-buf handle on attempt %d", attempt);
            api.cancelBuffer(win, anb, -1);
            continue;
        }

        int fd = anb->handle->data[0];   /* first fd backs the dma-buf */
        int stride = anb->stride, width = anb->width, height = anb->height;

        /* deduplicate by ANativeWindowBuffer pointer (stable per queue slot) */
        bool dup_found = false;
        for (int i = 0; i < found; i++) {
            if (s->buf_anb[i] == anb) {
                dup_found = true;
                break;
            }
        }

        /* post it back so the next dequeue rotates to another slot */
        api.queueBuffer(win, anb, -1);

        if (dup_found)
            continue;

        int dup_fd = dup(fd);
        if (dup_fd < 0)
            continue;

        s->buf_anb[found] = anb;
        s->dmabuf_fds[found] = dup_fd;
        s->dmabuf_infos[found].stride = stride * 4;
        s->dmabuf_infos[found].width  = width;
        s->dmabuf_infos[found].height = height;
        s->dmabuf_infos[found].format = PIXEL_FORMAT_RGBA_8888;
        s->dmabuf_infos[found].modifier = 0;
        s->dmabuf_infos[found].offset = 0;
        LOGI("  buf[%d]: anb=%p fd=%d dup=%d %dx%d stride=%d",
             found, (void *)anb, fd, dup_fd, width, height, stride);
        found++;
    }

    if (found < target) {
        LOGE("only collected %d/%d", found, target);
        for (int i = 0; i < found; i++) {
            close(s->dmabuf_fds[i]);
            s->dmabuf_fds[i] = -1;
        }
        return -1;
    }

    s->buf_count = found;
    LOGI("collected %d dma-bufs", found);
    return 0;
}

static void cleanup_dmabufs(struct consumer_state *s)
{
    for (int i = 0; i < s->buf_count; i++) {
        if (s->dmabuf_fds[i] >= 0) {
            close(s->dmabuf_fds[i]);
            s->dmabuf_fds[i] = -1;
        }
    }
    s->buf_count = 0;
}

/* Report the current display refresh rate to the producer over the data
 * channel, reusing the InputEvent framing (see INPUT_TYPE_DISPLAY_REFRESH).
 * No-op when disconnected or rate unknown. */
static void send_refresh_rate(struct consumer_state *s)
{
    if (!s->ctx || s->refresh_mhz == 0)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_DISPLAY_REFRESH,
        .display = { .refresh_mhz = s->refresh_mhz },
    };
    push_input_event(s->ctx, &ev);
}

/*
 * Event thread: listens for output events (clipboard, etc.) from the producer
 * on the data_fd. Runs while s->event_running is true.
 */
static void *event_thread_func(void *arg)
{
    struct consumer_state *s = arg;
    LOGI("event thread started");

    JNIEnv *env = NULL;
    if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
        LOGE("event thread: AttachCurrentThread failed");
        return NULL;
    }

    /* Find classes/methods once */
    jclass ctxClass = (*env)->GetObjectClass(env, g_activity_obj);
    jmethodID setClipMethod = (*env)->GetMethodID(env, ctxClass, "nativeSetClipboardText", "(Ljava/lang/String;)V");
    if (!setClipMethod) {
        LOGE("event thread: nativeSetClipboardText not found");
        (*g_jvm)->DetachCurrentThread(g_jvm);
        return NULL;
    }

    while (s->event_running) {
        if (!s->ctx) {
            usleep(50000);
            continue;
        }

        struct OutputEvent ev;
        int ret = poll_output_event(s->ctx, &ev, 500);
        if (ret <= 0)
            continue;

        if (ev.type == OUTPUT_TYPE_CLIPBOARD && ev.clipboard.size > 0) {
            char *buf = malloc(ev.clipboard.size + 1);
            if (!buf)
                continue;

            if (poll_output_event_extend_data(s->ctx, buf, ev.clipboard.size, 5000) == 1) {
                buf[ev.clipboard.size] = '\0';
                jstring jstr = (*env)->NewStringUTF(env, buf);
                if (jstr) {
                    (*env)->CallVoidMethod(env, g_activity_obj, setClipMethod, jstr);
                    (*env)->DeleteLocalRef(env, jstr);
                }
            }
            free(buf);
        } else {
            /* Unknown or zero-length event: drain any trailing data if size > 0 */
            LOGI("event thread: unknown output event type=%u size=%u", ev.type, ev.clipboard.size);
        }
    }

    (*g_jvm)->DetachCurrentThread(g_jvm);
    LOGI("event thread stopped");
    return NULL;
}

static void start_event_thread(struct consumer_state *s)
{
    if (s->event_running)
        return;
    s->event_running = true;
    pthread_create(&s->event_thread, NULL, event_thread_func, s);
}

static void stop_event_thread(struct consumer_state *s)
{
    if (!s->event_running)
        return;
    s->event_running = false;
    //pthread_join(s->event_thread, NULL);
}

static int do_connect(struct consumer_state *s)
{
    const char *sock = "/data/local/tmp/display_daemon.sock";

    if (s->ctx) {
        disconnect(s->ctx);
        s->ctx = NULL;
    }
    cleanup_dmabufs(s);

    ANativeWindow *win = s->window;
    s->screen_w = ANativeWindow_getWidth(win);
    s->screen_h = ANativeWindow_getHeight(win);

    /* dequeueBuffer needs the window connected to an API first (ANativeWindow_lock
     * did this internally). Disconnect first so reconnect is idempotent. */
    anw_api_disconnect(win, ANW_API_CPU);
    if (anw_api_connect(win, ANW_API_CPU) != 0) {
        LOGE("api_connect(CPU) failed");
        return -1;
    }

    ANativeWindow_setBuffersGeometry(win, s->screen_w, s->screen_h,
                                     AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);

    int min_undequeued = 0;
    api.query(win, ANATIVEWINDOW_QUERY_MIN_UNDEQUEUED_BUFFERS, &min_undequeued);
    int total = min_undequeued + 2;
    if (total > MAX_COLLECT_BUFS)
        total = MAX_COLLECT_BUFS;

    api.setBufferCount(win, total);

    s->buf_count = total;
    if (collect_dmabufs(s) < 0)
        return -1;

    LOGI("connecting to %s (%dx%d, %d bufs)", sock,
         s->screen_w, s->screen_h, s->buf_count);

    if (connect_to_deamon(&s->ctx, sock) < 0) {
        LOGE("connect_to_deamon failed");
        return -1;
    }

    set_screen_info(s->ctx, s->screen_w, s->screen_h,
                    PIXEL_FORMAT_RGBA_8888, s->refresh_mhz);
    push_dmabufs(s->ctx, s->dmabuf_fds, s->dmabuf_infos, s->buf_count);

    set_fallback_callback(s->ctx, on_fallback, s);
    set_exit_fallback_callback(s->ctx, on_exit_fallback, s);

    s->need_reconnect = false;
    LOGI("connected");
    return 0;
}

static void on_fallback(void *userdata)
{
    struct consumer_state *s = userdata;
    LOGI("fallback triggered");

    // Disable clip listener on Java side before stopping event thread
    if (g_jvm && g_activity_obj) {
        JNIEnv *env = NULL;
        bool attached = false;
        if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == 0)
                attached = true;
        }
        if (env) {
            jclass cls = (*env)->GetObjectClass(env, g_activity_obj);
            jmethodID mid = (*env)->GetMethodID(env, cls, "nativeClipListening", "(Z)V");
            if (mid)
                (*env)->CallVoidMethod(env, g_activity_obj, mid, JNI_FALSE);
        }
        if (attached)
            (*g_jvm)->DetachCurrentThread(g_jvm);
    }

    stop_event_thread(s);
}

static void on_exit_fallback(void *userdata)
{
    struct consumer_state *s = userdata;
    LOGI("exit fallback triggered");

    send_refresh_rate(&g_state);
    
    JNIEnv *env = NULL;
    if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
        LOGE("on_exit_fallback: AttachCurrentThread failed");
        return;
    }

    // Enable clip listener on Java side
    jclass cls = (*env)->GetObjectClass(env, g_activity_obj);
    jmethodID listenMid = (*env)->GetMethodID(env, cls, "nativeClipListening", "(Z)V");
    if (listenMid)
        (*env)->CallVoidMethod(env, g_activity_obj, listenMid, JNI_TRUE);

    start_event_thread(s);

    // Initial clipboard sync: read current system clipboard and send to producer
    jmethodID syncMethod = (*env)->GetMethodID(env, cls, "nativeClipboardSync", "()V");
    if (syncMethod)
        (*env)->CallVoidMethod(env, g_activity_obj, syncMethod);

    (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void *render_thread_func(void *arg)
{
    struct consumer_state *s = arg;
    LOGI("render thread started");

    while (s->running) {
        if (s->need_reconnect) {
            LOGI("reconnecting...");
            if (do_connect(s) < 0) {
                usleep(500000);
                continue;
            }
        }

        ANativeWindowBuffer *anb = NULL;
        int acqfence = -1;
        if (api.dequeueBuffer(s->window, &anb, &acqfence) != 0 || !anb) {
            usleep(16000);
            continue;
        }
        /* Emulate ANativeWindow_lock: CPU-wait the acquire fence so the buffer is
         * already safe to write (SurfaceFlinger done reading the previous frame)
         * before we hand it to the producer. A sync_file fd signals POLLIN. */
        if (acqfence >= 0) {
            struct pollfd fpfd = { .fd = acqfence, .events = POLLIN };
            poll(&fpfd, 1, 1000);
            close(acqfence);
        }

        int idx = -1;
        for (int i = 0; i < s->buf_count; i++) {
            if (s->buf_anb[i] == anb) {
                idx = i;
                break;
            }
        }

        if (idx < 0) {
            api.queueBuffer(s->window, anb, -1);
            usleep(16000);
            continue;
        }

        if (select_dmabuf(s->ctx, idx) < 0) {
            api.queueBuffer(s->window, anb, -1);
            usleep(16000);
            continue;
        }

        /* The producer renders into the buffer and hands back a render-done fence
         * over data_fd (reverse). Queue with it so SurfaceFlinger waits GPU-side
         * before scanout -- this lets the producer submit before its GPU render
         * completes (no glFinish stall). rfence == -1 falls back to "ready now". */
        int rfence = refresh_done(s->ctx);
        api.queueBuffer(s->window, anb, rfence);
    }

    LOGI("render thread stopped");
    return NULL;
}

/* ---------- JNI ---------- */

JNIEXPORT void JNICALL
Java_com_anland_consumer_MainActivity_nativeStart(
    JNIEnv *env, jobject thiz, jobject surface)
{
    if (!api_loaded) {
        if (anw_api_load(&api) < 0) {
            LOGE("failed to load ANativeWindow hidden API");
            return;
        }
        api_loaded = true;
    }

    pthread_mutex_lock(&g_state.lock);

    if (g_state.running) {
        g_state.running = false;
        pthread_mutex_unlock(&g_state.lock);
        pthread_join(g_state.render_thread, NULL);
        pthread_mutex_lock(&g_state.lock);
    }

    if (g_state.ctx) {
        disconnect(g_state.ctx);
        g_state.ctx = NULL;
    }
    motion_has_last = false;
    motion_has_last = false;
    cleanup_dmabufs(&g_state);

    if (g_state.window) {
        ANativeWindow_release(g_state.window);
        g_state.window = NULL;
    }

    g_state.window = ANativeWindow_fromSurface(env, surface);
    if (!g_state.window) {
        LOGE("ANativeWindow_fromSurface failed");
        pthread_mutex_unlock(&g_state.lock);
        return;
    }

    /* Save JVM & activity refs for event-thread JNI callbacks. */
    if (!g_jvm) {
        (*env)->GetJavaVM(env, &g_jvm);
    }
    if (g_activity_obj) {
        (*env)->DeleteGlobalRef(env, g_activity_obj);
    }
    g_activity_obj = (*env)->NewGlobalRef(env, thiz);

    g_state.running = true;
    g_state.need_reconnect = true;
    pthread_create(&g_state.render_thread, NULL, render_thread_func, &g_state);

    pthread_mutex_unlock(&g_state.lock);
}

JNIEXPORT void JNICALL
Java_com_anland_consumer_MainActivity_nativeStop(
    JNIEnv *env, jobject thiz)
{
    pthread_mutex_lock(&g_state.lock);

    if (g_state.running) {
        g_state.running = false;
        pthread_mutex_unlock(&g_state.lock);
        pthread_join(g_state.render_thread, NULL);
        pthread_mutex_lock(&g_state.lock);
    }

    if (g_state.ctx) {
        stop_event_thread(&g_state);
        disconnect(g_state.ctx);
        g_state.ctx = NULL;
    }

    // Disable clip listener on Java side
    if (g_jvm && g_activity_obj) {
        JNIEnv *env = NULL;
        bool attached = false;
        if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == 0)
                attached = true;
        }
        if (env) {
            jclass cls = (*env)->GetObjectClass(env, g_activity_obj);
            jmethodID mid = (*env)->GetMethodID(env, cls, "nativeClipListening", "(Z)V");
            if (mid)
                (*env)->CallVoidMethod(env, g_activity_obj, mid, JNI_FALSE);
        }
        if (attached)
            (*g_jvm)->DetachCurrentThread(g_jvm);
    }

    cleanup_dmabufs(&g_state);

    if (g_state.window) {
        ANativeWindow_release(g_state.window);
        g_state.window = NULL;
    }

    pthread_mutex_unlock(&g_state.lock);
}

JNIEXPORT void JNICALL
Java_com_anland_consumer_MainActivity_nativeSetRefreshRate(
    JNIEnv *env, jobject thiz, jfloat hz)
{
    if (hz <= 0.0f)
        return;
    g_state.refresh_mhz = (uint32_t)(hz * 1000.0f + 0.5f);
    // Apply live if already connected; otherwise do_connect() seeds it.
    send_refresh_rate(&g_state);
}

JNIEXPORT void JNICALL
Java_com_anland_consumer_MainActivity_nativeSendTouch(
    JNIEnv *env, jobject thiz, jint action, jfloat x, jfloat y, jint pointer_id)
{
    if (!g_state.ctx)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_TOUCH,
        .touch = { .action = action, .x = x, .y = y, .pointer_id = pointer_id },
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_consumer_MainActivity_nativeSendTouchFrame(
    JNIEnv *env, jobject thiz)
{
    if (!g_state.ctx)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_TOUCH_FRAME,
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_consumer_MainActivity_nativeSendKey(
    JNIEnv *env, jobject thiz, jint action, jint keycode)
{
    if (!g_state.ctx)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_KEY,
        .key = { .action = action, .keycode = keycode },
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_consumer_MainActivity_nativeSendMouseMotion(
    JNIEnv *env, jobject thiz, jfloat x, jfloat y, jfloat dx, jfloat dy)
{
    if (!g_state.ctx)
        return;

    if (dx == 0.0f && dy == 0.0f && motion_has_last) {
        dx = x - motion_last_x;
        dy = y - motion_last_y;
    }

    motion_last_x = x;
    motion_last_y = y;
    motion_has_last = true;

    struct InputEvent ev = {
        .type = INPUT_TYPE_POINTER_MOTION,
        .pointer_motion = { .x = x, .y = y, .dx = dx, .dy = dy },
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_consumer_MainActivity_nativeSendMouseButton(
    JNIEnv *env, jobject thiz, jint button, jboolean pressed)
{
    if (!g_state.ctx)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_POINTER_BUTTON,
        .pointer_button = { .button = button, .pressed = pressed ? 1 : 0 },
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_consumer_MainActivity_nativeSendMouseScroll(
    JNIEnv *env, jobject thiz, jint axis, jfloat value)
{
    if (!g_state.ctx)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_POINTER_AXIS,
        .pointer_axis = { .axis = axis, .value = value, .discrete = 0 },
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_consumer_MainActivity_nativeSendClipboard(
    JNIEnv *env, jobject thiz, jbyteArray data)
{
    if (!g_state.ctx)
        return;

    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0)
        return;

    char *buf = malloc(len);
    if (!buf)
        return;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)buf);

    struct InputEvent ev = {
        .type = INPUT_TYPE_CLIPBOARD,
        .clipboard = { .size = (uint32_t)len },
    };
    push_input_event_with_length(g_state.ctx, &ev, buf, len);
    free(buf);
}
