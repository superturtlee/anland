package com.anland.consumer;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.util.DisplayMetrics;   // ADDED

import java.nio.charset.StandardCharsets;


public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "Anland";

    private SurfaceView surfaceView;
    private boolean surfaceReady = false;
    private String mLastSentClip = null;
    private boolean mClipListening = false;
    private static final String PREFS_NAME = "anland_settings";
    private int customScreenWidth = 0;
    private int customScreenHeight = 0;
    private int viewWidth = 0;
    private int viewHeight = 0;
    private static final String KEY_BOUND_KEYCODE = "bound_keycode";
    private static final String KEY_SOCKET_PATH = "socket_path";
    private static final String KEY_USE_ROOT = "use_root";
    private static final String KEY_MIC_ENABLED = "mic_enabled";
    private static final String KEY_CAMERA_ENABLED = "camera_enabled";
    // Latency presets in ms; 0 = engine default. Shared with SettingsActivity.
    static final String KEY_SPEAKER_LATENCY_MS = "speaker_latency_ms";
    static final String KEY_MIC_LATENCY_MS = "mic_latency_ms";
    private static final int REQ_RECORD_AUDIO = 1001;
    private static final int REQ_CAMERA = 1002;
    // Camera service fds/threads are created once and persist across reconnects;
    // this guards that one-time init (see applyCameraState).
    private boolean cameraInited = false;
    private static final String DEFAULT_SOCKET_PATH = "/data/local/tmp/display_daemon.sock";
    private static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_key_intercept";
    private static final String KEY_EXTRA_KEYS_ENABLED = "extra_keys_bar";
    private static final String KEY_AUTO_SHOW_EXTRA_KEYS = "auto_show_extra_keys";
    private static final String KEY_BACK_OPENS_EXTRA_KEYS = "back_opens_extra_keys";
    private static final String KEY_EXTRA_KEYS_LAYOUT = "extra_keys_layout";
    // When on, the IME and extra-keys bar float over the display instead of
    // shrinking it: the bar rides up with the keyboard but the surface keeps
    // its full size. See relayout() and buildExtraKeysBar().
    private static final String KEY_KEYBOARD_FLOATING = "keyboard_floating";
    private boolean mKeyboardFloating = false;
    // Persistent "tap to open Settings" notification, toggleable in Settings > General.
    private static final String KEY_NOTIFICATION_ENABLED = "settings_notification";
    private EditText hiddenInput;
    private InputMethodManager imm;
    private int mImeBottom = 0;   // last IME bottom inset
    private int mBarHeight = 0;   // extra-keys bar height in px
    private ExtraKeysBar extraKeysBar;
    private FrameLayout mRoot;    // content root, host of the extra-keys bar
    private float mDensity = 1f;
    // Layout JSON the current bar was built from; used to detect edits on resume.
    private String mAppliedLayoutJson = "";

    public static MainActivity sInstance;

    // ADDED: VirtualKeyboardView instance
    private VirtualKeyboardView virtualKeyboardView;

    // evdev keycodes (linux/input-event-codes.h) for the editing keys a soft
    // keyboard emits as key events rather than text.
    private static final int EVDEV_ESC = 1;
    private static final int EVDEV_BACKSPACE = 14;
    private static final int EVDEV_TAB = 15;
    private static final int EVDEV_ENTER = 28;
    private static final int EVDEV_UP = 103;
    private static final int EVDEV_LEFT = 105;
    private static final int EVDEV_RIGHT = 106;
    private static final int EVDEV_DOWN = 108;
    private static final int EVDEV_DELETE = 111;

    // ==================== 新增：触摸板相关设置 ====================
    public static final String KEY_TOUCHPAD_MODE = "touchpad_mode";
    public static final String KEY_MOUSE_ACCEL = "mouse_speed"; // 名称仍为 speed，实际控制加速度强度

    private boolean isTouchpadMode = true;
    private float mouseAccelStrength = 1.0f; // 加速度强度，0.5 ~ 10.0

    // 状态机
    private static final int STATE_IDLE = 0;
    private static final int STATE_ONE_FINGER = 1;
    private static final int STATE_TWO_FINGER = 2;
    private static final int STATE_DRAGGING = 3;
    private int currentState = STATE_IDLE;

    private float lastX1, lastY1;
    private float startX1, startY1;
    private float lastX2, lastY2;
    private long downTime1;
    private float touchSlop;

    private boolean isSingleTapCandidate = false;
    private boolean isTwoFingerTapCandidate = false;
    private boolean isDraggingActive = false;

    private long lastTapTime = 0;
    private float lastTapX, lastTapY;
    private boolean isDoubleTapPending = false;

    private static final long TOUCH_LONG_PRESS_TIMEOUT = 500;
    private boolean hasLongPressed = false;
    private boolean isLongPressPossible = false;
    private boolean isMultiFinger = false;

    // 鼠标位置（相对模式）
    private float mouseX = 0;
    private float mouseY = 0;
    private int screenWidth = 1920;
    private int screenHeight = 1080;

    // ===== 调整后的平滑/抗抖动参数（更灵敏、更连续） =====
    private static final float DEAD_ZONE = 0.3f;          // 死区从 0.5 降到 0.3
    private static final float SMOOTHING_FACTOR = 0.45f;   // 提高响应速度
    private static final float ACCUMULATED_THRESHOLD = 0.1f; // 从 0.8 大幅降低，让移动更连续

    private float smoothedDx = 0f;
    private float smoothedDy = 0f;
    private float accumulatedX = 0f;
    private float accumulatedY = 0f;
    private boolean smoothInitialized = false;

    static {
        System.loadLibrary("anland_consumer");
    }

    private native void nativeConfigure(String socketPath, boolean useRoot,
                                        String helperPath, String bridgePath);
    private native void nativeStart(Surface surface);
    private native void nativeStop();
    private native void nativeSendTouch(int action, float x, float y, int pointerId);
    private native void nativeSendTouchFrame();
    private native void nativeSendKey(int action, int keycode);
    private native void nativeSendMouseMotion(float x, float y, float dx, float dy);
    private native void nativeSendMouseButton(int button, boolean pressed);
    private native void nativeSendMouseScroll(int axis, float value);
    private native void nativeSetRefreshRate(float hz);
    private native void nativeSendClipboard(byte[] data);
    private native void nativeSendTextInput(byte[] data);
    private native void nativeSetMicEnabled(boolean enabled);
    private native void nativeSetAudioLatency(int speakerMs, int micMs);
    // Called from native event thread to set clipboard text on Android
    public void nativeSetClipboardText(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            mLastSentClip = text;  // 记录，clipListener 回环时会比对跳过
            cm.setPrimaryClip(ClipData.newPlainText("anland", text));
        }
    }
    // Called from native C on exit_fallback to send initial clipboard sync
    public void nativeClipboardSync() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).getText();
            if (text != null) {
                mLastSentClip = text.toString();
                nativeSendClipboard(text.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
        () -> pushClipboard();

    // Called from native C: true = register clip listener, false = unregister
    public void nativeClipListening(boolean enable) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        if (enable) {
            if (mClipListening) return;  // already registered
            cm.addPrimaryClipChangedListener(clipListener);
            mClipListening = true;
        } else {
            if (!mClipListening) return;  // not registered
            cm.removePrimaryClipChangedListener(clipListener);
            mClipListening = false;
        }
    }

    // Push clipboard only if content actually changed
    private void pushClipboard() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).getText();
            if (text != null) {
                String clipText = text.toString();
                if (!clipText.equals(mLastSentClip)) {
                    mLastSentClip = clipText;
                    nativeSendClipboard(clipText.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    // Forwards the current display refresh rate to the daemon so KWin can repace
    // its RenderLoop. Re-fires on every onDisplayChanged (e.g. 60/90/120 switch).
    private final DisplayManager.DisplayListener displayListener =
        new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                Display d = getDisplay();
                if (d != null && d.getDisplayId() == displayId)
                    pushRefreshRate();
            }
        };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            pushClipboard();
        }
    }

    private void pushRefreshRate() {
        Display d = getDisplay();
        if (d != null)
            nativeSetRefreshRate(d.getRefreshRate());
    }

    // Push the current connection settings (socket path / root mode) to native
    // before (re)connecting. The root helper is the executable bundled in the
    // app's native lib dir; the bridge is a unix socket in our cache dir that
    // the helper, launched via su, uses to hand back the daemon fd.
    private void applyConnectionConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String sock = prefs.getString(KEY_SOCKET_PATH, DEFAULT_SOCKET_PATH);
        if (sock == null || sock.trim().isEmpty())
            sock = DEFAULT_SOCKET_PATH;
        boolean useRoot = prefs.getBoolean(KEY_USE_ROOT, true);
        String helperPath = getApplicationInfo().nativeLibraryDir + "/libfdhelper.so";
        String bridgePath = getCacheDir().getAbsolutePath() + "/anland_fdbridge.sock";
        nativeConfigure(sock.trim(), useRoot, helperPath, bridgePath);
        int customW = prefs.getInt("custom_width", 0);
        int customH = prefs.getInt("custom_height", 0);
        customScreenWidth = prefs.getInt("custom_width", 0);
        customScreenHeight = prefs.getInt("custom_height", 0);
        nativeSetCustomResolution(customW, customH);
    }
    
    private native void nativeSetCustomResolution(int width, int height);
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sInstance = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        // Take over inset handling: the IME insets are dispatched to our
        // OnApplyWindowInsetsListener (so we can resize the surface) instead of
        // the system auto-panning the fullscreen window.
        getWindow().setDecorFitsSystemWindows(false);

        surfaceView = new SurfaceView(this);
        initHiddenInput();

        FrameLayout root = new FrameLayout(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        // 1x1 so the IME target never overlaps the surface and steals touches.
        root.addView(hiddenInput, new FrameLayout.LayoutParams(1, 1));

        // Bottom extra-keys bar (Termux-style). Hidden by default; toggled by the
        // settings switch and synced in onResume. The layout (and thus the row
        // count / height) comes from the user's JSON config; see buildExtraKeysBar.
        mRoot = root;
        mDensity = getResources().getDisplayMetrics().density;
        mKeyboardFloating = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_KEYBOARD_FLOATING, true);
        buildExtraKeysBar();

        // ADDED: Create VirtualKeyboardView (hidden initially)
        virtualKeyboardView = new VirtualKeyboardView(this);
        virtualKeyboardView.setVisibility(View.GONE);
        virtualKeyboardView.setOnKeyEventListener(new VirtualKeyboardView.OnKeyEventListener() {
            @Override
            public void onKeyDown(int scanCode) {
                nativeSendKey(0, scanCode);
            }
            @Override
            public void onKeyUp(int scanCode) {
                nativeSendKey(1, scanCode);
            }
        });
        // Add to root with no gravity – we will position manually.
        root.addView(virtualKeyboardView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.NO_GRAVITY
        ));
        // Reposition the virtual keyboard when the root layout size changes
        // (e.g. freeform / small-window mode resize).
        root.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int newW = right - left;
            int newH = bottom - top;
            int oldW = oldRight - oldLeft;
            int oldH = oldBottom - oldTop;
            Log.d("VirtualKeyboard", "root layout changed: " + newW + "x" + newH
                    + " (was " + oldW + "x" + oldH + ")");
            if (newW != oldW || newH != oldH) {
                if (virtualKeyboardView != null
                        && virtualKeyboardView.getVisibility() == View.VISIBLE) {
                    positionVirtualKeyboard();
                }
            }
        });
        // Positioning happens lazily the first time the keyboard is shown
        // (see toggleVirtualKeyboard). Positioning it here would spin forever:
        // the view starts GONE and a GONE view is never measured.

        setContentView(root);
        surfaceView.getHolder().addCallback(this);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            // When the IME hides by any means (toggle, system back, or the IME's
            // own close button), release the hidden input so its focus state
            // stays in sync — otherwise reopening needs a second press.
            if (!insets.isVisible(WindowInsets.Type.ime()))
                releaseHiddenInput();
            applyImeInset(insets);
            return v.onApplyWindowInsets(insets);
        });

        setupFullscreen();
        setupCursorHiding();

        // ===== 新增：加载触摸板设置 =====
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isTouchpadMode = prefs.getBoolean(KEY_TOUCHPAD_MODE, false);
        mouseAccelStrength = prefs.getFloat(KEY_MOUSE_ACCEL, 1.0f);
        mouseAccelStrength = Math.max(0.5f, Math.min(10.0f, mouseAccelStrength)); // 范围扩大到 10.0
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        updateScreenSize();
        mouseX = screenWidth / 2f;
        mouseY = screenHeight / 2f;
    }

    private static final String NOTIFICATION_CHANNEL = "anland_channel";
    private static final int NOTIFICATION_ID = 1;

    private void showSettingsNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);

        Intent intent = new Intent(this, SettingsActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build();

        nm.notify(NOTIFICATION_ID, notification);
    }

    // ADDED: Helper to position virtual keyboard at bottom-center
    private void positionVirtualKeyboard() {
        if (virtualKeyboardView == null) return;
        int w = virtualKeyboardView.getMeasuredWidth();
        int h = virtualKeyboardView.getMeasuredHeight();
        if (w <= 0 || h <= 0) {
            // Only retry while the keyboard is actually visible. A GONE view is
            // never measured (width/height stay 0), so reposting unconditionally
            // would re-queue this Runnable on the main thread every frame forever
            // and cause global jank/卡顿 even while the keyboard is hidden.
            if (virtualKeyboardView.getVisibility() == View.VISIBLE) {
                virtualKeyboardView.post(this::positionVirtualKeyboard);
            }
            return;
        }
        // Use the root layout's dimensions instead of DisplayMetrics so that
        // positioning is correct in freeform / small-window mode.
        int parentW = mRoot.getWidth();
        int parentH = mRoot.getHeight();
        if (parentW <= 0 || parentH <= 0) {
            // Root not laid out yet — retry next frame.
            if (virtualKeyboardView.getVisibility() == View.VISIBLE) {
                virtualKeyboardView.post(this::positionVirtualKeyboard);
            }
            return;
        }
        float x = (parentW - w) / 2f;
        float y = parentH - h - dpToPx(50);
        // Clamp to visible area.
        x = Math.max(0, Math.min(x, parentW - w));
        y = Math.max(0, Math.min(y, parentH - h));
        virtualKeyboardView.setX(x);
        virtualKeyboardView.setY(y);
        Log.d("VirtualKeyboard", "positionVirtualKeyboard: x=" + x + ", y=" + y
                + " parent=" + parentW + "x" + parentH + " view=" + w + "x" + h);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void setupFullscreen() {
        WindowInsetsController ctrl = getWindow().getInsetsController();
        if (ctrl != null) {
            ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            ctrl.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        getWindow().getAttributes().layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    }

    private void setupCursorHiding() {
        surfaceView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Show settings notification while in foreground, unless disabled in Settings.
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATION_ENABLED, true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1003);
            } else {
                showSettingsNotification();
            }
        } else {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        }

        // Re-check accessibility service state on resume
        KeyInterceptor.recheck();

        // If the user edited the layout JSON in Settings, rebuild the bar so the
        // change takes effect on return to the desktop.
        String layoutJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_EXTRA_KEYS_LAYOUT, "");
        if (!layoutJson.equals(mAppliedLayoutJson))
            rebuildExtraKeysBar();

        // Pick up a Keyboard-floating toggle made in Settings: update the bar's
        // backdrop and re-run the layout so the surface margin tracks the new mode.
        mKeyboardFloating = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_KEYBOARD_FLOATING, true);
        if (extraKeysBar != null)
            extraKeysBar.setFloating(mKeyboardFloating);
        relayout();

        // Sync extra-keys bar visibility with the settings switches. With auto-show
        // ON the bar tracks the keyboard (hidden now if the IME isn't up); with it
        // OFF the master switch decides. See shouldShowBar.
        setExtraKeysBarVisible(shouldShowBar(isImeVisible()));

        setupFullscreen();
        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null)
            dm.registerDisplayListener(displayListener, null);
        // Bring the camera service up (or confirm it disabled) BEFORE nativeStart, so
        // the render thread's do_connect() sees a settled camera_service_is_ready()
        // and registers SERVICE_TYPE_CAMERA on the very first connect rather than a
        // later reconnect. Idempotent, so safe to call on every resume.
        applyCameraState();
        if (surfaceReady) {
            nativeStop();
            applyConnectionConfig();
            nativeStart(surfaceView.getHolder().getSurface());
            pushRefreshRate();
            applyMicState();
            applyAudioLatency();
        }

        // ===== 新增：重新读取触摸板设置 =====
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isTouchpadMode = prefs.getBoolean(KEY_TOUCHPAD_MODE, false);
        mouseAccelStrength = prefs.getFloat(KEY_MOUSE_ACCEL, 1.0f);
        mouseAccelStrength = Math.max(0.5f, Math.min(10.0f, mouseAccelStrength));
    }

    @Override
    protected void onPause() {
        super.onPause();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null)
            dm.unregisterDisplayListener(displayListener);
        nativeStop();
    }

    @Override
    protected void onDestroy() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        if (cameraInited) {
            CameraServices.nativeDestroyCameraService();
            cameraInited = false;
        }
        super.onDestroy();
    }

    /*
     * Bring the camera service up only when the user enabled it AND CAMERA is
     * granted. The native fds/threads are created once and persist across transport
     * restarts, so this is idempotent (guarded by cameraInited). When the toggle is
     * off we never init, so do_connect() never registers SERVICE_TYPE_CAMERA and the
     * producer never sees it. Request the permission if enabled but not yet granted;
     * onRequestPermissionsResult finishes the init.
     */
    private void applyCameraState() {
        boolean want = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_CAMERA_ENABLED, false);
        if (!want || cameraInited)
            return;
        if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            CameraServices.nativeInitCameraService(this);
            cameraInited = true;
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    /*
     * Forward the mic only when the user enabled it AND RECORD_AUDIO is granted.
     * If enabled but not yet granted, request it; onRequestPermissionsResult applies
     * the result. Safe to call after every nativeStart (re)connect.
     */
    /* Push the speaker/mic latency presets to native (which forwards them to the
     * producer's PipeWire nodes). Safe to call after every (re)connect and whenever
     * the user changes a preset. */
    private void applyAudioLatency() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int speakerMs = prefs.getInt(KEY_SPEAKER_LATENCY_MS, 0);
        int micMs = prefs.getInt(KEY_MIC_LATENCY_MS, 0);
        nativeSetAudioLatency(speakerMs, micMs);
    }

    private void applyMicState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean want = prefs.getBoolean(KEY_MIC_ENABLED, false);
        if (!want) {
            nativeSetMicEnabled(false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            nativeSetMicEnabled(true);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                               REQ_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            nativeSetMicEnabled(granted);
        } else if (requestCode == REQ_CAMERA) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && !cameraInited && getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_CAMERA_ENABLED, false)) {
                CameraServices.nativeInitCameraService(this);
                cameraInited = true;
            }
        } else if (requestCode == 1003) {
            if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_NOTIFICATION_ENABLED, true)) {
                showSettingsNotification();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
        viewWidth = width;
        viewHeight = height;
        surfaceReady = true;
        // Same ordering guarantee as onResume: camera service settled before connect.
        applyCameraState();
        nativeStop();
        applyConnectionConfig();
        nativeStart(holder.getSurface());
        pushRefreshRate();
        applyMicState();
        applyAudioLatency();

        // ===== 新增：更新屏幕尺寸并重置平滑状态 =====
        updateScreenSize();
        mouseX = clamp(mouseX, 0, screenWidth);
        mouseY = clamp(mouseY, 0, screenHeight);
        resetSmoothing();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        nativeStop();
    }

    private void initHiddenInput() {
        imm = getSystemService(InputMethodManager.class);

        // Anonymous subclass so we can hand the IME our own InputConnection that
        // forwards text/keys to the remote in real time instead of buffering
        // them in an Editable that only flushes on Enter.
        hiddenInput = new EditText(this) {
            @Override
            public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                super.onCreateInputConnection(outAttrs); // fills outAttrs only
                return new ForwardingInputConnection(this);
            }
        };
        hiddenInput.setBackgroundColor(Color.TRANSPARENT);
        hiddenInput.setCursorVisible(false);
        hiddenInput.setAlpha(0f);
        hiddenInput.setEnabled(false);          // 默认不拦截触摸
        hiddenInput.setFocusable(false);
        hiddenInput.setFocusableInTouchMode(false);
        hiddenInput.setClickable(false);
        hiddenInput.setLongClickable(false);
        // NO_ENTER_ACTION: deliver Enter as a key event we can forward, rather
        // than an editor action we'd have to swallow. NO_FULLSCREEN: never show
        // the landscape extract editor, which buffers text instead of sending it
        // live through our InputConnection.
        hiddenInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | EditorInfo.IME_FLAG_NO_FULLSCREEN
            | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        hiddenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL);
    }

    // Mirror of the text we have pushed to the remote via the IME path, with the
    // cursor implicitly at its end (we only ever append text or backspace from the
    // tail). Maintained at the sendText/tapKey choke points so it stays accurate no
    // matter which InputConnection method drove the change. Used to re-seed the
    // composing tracker when the IME reclaims already-sent text as a composing
    // region (see ForwardingInputConnection.setComposingRegion).
    private final StringBuilder mMirror = new StringBuilder();
    // Only the trailing text is ever needed (a composing region is at most a word);
    // drop the head past this bound so a long session can't grow the buffer forever.
    private static final int MIRROR_CAP = 4096;

    private void sendText(String text) {
        if (text.isEmpty()) return;
        mMirror.append(text);
        if (mMirror.length() > MIRROR_CAP) {
            mMirror.delete(0, mMirror.length() - MIRROR_CAP);
        }
        nativeSendTextInput(text.getBytes(StandardCharsets.UTF_8));
    }

    private void tapKey(int evdevCode) {
        nativeSendKey(0, evdevCode);
        nativeSendKey(1, evdevCode);
    }

    // Maps soft-keyboard characters to Android key codes so a bar modifier can be
    // combined with them. Shared instance; KeyCharacterMap.getEvents is read-only.
    private final KeyCharacterMap mVirtualKcm =
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    /*
     * If an extra-keys-bar modifier (CTRL/ALT/SHIFT) is currently held, take the
     * first key-mappable character of `s` and send it as a modifier combo (e.g.
     * Ctrl+C) through the bar, which also clears the unlocked modifiers. Returns
     * true if the input was consumed this way, false to fall back to plain text.
     */
    private boolean maybeSendModifierCombo(String s) {
        if (extraKeysBar == null || !extraKeysBar.hasActiveModifier()
                || s == null || s.isEmpty())
            return false;
        for (int i = 0; i < s.length(); i++) {
            int evdev = charToEvdev(s.charAt(i));
            if (evdev != -1) {
                extraKeysBar.sendKeyComboFromExternal(evdev);
                return true;
            }
        }
        return false;
    }

    // Convert a character to an evdev scancode via the virtual key character map
    // and KeyCodeMapper. Returns -1 if it can't be expressed as a single key.
    private int charToEvdev(char ch) {
        KeyEvent[] events = mVirtualKcm.getEvents(new char[]{ch});
        if (events != null) {
            for (KeyEvent e : events) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    int evdev = KeyCodeMapper.getScanCode(e.getKeyCode());
                    if (evdev != -1) return evdev;
                }
            }
        }
        return -1;
    }

    // Map the few Android key codes a soft keyboard delivers as key events to the
    // evdev keycodes KWin expects. Returns 0 for keys we don't forward.
    private static int toEvdevKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return EVDEV_ENTER;
            case KeyEvent.KEYCODE_DEL:          return EVDEV_BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL:  return EVDEV_DELETE;
            case KeyEvent.KEYCODE_TAB:          return EVDEV_TAB;
            case KeyEvent.KEYCODE_ESCAPE:       return EVDEV_ESC;
            case KeyEvent.KEYCODE_DPAD_LEFT:    return EVDEV_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:   return EVDEV_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP:      return EVDEV_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:    return EVDEV_DOWN;
            default:                            return 0;
        }
    }

    /*
     * Bridges the soft keyboard to the remote compositor in real time. Committed
     * text is forwarded as UTF-8 immediately; composing (preedit) text is
     * forwarded as it changes by diffing against what we already sent, so each
     * keystroke shows up live without waiting for Enter. Editing keys (Enter,
     * Backspace, ...) are forwarded as evdev key taps. We keep no Editable of our
     * own, so nothing accumulates between commits.
     */
    private final class ForwardingInputConnection extends BaseInputConnection {
        // What we have already forwarded for the in-progress composition.
        private final StringBuilder composing = new StringBuilder();

        ForwardingInputConnection(View target) {
            super(target, false);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            final String s = text == null ? "" : text.toString();
            // If a bar modifier (CTRL/ALT/...) is held, combine it with the typed
            // character and send as a key combo instead of inserting text.
            if (maybeSendModifierCombo(s)) {
                composing.setLength(0);
                return true;
            }
            // Fast path: the commit just finalizes the current composition
            // unchanged — already forwarded, so only drop the tracker.
            if (composing.length() > 0 && composing.toString().equals(s)) {
                composing.setLength(0);
                return true;
            }
            eraseComposing();
            sendText(s);
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            final String s = text == null ? "" : text.toString();
            if (maybeSendModifierCombo(s)) {
                composing.setLength(0);
                return true;
            }
            replaceComposing(s);
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composing.setLength(0); // accepted as-is; keep what we forwarded
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            for (int i = 0; i < beforeLength; i++) {
                if (mMirror.length() > 0) {
                    mMirror.setLength(mMirror.offsetByCodePoints(mMirror.length(), -1));
                }
                tapKey(EVDEV_BACKSPACE);
            }
            for (int i = 0; i < afterLength; i++) {
                tapKey(EVDEV_DELETE);
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            for (int i = 0; i < beforeLength; i++) {
                if (mMirror.length() > 0) {
                    mMirror.setLength(mMirror.offsetByCodePoints(mMirror.length(), -1));
                }
                tapKey(EVDEV_BACKSPACE);
            }
            for (int i = 0; i < afterLength; i++) {
                tapKey(EVDEV_DELETE);
            }
            return true;
        }

        // The IME reclaims text it previously committed as a fresh composing region
        // (e.g. backspacing into a finished word: it deletes a char, then re-composes
        // the remainder before replacing it). We keep no Editable, so the base class
        // can't honour this — and because our composing tracker is empty at this
        // point, the follow-up setComposingText would diff against "" and *append*
        // the replacement instead of overwriting, turning "shado"+"shad" into
        // "shadoshad". Re-seed the tracker with the region's text so replaceComposing()
        // backspaces the difference. The cursor always sits at the tail of what we've
        // sent, so the region is the last (end - start) chars of the mirror — reading
        // it as a length keeps us correct whether the IME's indices are document- or
        // word-relative.
        @Override
        public boolean setComposingRegion(int start, int end) {
            final int len = end - start;
            if (len >= 0 && len <= mMirror.length()) {
                composing.setLength(0);
                composing.append(mMirror, mMirror.length() - len, mMirror.length());
            }
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            final int evdev = toEvdevKey(event.getKeyCode());
            if (evdev == 0) {
                return super.sendKeyEvent(event);
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                nativeSendKey(0, evdev);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                nativeSendKey(1, evdev);
                // Keep the mirror consistent with a raw key edit. A backspace pops the
                // tail; anything else (Enter, Tab, arrows, ...) moves the cursor or
                // inserts content our tail-only model can't track, so drop the mirror
                // and composing tracker rather than risk seeding a bad region later.
                if (evdev == EVDEV_BACKSPACE) {
                    if (mMirror.length() > 0)
                        mMirror.setLength(mMirror.offsetByCodePoints(mMirror.length(), -1));
                } else {
                    mMirror.setLength(0);
                    composing.setLength(0);
                }
            }
            return true;
        }

        // Forward only the delta between the previously-sent composition and the
        // new one: backspace the changed tail, then send the new tail.
        private void replaceComposing(String next) {
            final String prev = composing.toString();
            int prefix = 0;
            final int min = Math.min(prev.length(), next.length());
            while (prefix < min && prev.charAt(prefix) == next.charAt(prefix)) {
                prefix++;
            }
            if (prefix > 0 && Character.isHighSurrogate(prev.charAt(prefix - 1))) {
                prefix--; // never split a surrogate pair
            }
            final int erase = prev.codePointCount(prefix, prev.length());
            for (int i = 0; i < erase; i++) {
                if (mMirror.length() > 0) {
                    mMirror.setLength(mMirror.offsetByCodePoints(mMirror.length(), -1));
                }
                tapKey(EVDEV_BACKSPACE);
            }
            if (prefix < next.length()) {
                sendText(next.substring(prefix));
            }
            composing.setLength(0);
            composing.append(next);
        }

        private void eraseComposing() {
            final int erase = composing.codePointCount(0, composing.length());
            for (int i = 0; i < erase; i++) {
                if (mMirror.length() > 0) {
                    mMirror.setLength(mMirror.offsetByCodePoints(mMirror.length(), -1));
                }
                tapKey(EVDEV_BACKSPACE);
            }
            composing.setLength(0);
        }
    }

    // Shrink the surface to the area above the keyboard (and the extra-keys bar,
    // if shown) by giving it a bottom margin. The size change flows through
    // surfaceChanged -> nativeStart and the producer's resize path, so the
    // focused window relayouts into the upper region instead of hiding behind
    // the keyboard. Reset when the IME goes away.
    private void applyImeInset(WindowInsets insets) {
        int newImeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
        boolean imeVisible = newImeBottom > 0;
        boolean wasImeVisible = mImeBottom > 0;
    
        mImeBottom = newImeBottom;
    
        if (imeVisible != wasImeVisible)
            setExtraKeysBarVisible(shouldShowBar(imeVisible));

        relayout();
    }

    // Desired extra-keys bar visibility for the current keyboard state. The two
    // switches are independent: with "auto-show" ON the bar tracks the keyboard
    // (regardless of the master switch), so it appears whenever the IME opens —
    // including via the bound virtual-keyboard key, the app's only other opener.
    // With "auto-show" OFF the master switch keeps the bar persistently visible.
    private boolean shouldShowBar(boolean imeVisible) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean autoShow = prefs.getBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, true);
        if (autoShow)
            return imeVisible;
        return prefs.getBoolean(KEY_EXTRA_KEYS_ENABLED, false);
    }

    // Recompute the surface bottom margin and the bar position from the current
    // IME inset and bar visibility. The surface ends above the bar, which sits
    // directly on top of the IME: "surface / extra-keys bar / IME" bottom-up.
    private void relayout() {
        boolean barVisible = extraKeysBar != null && extraKeysBar.getVisibility() == View.VISIBLE;
        int barH = barVisible ? mBarHeight : 0;
        // Floating mode: keyboard + bar overlay the display, so the surface keeps
        // its full size (target 0). Default mode: shrink the surface above both.
        int target = mKeyboardFloating ? 0 : (mImeBottom + barH);

        FrameLayout.LayoutParams lp =
            (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.bottomMargin != target) {       // skip redundant surface restart
            lp.bottomMargin = target;
            surfaceView.setLayoutParams(lp);
        }
        if (extraKeysBar != null)
            extraKeysBar.setTranslationY(-mImeBottom);
    }

    // Show/hide the extra-keys bar and re-apply the layout so the display area
    // is compressed (shown) or restored (hidden).
    private void setExtraKeysBarVisible(boolean visible) {
        if (extraKeysBar == null) return;
        boolean cur = extraKeysBar.getVisibility() == View.VISIBLE;
        if (cur == visible) return;
        extraKeysBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) extraKeysBar.reset();
        relayout();
    }

    // Construct the extra-keys bar from the user's saved JSON layout and add it to
    // the content root (hidden). The bar height mirrors Termux at 37.5dp/row and
    // scales with the parsed row count. Records the layout JSON it was built from.
    private void buildExtraKeysBar() {
        extraKeysBar = new ExtraKeysBar(this, new ExtraKeysBar.Sender() {
            @Override public void key(int action, int evdev) { nativeSendKey(action, evdev); }
            @Override public void text(String s) {
                if (!s.isEmpty()) nativeSendTextInput(s.getBytes(StandardCharsets.UTF_8));
            }
            // Tapping the ⌨ key keeps the original behaviour: toggle the system IME.
            @Override public void toggleKeyboard() { toggleSystemKeyboard(); }
            // Pulling up on the ⌨ key toggles the floating virtual keyboard.
            @Override public void toggleVirtualKeyboard() {
                if (virtualKeyboardView.getVisibility() == View.VISIBLE) {
                    virtualKeyboardView.setVisibility(View.GONE);
                } else {
                    Log.d("VirtualKeyboard", "toggle: showing keyboard, mRoot="
                            + mRoot.getWidth() + "x" + mRoot.getHeight());
                    virtualKeyboardView.setVisibility(View.VISIBLE);
                    virtualKeyboardView.bringToFront();
                    // Re-position it (in case screen size changed)
                    positionVirtualKeyboard();
                    // Hide the system IME to avoid overlap with the floating keyboard.
                    InputMethodManager imm = getSystemService(InputMethodManager.class);
                    if (imm != null && getCurrentFocus() != null) {
                        imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
                    }
                }
            }
            @Override public void openSettings() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        mBarHeight = Math.round(37.5f * mDensity * extraKeysBar.getRowCount());
        extraKeysBar.setFloating(mKeyboardFloating);
        extraKeysBar.setVisibility(View.GONE);
        mRoot.addView(extraKeysBar, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, mBarHeight, Gravity.BOTTOM));
        mAppliedLayoutJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_EXTRA_KEYS_LAYOUT, "");
    }

    // Replace the bar with a freshly-parsed one after the user edits the layout in
    // Settings. Called from onResume when the saved JSON no longer matches what the
    // current bar was built from.
    private void rebuildExtraKeysBar() {
        if (mRoot == null) return;
        if (extraKeysBar != null) {
            extraKeysBar.reset();
            mRoot.removeView(extraKeysBar);
        }
        buildExtraKeysBar();
        setExtraKeysBarVisible(shouldShowBar(isImeVisible()));
        relayout();
    }

    // Toggle the extra-keys bar on its own (e.g. from the Back key), independent of
    // the soft keyboard. Showing it just compresses the display area above the bar.
    private void toggleExtraKeysBar() {
        boolean visible = extraKeysBar != null
            && extraKeysBar.getVisibility() == View.VISIBLE;
        setExtraKeysBarVisible(!visible);
    }

    // Tracks whether we have requested the IME to show.  In freeform mode the
    // floating IME does NOT affect WindowInsets so isImeVisible() would always
    // return false; this flag lets toggleSystemKeyboard() know the real state.
    private boolean imeRequested = false;

    private boolean isImeVisible() {
        if (imeRequested) return true;
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }

    private void releaseHiddenInput() {
        if (!hiddenInput.isEnabled()) return;  // already released
        hiddenInput.clearFocus();
        hiddenInput.setFocusable(false);
        hiddenInput.setEnabled(false);
    }

    // Toggle the system IME (soft keyboard). Driven by the ⌨ bar key tap and the
    // user-bound hardware keycode.
    private void toggleSystemKeyboard() {
        if (imm == null) imm = getSystemService(InputMethodManager.class);
        if (imm == null) return;
        if (isImeVisible()) {
            imm.hideSoftInputFromWindow(hiddenInput.getWindowToken(), 0);
            releaseHiddenInput();
            imeRequested = false;
            // In freeform mode the inset callback may not fire; hide the bar
            // explicitly so it tracks the IME state in all modes.
            setExtraKeysBarVisible(shouldShowBar(false));
        } else {
            hiddenInput.setEnabled(true);
            hiddenInput.setFocusable(true);
            hiddenInput.setFocusableInTouchMode(true);
            hiddenInput.requestFocus();
            imm.showSoftInput(hiddenInput, InputMethodManager.SHOW_IMPLICIT);
            imeRequested = true;
            // In freeform / small-window mode the IME appears as a floating
            // window that does NOT trigger window insets, so applyImeInset()
            // is never called and the extra-keys bar stays hidden.  Show it
            // explicitly here so the bar appears alongside the IME in all modes.
            setExtraKeysBarVisible(shouldShowBar(true));
        }
    }

    // ================================================================
    // 原有 onTouchEvent 仅在最前面插入了一个分支判断，其余原封不动
    // ================================================================
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // ===== 新增：触摸板模式优先处理（仅针对非鼠标触摸事件） =====
        if (isTouchpadMode && !isMouseEvent(event)) {
            return handleTouchpadGesture(event);
        }

        // 以下为原有代码，一字未改
        if (isMouseEvent(event)) {
            int cls = event.getClassification();
            if (cls == CLASSIFICATION_TWO_FINGER_SWIPE)
                return handleTouchpadScroll(event);
            if (cls == CLASSIFICATION_MULTI_FINGER_SWIPE || cls == CLASSIFICATION_PINCH)
                return handleTouchEvent(event);
            return handleMouseEvent(event);
        }
        return handleTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isMouseEvent(event)) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_MOVE) {
                        
                // Масштабирование
                float scaleX = (customScreenWidth > 0 && viewWidth > 0) ? 
                        (float)customScreenWidth / viewWidth : 1.0f;
                float scaleY = (customScreenHeight > 0 && viewHeight > 0) ? 
                        (float)customScreenHeight / viewHeight : 1.0f;
        
                nativeSendMouseMotion(event.getX()*scaleX, event.getY()*scaleY,
                                      event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                                      event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y));
                return true;
            }
            if (action == MotionEvent.ACTION_SCROLL) {
                float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                if (vScroll != 0)
                    nativeSendMouseScroll(0, -vScroll * 10);
                if (hScroll != 0)
                    nativeSendMouseScroll(1, hScroll * 10);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() > 0)
            return true;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int boundKeycode = prefs.getInt(KEY_BOUND_KEYCODE, -1);
        if (boundKeycode != -1 && keyCode == boundKeycode) {
            toggleSystemKeyboard();   // Keep original bound key behavior (system IME)
            return true;
        }

        // Back key toggles the extra-keys bar (without opening the soft keyboard)
        // when enabled in settings. Leaves the default swallow behaviour otherwise.
        if (keyCode == KeyEvent.KEYCODE_BACK
                && prefs.getBoolean(KEY_BACK_OPENS_EXTRA_KEYS, true)) {
            toggleExtraKeysBar();
            return true;
        }

        int scanCode = event.getScanCode();
        if (scanCode != 0) {
            nativeSendKey(0, scanCode);
            return true;
        }

        // fallback: when scancode is 0 (e.g. Fn key combos), map via KeyCodeMapper
        int evdev = KeyCodeMapper.getScanCode(keyCode);
        if (evdev != -1) {
            nativeSendKey(0, evdev);
            return true;
        }
        return true;
    }

    // Some OEM ROMs (notably Xiaomi/HyperOS) dispatch Back via onBackPressed()
    // instead of onKeyDown().  Without this override the default Activity
    // onBackPressed() calls finish() — the app just exits.
    // Keep this empty (same approach as Termux-X11): the actual Back key
    // handling lives in onKeyDown(); this override simply prevents the
    // system from finishing the activity via gesture navigation.
    @Override
    public void onBackPressed() {
    }

    // Called from KeyInterceptor (accessibility service) to handle keys that
    // the normal onKeyDown/onKeyUp might miss (e.g. Fn combos).
    public boolean handleAccessibilityKey(KeyEvent event) {
        if (event.getRepeatCount() > 0)
            return true;

        int scanCode = event.getScanCode();
        if (scanCode != 0 && event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) {
            // Some Fn combos deliver KEYCODE_UNKNOWN with a valid scancode
            nativeSendKey(event.getAction() == KeyEvent.ACTION_DOWN ? 0 : 1, scanCode);
            return true;
        }

        int evdev = KeyCodeMapper.getScanCode(event.getKeyCode());
        if (evdev != -1) {
            nativeSendKey(event.getAction() == KeyEvent.ACTION_DOWN ? 0 : 1, evdev);
            return true;
        }

        // If both keyCode and scancode are unknown, store/replay raw scancode anyway
        if (scanCode != 0) {
            nativeSendKey(event.getAction() == KeyEvent.ACTION_DOWN ? 0 : 1, scanCode);
            return true;
        }
        return true;
    }

    public boolean isAccessibilityInterceptEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ACCESSIBILITY_ENABLED, false);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode != 0) {
            nativeSendKey(1, scanCode);
            return true;
        }

        // fallback: when scancode is 0, map via KeyCodeMapper
        int evdev = KeyCodeMapper.getScanCode(keyCode);
        if (evdev != -1) {
            nativeSendKey(1, evdev);
            return true;
        }
        return true;
    }

    private static final int CLASSIFICATION_TWO_FINGER_SWIPE = 3;
    private static final int CLASSIFICATION_MULTI_FINGER_SWIPE = 4;
    private static final int CLASSIFICATION_PINCH = 5;

    private int savedBS = 0;

    private static final int[][] BUTTON_MAP = {
        {MotionEvent.BUTTON_PRIMARY,   0x110}, // BTN_LEFT
        {MotionEvent.BUTTON_SECONDARY, 0x111}, // BTN_RIGHT
        {MotionEvent.BUTTON_TERTIARY,  0x112}, // BTN_MIDDLE
        {MotionEvent.BUTTON_BACK,      0x113}, // BTN_SIDE
        {MotionEvent.BUTTON_FORWARD,   0x114}, // BTN_EXTRA
    };

    private boolean isMouseEvent(MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN)
            return false;
        if ((source & InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE)
            return false;
        int toolType = event.getToolType(event.getActionIndex());
        return toolType == MotionEvent.TOOL_TYPE_MOUSE
            || toolType == MotionEvent.TOOL_TYPE_FINGER;
    }

    private boolean handleMouseEvent(MotionEvent event) {
        float dx = 0f;
        float dy = 0f;
        
        // Масштабирование
        float scaleX = (customScreenWidth > 0 && viewWidth > 0) ? 
                   (float)customScreenWidth / viewWidth : 1.0f;
        float scaleY = (customScreenHeight > 0 && viewHeight > 0) ? 
                   (float)customScreenHeight / viewHeight : 1.0f;
        
        if (event.getHistorySize() > 0) {
            int last = event.getHistorySize() - 1;
            dx = (event.getX() - event.getHistoricalX(0, last))*scaleX;
            dy = (event.getY() - event.getHistoricalY(0, last))*scaleY;
        }
        nativeSendMouseMotion(event.getX() * scaleX, event.getY() * scaleY, dx, dy);

        int currentBS = event.getButtonState();
        for (int[] btn : BUTTON_MAP) {
            boolean wasDown = (savedBS & btn[0]) != 0;
            boolean isDown  = (currentBS & btn[0]) != 0;
            if (wasDown != isDown)
                nativeSendMouseButton(btn[1], isDown);
        }
        savedBS = currentBS;
        return true;
    }

    private boolean handleTouchpadScroll(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float scrollX = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE);
            float scrollY = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE);
            if (scrollY != 0)
                nativeSendMouseScroll(0, scrollY);
            if (scrollX != 0)
                nativeSendMouseScroll(1, -scrollX);
        }
        return true;
    }

    // 原有 handleTouchEvent 一字未改
    private boolean handleTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIdx = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIdx);
    
        // Масштабирование
        float scaleX = (customScreenWidth > 0 && viewWidth > 0) ? 
                       (float)customScreenWidth / viewWidth : 1.0f;
        float scaleY = (customScreenHeight > 0 && viewHeight > 0) ? 
                       (float)customScreenHeight / viewHeight : 1.0f;
    
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                nativeSendTouch(0, 
                    event.getX(pointerIdx) * scaleX, 
                    event.getY(pointerIdx) * scaleY, 
                    pointerId);
                nativeSendTouchFrame();
                return true;
            
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                nativeSendTouch(1, 
                    event.getX(pointerIdx) * scaleX, 
                    event.getY(pointerIdx) * scaleY, 
                    pointerId);
                nativeSendTouchFrame();
                return true;
            
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    nativeSendTouch(2, 
                        event.getX(i) * scaleX, 
                        event.getY(i) * scaleY, 
                        event.getPointerId(i));
                }
                nativeSendTouchFrame();
                return true;
            
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    nativeSendTouch(1, 
                        event.getX(i) * scaleX, 
                        event.getY(i) * scaleY, 
                        event.getPointerId(i));
                }
                nativeSendTouchFrame();
                return true;
        }
        return false;
    }

    // ==================== 新增：触摸板手势及辅助方法 ====================
    private boolean handleTouchpadGesture(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX();
                float y = event.getY();
                startX1 = lastX1 = x;
                startY1 = lastY1 = y;
                downTime1 = event.getEventTime();
                hasLongPressed = false;
                isLongPressPossible = true;
                isSingleTapCandidate = true;
                isTwoFingerTapCandidate = false;
                isDraggingActive = false;
                isMultiFinger = false;
                currentState = STATE_ONE_FINGER;
                resetSmoothing();
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                isMultiFinger = true;
                isSingleTapCandidate = false;
                isLongPressPossible = false;
                if (currentState == STATE_DRAGGING) {
                    nativeSendMouseButton(0x110, false);
                    isDraggingActive = false;
                }
                if (pointerCount == 2) {
                    currentState = STATE_TWO_FINGER;
                    isTwoFingerTapCandidate = true;
                    lastX1 = event.getX(0);
                    lastY1 = event.getY(0);
                    lastX2 = event.getX(1);
                    lastY2 = event.getY(1);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pointerCount == 1 && !isMultiFinger) {
                    float x = event.getX();
                    float y = event.getY();
                    float rawDx = x - lastX1;
                    float rawDy = y - lastY1;
                    float dist = (float) Math.hypot(x - startX1, y - startY1);

                    if (dist > touchSlop) {
                        isLongPressPossible = false;
                        isSingleTapCandidate = false;
                    }

                    if (isLongPressPossible && !hasLongPressed &&
                            (event.getEventTime() - downTime1) >= TOUCH_LONG_PRESS_TIMEOUT) {
                        hasLongPressed = true;
                        currentState = STATE_DRAGGING;
                        isDraggingActive = true;
                        nativeSendMouseButton(0x110, true);
                        mouseX = clamp(mouseX, 0, screenWidth);
                        mouseY = clamp(mouseY, 0, screenHeight);
                        nativeSendMouseMotion(mouseX, mouseY, 0f, 0f);
                        resetSmoothing();
                        break;
                    }

                    float[] smoothed = applySmoothing(rawDx, rawDy);
                    float smoothDx = smoothed[0];
                    float smoothDy = smoothed[1];

                    if (smoothDx != 0f || smoothDy != 0f) {
                        // 计算移动距离（平滑后的欧式距离）
                        float distance = (float) Math.hypot(smoothDx, smoothDy);

                        // 改进的加速度曲线：以 10px 为参考阈值，使小位移也能获得明显加速
                        float speedFactor = distance / 10.0f;
                        // 使用 sigmoid-like 曲线：scale = 1 + (strength - 1) * (speed / (1 + speed))
                        float dynamicScale = 1.0f + (mouseAccelStrength - 1.0f) * (speedFactor / (1.0f + speedFactor));
                        // 限制范围，防止失控（最大不超过 10 倍）
                        dynamicScale = Math.max(0.3f, Math.min(10.0f, dynamicScale));

                        float moveX = smoothDx * dynamicScale;
                        float moveY = smoothDy * dynamicScale;
                        mouseX = clamp(mouseX + moveX, 0, screenWidth);
                        mouseY = clamp(mouseY + moveY, 0, screenHeight);
                        nativeSendMouseMotion(mouseX, mouseY, 0f, 0f);
                    }

                    lastX1 = x;
                    lastY1 = y;

                } else if (pointerCount == 2) {
                    if (currentState == STATE_TWO_FINGER) {
                        float x1 = event.getX(0);
                        float y1 = event.getY(0);
                        float x2 = event.getX(1);
                        float y2 = event.getY(1);
                        float avgDx = ((x1 - lastX1) + (x2 - lastX2)) / 2;
                        float avgDy = ((y1 - lastY1) + (y2 - lastY2)) / 2;

                        if (Math.abs(avgDx) > 1 || Math.abs(avgDy) > 1) {
                            isTwoFingerTapCandidate = false;
                            if (Math.abs(avgDy) > Math.abs(avgDx) * 0.5) {
                                nativeSendMouseScroll(0, -avgDy * 0.5f);
                            }
                            if (Math.abs(avgDx) > Math.abs(avgDy) * 0.5) {
                                nativeSendMouseScroll(1, avgDx * 0.5f);
                            }
                            lastX1 = x1;
                            lastY1 = y1;
                            lastX2 = x2;
                            lastY2 = y2;
                        }
                    }
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int remaining = pointerCount - 1;
                if (remaining == 1) {
                    isMultiFinger = false;
                    isSingleTapCandidate = false;
                    isLongPressPossible = false;
                    int idx = (event.getActionIndex() == 0) ? 1 : 0;
                    lastX1 = event.getX(idx);
                    lastY1 = event.getY(idx);
                    startX1 = lastX1;
                    startY1 = lastY1;
                    downTime1 = event.getEventTime();
                    hasLongPressed = false;
                    currentState = STATE_ONE_FINGER;
                    resetSmoothing();
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                long duration = event.getEventTime() - downTime1;
                boolean isQuickTap = duration < 300;

                if (isDraggingActive) {
                    nativeSendMouseButton(0x110, false);
                    isDraggingActive = false;
                    resetTouchpadState();
                    resetSmoothing();
                    return true;
                }

                if (isTwoFingerTapCandidate && isQuickTap) {
                    nativeSendMouseButton(0x111, true);
                    nativeSendMouseButton(0x111, false);
                    resetTouchpadState();
                    resetSmoothing();
                    return true;
                }

                if (currentState == STATE_ONE_FINGER && isSingleTapCandidate && isQuickTap) {
                    long gap = event.getEventTime() - lastTapTime;
                    float dist = (float) Math.hypot(lastX1 - lastTapX, lastY1 - lastTapY);
                    if (gap < 300 && dist < touchSlop && !isDoubleTapPending) {
                        isDoubleTapPending = true;
                        nativeSendMouseButton(0x110, true);
                        nativeSendMouseButton(0x110, false);
                        nativeSendMouseButton(0x110, true);
                        nativeSendMouseButton(0x110, false);
                        isDoubleTapPending = false;
                        lastTapTime = 0;
                    } else {
                        nativeSendMouseButton(0x110, true);
                        nativeSendMouseButton(0x110, false);
                        lastTapTime = event.getEventTime();
                        lastTapX = lastX1;
                        lastTapY = lastY1;
                        isDoubleTapPending = false;
                    }
                    resetTouchpadState();
                    resetSmoothing();
                    return true;
                }
                resetTouchpadState();
                resetSmoothing();
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (isDraggingActive) {
                    nativeSendMouseButton(0x110, false);
                    isDraggingActive = false;
                }
                resetTouchpadState();
                resetSmoothing();
                break;
            }
        }
        return true;
    }

    private void resetTouchpadState() {
        currentState = STATE_IDLE;
        isSingleTapCandidate = false;
        isTwoFingerTapCandidate = false;
        isDoubleTapPending = false;
        hasLongPressed = false;
        isDraggingActive = false;
        isLongPressPossible = false;
        isMultiFinger = false;
    }

    private void resetSmoothing() {
        smoothedDx = 0f;
        smoothedDy = 0f;
        accumulatedX = 0f;
        accumulatedY = 0f;
        smoothInitialized = false;
    }

    private float[] applySmoothing(float rawDx, float rawDy) {
        float deadDx = Math.abs(rawDx) < DEAD_ZONE ? 0f : rawDx;
        float deadDy = Math.abs(rawDy) < DEAD_ZONE ? 0f : rawDy;

        if (deadDx == 0f && deadDy == 0f) {
            return new float[]{0f, 0f};
        }

        if (!smoothInitialized) {
            smoothedDx = deadDx;
            smoothedDy = deadDy;
            smoothInitialized = true;
        } else {
            smoothedDx = SMOOTHING_FACTOR * deadDx + (1 - SMOOTHING_FACTOR) * smoothedDx;
            smoothedDy = SMOOTHING_FACTOR * deadDy + (1 - SMOOTHING_FACTOR) * smoothedDy;
        }

        // 累积阈值大幅降低，让移动更加连续
        accumulatedX += smoothedDx;
        accumulatedY += smoothedDy;

        float outX = 0f;
        float outY = 0f;
        if (Math.abs(accumulatedX) >= ACCUMULATED_THRESHOLD) {
            outX = accumulatedX;
            accumulatedX = 0f;
        }
        if (Math.abs(accumulatedY) >= ACCUMULATED_THRESHOLD) {
            outY = accumulatedY;
            accumulatedY = 0f;
        }
        return new float[]{outX, outY};
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateScreenSize() {
        android.graphics.Point size = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
    }
}