/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-License-Identifier: GPL-2.0-or-later
*/
#include "anland_backend.h"
#include "anland_egl_backend.h"
#include "anland_input.h"
#include "anland_logging.h"
#include "anland_output.h"

#include "core/drmdevice.h"
#include "core/renderloop.h"
#include "opengl/egldisplay.h"
#include "utils/filedescriptor.h"
#include "utils/pipe.h"
#include "wayland/abstract_data_source.h"
#include "wayland/seat.h"
#include "wayland/display.h"
#include "wayland_server.h"

#include <QScopeGuard>
#include <QSocketNotifier>
#include <QTimer>
#include <QtConcurrent>

#include <xf86drm.h>

#include <fcntl.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <unistd.h>

namespace KWin
{

static const QString s_defaultSocketPath = QStringLiteral("/tmp/display_daemon.sock");
static const int s_reconnectIntervalMs = 200;

/*
 * KWin needs a DRM render device for the GL/EGL path (syncobj timelines, dmabuf
 * feedback). The anland backend renders surfaceless and imports the daemon's
 * dmabufs, so any usable render node works. Prefer an explicit override
 * ($ANLAND_DRM_DEVICE), then the first enumerated render node (as the virtual
 * backend does), then the standard render node — which on the kgsl/turnip stack
 * is the msm node exposed at /dev/dri/renderD128.
 */
static std::unique_ptr<DrmDevice> openRenderDevice()
{
    const QString override = qEnvironmentVariable("ANLAND_DRM_DEVICE");
    if (!override.isEmpty()) {
        if (auto dev = DrmDevice::open(override)) {
            return dev;
        }
        qCWarning(KWIN_ANLAND) << "ANLAND_DRM_DEVICE" << override << "could not be opened";
    }

    const int count = drmGetDevices2(0, nullptr, 0);
    if (count > 0) {
        QList<drmDevice *> devices(count);
        if (drmGetDevices2(0, devices.data(), devices.size()) >= 0) {
            auto guard = qScopeGuard([&] {
                drmFreeDevices(devices.data(), devices.size());
            });
            for (drmDevice *device : std::as_const(devices)) {
                if (device->available_nodes & (1 << DRM_NODE_RENDER)) {
                    if (auto dev = DrmDevice::open(QString::fromUtf8(device->nodes[DRM_NODE_RENDER]))) {
                        return dev;
                    }
                }
            }
        }
    }

    return DrmDevice::open(QStringLiteral("/dev/dri/renderD128"));
}

AnlandBackend::AnlandBackend(const QString &socketPath, QObject *parent)
    : OutputBackend(parent)
    , m_socketPath(socketPath.isEmpty() ? s_defaultSocketPath : socketPath)
{
}

AnlandBackend::~AnlandBackend()
{
    teardownNotifiers();
    if (m_reconnectTimer) {
        m_reconnectTimer->stop();
    }
    // m_outputs are QObject children of this backend; ~QObject deletes them.
    m_outputs.clear();
    m_inputDevice.reset();
    if (m_display) {
        ::disconnect(m_display); // C producer API, not QObject::disconnect
        m_display = nullptr;
    }
    // m_eglDisplay (EglDisplay) tears down the EGLDisplay in its destructor; the
    // 5.27-era manual eglTerminate(sceneEglDisplay()) is no longer needed.
}

bool AnlandBackend::initialize()
{
    if (connect_to_deamon(&m_display, m_socketPath.toLocal8Bit().constData()) < 0) {
        qCWarning(KWIN_ANLAND) << "failed to connect to display daemon at" << m_socketPath;
        return false;
    }

    set_fallback_callback(m_display, &AnlandBackend::fallbackTrampoline, this);

    uint32_t w = 0, h = 0, fmt = 0, refresh = 0;
    get_screen_info(m_display, &w, &h, &fmt, &refresh);
    qCInfo(KWIN_ANLAND) << "connected to daemon at" << m_socketPath
                        << "screen" << w << "x" << h << "refresh" << refresh << "mHz";

    if (w == 0 || h == 0) {
        qCWarning(KWIN_ANLAND) << "daemon reported invalid screen size";
        return false;
    }

    // KWin dereferences renderBackend->drmDevice() during OpenGL compositor
    // setup; without a real device it segfaults. Open one up front.
    m_drmDevice = openRenderDevice();
    if (!m_drmDevice) {
        qCWarning(KWIN_ANLAND) << "no usable DRM render device; cannot bring up OpenGL compositing";
        return false;
    }

    m_inputDevice = std::make_unique<AnlandInputDevice>();

    auto *output = new AnlandOutput(this, QStringLiteral("anland-1"));
    output->init(QSize(w, h), static_cast<int>(refresh), 1.0);
    m_outputs.append(output);
    Q_EMIT outputAdded(output);
    output->updateEnabled(true);
    Q_EMIT outputsQueried();

    m_reconnectTimer = new QTimer(this);
    m_reconnectTimer->setInterval(s_reconnectIntervalMs);
    connect(m_reconnectTimer, &QTimer::timeout, this, &AnlandBackend::onReconnectTimer);

    // connect_to_deamon() only fetched screen info; the context is still in
    // fallback with no consumer fds or dmabufs. Enter fallback explicitly so the
    // reconnect timer starts and discovers the consumer via try_exit_fallback().
    enterFallback();

    // Connect to KWin's clipboard change signal once.  The handler is guarded
    // by m_inFallback so it only fires when a consumer is connected.
    if (SeatInterface *seat = waylandServer()->seat()) {
        connect(seat, &SeatInterface::selectionChanged, this, [this](AbstractDataSource *) {
            onClipboardChanged();
        });
    }

    return true;
}

std::unique_ptr<EglBackend> AnlandBackend::createOpenGLBackend()
{
    return std::make_unique<AnlandEglBackend>(this);
}

std::unique_ptr<InputBackend> AnlandBackend::createInputBackend()
{
    return std::make_unique<AnlandInputBackend>(this);
}

QList<CompositingType> AnlandBackend::supportedCompositors() const
{
    return QList<CompositingType>{OpenGLCompositing};
}

QList<BackendOutput *> AnlandBackend::outputs() const
{
    QList<BackendOutput *> ret;
    ret.reserve(m_outputs.size());
    for (AnlandOutput *output : m_outputs) {
        ret.append(output);
    }
    return ret;
}

EglDisplay *AnlandBackend::sceneEglDisplayObject() const
{
    return m_eglDisplay.get();
}

void AnlandBackend::setEglDisplay(std::unique_ptr<EglDisplay> &&display)
{
    m_eglDisplay = std::move(display);
}

bool AnlandBackend::notifyFramePresented()
{
    if (m_consumerReady) {
        trigger_refresh(m_display);
        m_consumerReady = false;
        return true;
    }
    return false;
}

void AnlandBackend::setupNotifiers()
{
    teardownNotifiers();

    const int dataFd = get_data_fd(m_display);
    if (dataFd >= 0) {
        m_inputNotifier = new QSocketNotifier(dataFd, QSocketNotifier::Read, this);
        connect(m_inputNotifier, &QSocketNotifier::activated, this, [this]() {
            onInputReadable();
        });
    }

    const int bufReadyFd = get_buffer_ready_fd(m_display);
    if (bufReadyFd >= 0) {
        m_bufReadyNotifier = new QSocketNotifier(bufReadyFd, QSocketNotifier::Read, this);
        connect(m_bufReadyNotifier, &QSocketNotifier::activated, this, [this]() {
            onBufferReady();
        });
    }
}

void AnlandBackend::teardownNotifiers()
{
    if (m_inputNotifier) {
        m_inputNotifier->setEnabled(false);
        m_inputNotifier->deleteLater();
        m_inputNotifier = nullptr;
    }
    if (m_bufReadyNotifier) {
        m_bufReadyNotifier->setEnabled(false);
        m_bufReadyNotifier->deleteLater();
        m_bufReadyNotifier = nullptr;
    }
}

void AnlandBackend::onInputReadable()
{
    if (m_inFallback) return;

    InputEvent ev;
    while (poll_input_event(m_display, &ev, 0) > 0) {
        processInputEvent(ev);
        if (m_inFallback) break; // poll_input_event may have triggered fallback
    }
}

QPointF AnlandBackend::mapInputToLogical(const QPointF &devicePoint) const
{
    AnlandOutput *output = m_outputs[0];
    // The compositor maps logical -> device with transform().map(p, pixelSize()),
    // so the inverse maps device -> logical with the inverted transform bounded by
    // the device-space size (modeSize, the buffer's native landscape extent).
    // Then divide out the output scale to land in global logical coordinates.
    const QPointF logical = output->transform().inverted().map(devicePoint, QSizeF(output->modeSize()));
    return logical / output->scale();
}

void AnlandBackend::processInputEvent(const InputEvent &ev)
{
    if (!m_inputDevice) {
        return;
    }

    switch (ev.type) {
    case INPUT_TYPE_POINTER_MOTION: {
        // Both absolute position (x, y) and relative delta (dx, dy) come from the
        // consumer. Use the same mapInputToLogical for both so that dx/dy delivered
        // via wl_pointer.relative_motion match the cursor's position space, giving
        // Wayland clients correct velocity for momentum/kinetic scrolling.
        const QPointF pos = mapInputToLogical(QPointF(ev.pointer_motion.x, ev.pointer_motion.y));
        const QPointF delta = mapInputToLogical(QPointF(ev.pointer_motion.dx, ev.pointer_motion.dy));
        m_inputDevice->pointerMotion(pos, delta, delta);
        break;
    }
    case INPUT_TYPE_POINTER_BUTTON:
        m_inputDevice->pointerButton(ev.pointer_button.button, ev.pointer_button.pressed != 0);
        break;
    case INPUT_TYPE_POINTER_AXIS: {
        // protocol axis: 0 = vertical scroll, 1 = horizontal scroll (wayland order)
        const PointerAxis axis =
            ev.pointer_axis.axis == 0 ? PointerAxis::Vertical : PointerAxis::Horizontal;
        m_inputDevice->pointerAxis(axis, ev.pointer_axis.value, ev.pointer_axis.discrete * 120);
        break;
    }
    case INPUT_TYPE_KEY:
        m_inputDevice->keyboardKey(ev.key.keycode, ev.key.action == INPUT_ACTION_DOWN);
        break;
    case INPUT_TYPE_TOUCH: {
        const QPointF pos = mapInputToLogical(QPointF(ev.touch.x, ev.touch.y));
        switch (ev.touch.action) {
        case INPUT_ACTION_DOWN:
            m_inputDevice->touchDown(ev.touch.pointer_id, pos);
            break;
        case INPUT_ACTION_UP:
            m_inputDevice->touchUp(ev.touch.pointer_id);
            break;
        case INPUT_ACTION_MOVE:
            m_inputDevice->touchMotion(ev.touch.pointer_id, pos);
            break;
        default:
            break;
        }
        break;
    }
    case INPUT_TYPE_TOUCH_FRAME:
        m_inputDevice->touchFrame();
        break;
    case INPUT_TYPE_DISPLAY_REFRESH:
        // Not an input event: the consumer reports its live display refresh rate
        // (mHz) so we can repace the RenderLoop. m_outputs[0] is valid here (used
        // for scale above).
        m_outputs[0]->setRefreshRate(static_cast<int>(ev.display.refresh_mhz));
        break;
    case INPUT_TYPE_CLIPBOARD: {
        // The clipboard event carries the raw text data as inline payload after the
        // InputEvent header.  ev.clipboard.size tells us how many bytes follow.
        const uint32_t size = ev.clipboard.size;
        if (size == 0)
            break;
        QByteArray text(static_cast<int>(size), Qt::Uninitialized);
        if (poll_input_event_extend_data(m_display, text.data(), size, 5000) == 1) {
            sendClipboardToKWin(text);
        }
        break;
    }
    default:
        break;
    }
}

void AnlandBackend::onBufferReady()
{
    if (m_inFallback) {
        return;
    }

    // The dmabuf set is already in hand (try_exit_fallback() received it when we
    // left fallback), so buffer-ready is purely a frame-completion signal: drain
    // the eventfd and pace the render cycle.
    const int fd = get_buffer_ready_fd(m_display);
    if (fd >= 0) {
        eventfd_t val;
        eventfd_read(fd, &val);
    }

    m_consumerReady = true;

    // Buffer-ready is our frame-completion signal: complete the in-flight frame
    // (if any) and schedule the next one, keeping the render cycle paced by the
    // consumer rather than a timer.
    m_outputs[0]->onConsumerReady();
}

void AnlandBackend::fallbackTrampoline(void *data)
{
    static_cast<AnlandBackend *>(data)->enterFallback();
}

void AnlandBackend::enterFallback()
{
    if (m_inFallback) {
        return;
    }
    qCWarning(KWIN_ANLAND) << "consumer disconnected, entering fallback";

    if (m_inputDevice) {
        m_inputDevice->touchCancel();
    }

    // A frame may be in flight awaiting a buffer-ready that will never come now;
    // fail it so the RenderLoop's frame accounting does not stall.
    m_outputs[0]->stopRendering();

    teardownNotifiers();

    // Renderer is stopped: drop the imported dmabuf set now that the producer's fds
    // are gone. The layer is null at startup (no GL backend attached yet).
    if (AnlandEglLayer *layer = m_outputs[0]->eglLayer()) {
        layer->releaseBuffers();
    }

    m_consumerReady = false;
    m_inFallback = true;

    if (m_reconnectTimer) {
        m_reconnectTimer->start();
    }
}

void AnlandBackend::onReconnectTimer()
{
    if (!m_inFallback) {
        m_reconnectTimer->stop();
        return;
    }

    if (try_exit_fallback(m_display) != 0) {
        return; // still down, keep retrying
    }

    qCInfo(KWIN_ANLAND) << "consumer reconnected";
    m_inFallback = false;
    m_consumerReady = false;
    m_reconnectTimer->stop();

    // try_exit_fallback() already received a fresh dmabuf set. Import it into the
    // layer (which arms an infinite/full-output repaint on every rotation buffer),
    // resume the RenderLoop (uninhibit, balancing the inhibit from onConsumerLost),
    // and mark the layer dirty. scheduleRepaint() keeps the layer's needsRepaint()
    // true so the next composite() paints into the new dmabufs even on an idle
    // desktop. resumeRendering() runs unconditionally to keep inhibit/uninhibit
    // balanced regardless of whether the GL layer is attached yet.
    AnlandEglLayer *layer = m_outputs[0]->eglLayer();
    if (layer) {
        layer->importBuffers(get_buf_count(m_display));
    }
    setupNotifiers();
    m_outputs[0]->resumeRendering();
    if (layer) {
        layer->scheduleRepaint(nullptr);
    }
}

// ---------------------------------------------------------------------------
// Clipboard sync
// ---------------------------------------------------------------------------

/*
 * Pipe-based helper: poll() the read-end of a pipe with a timeout, accumulating
 * data until EOF (the remote side closes its write-end).  Returns the collected
 * bytes or an empty QByteArray on error / timeout.
 */
static QByteArray readDataFromFd(FileDescriptor &fd)
{
    QByteArray buffer;
    pollfd pfd{};
    pfd.fd = fd.get();
    pfd.events = POLLIN;

    while (true) {
        const int ready = poll(&pfd, 1, 1000);
        if (ready < 0) {
            if (errno != EINTR)
                return QByteArray();
        } else if (ready == 0) {
            return QByteArray(); // timeout
        } else {
            char chunk[4096];
            const ssize_t n = read(fd.get(), chunk, sizeof(chunk));
            if (n < 0)
                return QByteArray();
            if (n == 0)
                return buffer;
            buffer.append(chunk, n);
        }
    }
}

/*
 * Read the text/plain content of a Wayland AbstractDataSource (the clipboard
 * selection).  Picks text/plain;charset=utf-8 preferentially, falls back to
 * text/plain.  Returns an empty QByteArray if nothing useful is available.
 */
static QByteArray requestClipboardText(AbstractDataSource *source)
{
    if (!source)
        return QByteArray();

    const QStringList types = source->mimeTypes();
    QString mimeType;
    if (types.contains(QStringLiteral("text/plain;charset=utf-8")))
        mimeType = QStringLiteral("text/plain;charset=utf-8");
    else if (types.contains(QStringLiteral("text/plain")))
        mimeType = QStringLiteral("text/plain");
    else
        return QByteArray(); // no text type available

    std::optional<Pipe> pipe = Pipe::create(O_CLOEXEC);
    if (!pipe)
        return QByteArray();

    source->requestData(mimeType, std::move(pipe->writeEndpoint));
    waylandServer()->display()->flush();
    return readDataFromFd(pipe->readEndpoint);
}

/*
 * Called whenever KWin's SeatInterface::selectionChanged fires.
 * Reads the new clipboard content and pushes it to the consumer.
 * De-duplicates: if the text is identical to the last known value, the
 * send is skipped (avoids the consumer re-posting the same text back to KWin).
 */
void AnlandBackend::onClipboardChanged()
{
    if (m_inFallback)
        return;

    AbstractDataSource *source = waylandServer()->seat()->selection();
    QByteArray text = requestClipboardText(source);

    if (text == m_clipboardText)
        return; // already in sync

    m_clipboardText = text;
    sendClipboardToConsumer(text);
}

/*
 * Push clipboard text to the Android consumer via the data channel.
 * Uses the variable-length INPUT_TYPE_CLIPBOARD event (header + raw UTF-8).
 * An empty text clears the remote clipboard.
 */
void AnlandBackend::sendClipboardToConsumer(const QByteArray &text)
{
    if (m_inFallback)
        return;

    const uint32_t len = static_cast<uint32_t>(text.size());
    const OutputEvent ev = {
        .type = OUTPUT_TYPE_CLIPBOARD,
        .clipboard = { .size = len },
    };
    push_output_event_with_length(m_display, &ev,
                                  const_cast<char *>(text.constData()), len);
}

/*
 * Called from processInputEvent() when the consumer pushes clipboard data to us.
 * Reads the trailing payload, then sets the KWin Wayland selection to that text
 * so all Wayland clients see the change.
 * De-duplicates: if the incoming text matches our m_clipboardText, we skip the
 * setSelection() call to avoid a feedback loop.
 */
void AnlandBackend::sendClipboardToKWin(const QByteArray &text)
{
    if (text == m_clipboardText)
        return; // already in sync — skip to break the loop

    m_clipboardText = text;

    if (!waylandServer())
        return;

    SeatInterface *seat = waylandServer()->seat();
    if (!seat)
        return;

    // Build a minimal QMimeData + AbstractDataSource to feed setSelection().
    // The source wraps the data; setSelection() tells all wl_data_device listeners
    // about the new clipboard content.
    auto *mimeData = new QMimeData();
    mimeData->setData(QStringLiteral("text/plain;charset=utf-8"), text);

    // ClipboardDataSource lives in the QPA plugin namespace; re-create the same
    // pattern inline since it is a trivial AbstractDataSource subclass.
    class AnlandClipboardSource : public AbstractDataSource {
    public:
        explicit AnlandClipboardSource(QMimeData *data, QObject *parent = nullptr)
            : AbstractDataSource(parent), m_data(data) {}
        void requestData(const QString &mimeType, FileDescriptor fd) override
        {
            const QByteArray buf = m_data->data(mimeType);
            // Write asynchronously — the read side is blocked until EOF.
            QtConcurrent::run([buf, fd = std::move(fd)]() mutable {
                size_t remaining = buf.size();
                const char *ptr = buf.constData();
                pollfd pfd{};
                pfd.fd = fd.get();
                pfd.events = POLLOUT;
                while (remaining > 0) {
                    if (poll(&pfd, 1, 5000) <= 0)
                        break;
                    if (!(pfd.revents & POLLOUT))
                        break;
                    ssize_t n = write(fd.get(), ptr, remaining);
                    if (n <= 0)
                        break;
                    ptr += n;
                    remaining -= n;
                }
            });
        }
        void cancel() override {}
        QStringList mimeTypes() const override { return m_data->formats(); }
    private:
        QMimeData *m_data;
    };

    auto *source = new AnlandClipboardSource(mimeData, mimeData);
    seat->setSelection(source, waylandServer()->display()->nextSerial());
    // seat->setSelection() takes a raw pointer; it is released when the next
    // selection replaces it, or when the seat is torn down.  We parent the source
    // to mimeData so it is freed when mimeData goes away — but since setSelection()
    // holds a raw pointer, keep both alive for the lifetime of the seat's selection.
    // Parent the source to the seat to ensure it outlives the selection reference.
    source->setParent(seat);
}

} // namespace KWin
