package com.anland.consumer;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.util.DisplayMetrics;   // ADDED

import java.nio.charset.StandardCharsets;


public class MainActivity extends Activity
        implements SurfaceHolder.Callback, SystemIME.Host {
    private static final String TAG = "Anland";

    private SurfaceView surfaceView;
    private boolean surfaceReady = false;
    // System-clipboard bridge; also the target for the native clipboard callbacks.
    private Clipboard clipboard;
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
    // Multi-instance launch parameters. A secondary window is started with these
    // Intent extras (see SecondaryActivity / SettingsActivity); the launcher icon
    // starts MainActivity with none, i.e. the default socket and window name "anland".
    static final String EXTRA_SOCKET_PATH = "socket_path";
    static final String EXTRA_WINDOW_NAME = "window_name";
    // This window's own native transport instance (its own consumer_state handle).
    private Native mNative;
    // Socket path from the launch Intent; overrides the saved pref when non-null.
    private String mSocketOverride = null;
    // Title shown in recents / freeform (setTaskDescription); default "anland".
    private String mWindowName = "anland";
    // Live windows keyed by their resolved socket path, so a launch that targets a
    // socket already on screen can focus that window instead of opening a duplicate.
    // Only touched on the main thread (onCreate / onResume / onDestroy).
    private static final java.util.Map<String, MainActivity> sWindowsBySocket =
            new java.util.HashMap<>();
    // The socket path this window is currently registered under in sWindowsBySocket.
    private String mRegisteredSocket = null;
    // Set when onCreate found the target socket missing and bounced to Settings
    // (no pipeline was ever initialized). Makes onPause/onResume no-op-and-exit.
    private boolean mForceSettings = false;
    private static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_key_intercept";
    private static final String KEY_EXTRA_KEYS_MODE = "extra_keys_mode";
    private static final String KEY_BACK_OPENS_EXTRA_KEYS = "back_opens_extra_keys";
    private static final String KEY_EXTRA_KEYS_LAYOUT = "extra_keys_layout";
    // When on, the IME and extra-keys bar float over the display instead of
    // shrinking it: the bar rides up with the keyboard but the surface keeps
    // its full size. See relayout() and buildExtraKeysBar().
    private static final String KEY_KEYBOARD_FLOATING = "keyboard_floating";
    private boolean mKeyboardFloating = false;
    // Persistent "tap to open Settings" notification, toggleable in Settings > General.
    private static final String KEY_NOTIFICATION_ENABLED = "settings_notification";
    // System soft-keyboard bridge: hidden input, text forwarding and toggle.
    private SystemIME systemIme;
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

    // ==================== 触摸板相关设置 ====================
    public static final String KEY_TOUCHPAD_MODE = "touchpad_mode";
    public static final String KEY_MOUSE_ACCEL = "mouse_speed"; // 名称仍为 speed，实际控制加速度强度

    // Routing gate: when on, non-mouse touches go to the virtual touchpad.
    private boolean isTouchpadMode = true;
    // Finger-gesture touchpad (relative motion, taps, drag, two-finger scroll).
    private VirtualTouchpad virtualTouchpad;

    static {
        // Loads the single shared .so backing MainActivity, Native and
        // CameraServices; the last two only declare their natives.
        System.loadLibrary("anland_consumer");
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

    // Called from native on_fallback (display lib dropped the connection). Runs on a
    // native worker thread, so hop to the UI thread before touching the toast/finish.
    // If the daemon socket is gone the daemon really went down, so close this window.
    public void onFallback(){
        runOnUiThread(() -> {
            if (!isSocketFile(resolveSocketPath())) {
                //exit
                android.widget.Toast.makeText(this, "Deamon Down",
                        android.widget.Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!isSocketFile(resolveSocketPath())) {
            //exit
            android.widget.Toast.makeText(this, "Deamon Down",
                    android.widget.Toast.LENGTH_SHORT).show();
            finish();
        }
        if (hasFocus) {
            // Become the accessibility-key target and the focused instance, so real
            // camera frames route to this window (others get blank frames).
            sInstance = this;
            if (mNative != null) mNative.setFocused(true);
        }
        if (hasFocus && clipboard != null) {
            clipboard.pushClipboard();
        }
    }

    private void pushRefreshRate() {
        Display d = getDisplay();
        if (d != null)
            mNative.setRefreshRate(d.getRefreshRate());
    }

    // Push the current connection settings (socket path / root mode) to native
    // before (re)connecting. The root helper is the executable bundled in the
    // app's native lib dir; the bridge is a unix socket in our cache dir that
    // the helper, launched via su, uses to hand back the daemon fd.
    private void applyConnectionConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String sock = resolveSocketPath();
        boolean useRoot = prefs.getBoolean(KEY_USE_ROOT, true);
        String helperPath = getApplicationInfo().nativeLibraryDir + "/libfdhelper.so";
        String bridgePath = getCacheDir().getAbsolutePath() + "/anland_fdbridge.sock";
        mNative.configure(sock, useRoot, helperPath, bridgePath);
        int customW = prefs.getInt("custom_width", 0);
        int customH = prefs.getInt("custom_height", 0);
        customScreenWidth = prefs.getInt("custom_width", 0);
        customScreenHeight = prefs.getInt("custom_height", 0);
        mNative.setCustomResolution(customW, customH);
    }

    // The daemon socket this window targets: the launch-Intent override if any,
    // else the saved pref, else the built-in default. Never null/blank. This is
    // both the native connection target and this window's dedup key.
    private String resolveSocketPath() {
        String sock = mSocketOverride;
        if (sock == null || sock.trim().isEmpty())
            sock = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_SOCKET_PATH, DEFAULT_SOCKET_PATH);
        if (sock == null || sock.trim().isEmpty())
            sock = DEFAULT_SOCKET_PATH;
        return sock.trim();
    }

    // Start (or restart) this window's native pipeline, but only if the daemon
    // socket is still a live socket. The daemon can go down after launch, so
    // re-check on every (re)connect; if it is gone, report it and exit the window.
    private void startNative(android.view.Surface surface) {
        if (!isSocketFile(resolveSocketPath())) {
            android.widget.Toast.makeText(this, "Deamon Down",
                    android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        mNative.start(surface, clipboard, this);
    }

    // True only when `path` exists and is a unix-domain socket. In root mode the
    // daemon socket usually lives in a root-only location (e.g. /data/local/tmp),
    // which this untrusted_app process cannot stat() directly -- a direct stat
    // would EACCES and wrongly report "no socket". So when root mode is on we run
    // the bundled helper as root (`su -c "<helper> <path> test"`) and read its
    // exit code instead; otherwise we stat() locally.
    private boolean isSocketFile(String path) {
        boolean useRoot = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_USE_ROOT, true);
        return useRoot ? isSocketFileRoot(path) : isSocketFileLocal(path);
    }

    // stat(2) both resolves existence and reports the file type, so a stale
    // regular file / dir (or an unreadable / missing path -> ErrnoException)
    // counts as "no socket".
    private static boolean isSocketFileLocal(String path) {
        try {
            android.system.StructStat st = android.system.Os.stat(path);
            return android.system.OsConstants.S_ISSOCK(st.st_mode);
        } catch (android.system.ErrnoException e) {
            return false;
        }
    }

    // Probe the socket from root context via the bundled helper's "test" mode.
    // Exit 0 means the path exists and is a unix socket; anything else (including
    // su being unavailable / denied, which throws) counts as "no socket".
    private boolean isSocketFileRoot(String path) {
        String helperPath = getApplicationInfo().nativeLibraryDir + "/libfdhelper.so";
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", helperPath + " " + path + " test")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            Log.w(TAG, "root socket probe failed: " + e);
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    // (Re)register this window under its current socket in sWindowsBySocket. A
    // window with no Intent override resolves its socket from the saved pref, which
    // the user can change in Settings, so re-key whenever it may have moved.
    private void registerWindow() {
        String sock = resolveSocketPath();
        if (sock.equals(mRegisteredSocket)) return;
        if (mRegisteredSocket != null)
            sWindowsBySocket.remove(mRegisteredSocket, this);
        sWindowsBySocket.put(sock, this);
        mRegisteredSocket = sock;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Apply the launch parameters: socket path (overrides the saved pref) and
        // window name (task title). Read them before anything else so the dedup
        // check below sees this window's target socket.
        Intent launch = getIntent();
        if (launch != null) {
            String sock = launch.getStringExtra(EXTRA_SOCKET_PATH);
            if (sock != null && !sock.trim().isEmpty())
                mSocketOverride = sock.trim();
            String name = launch.getStringExtra(EXTRA_WINDOW_NAME);
            if (name != null && !name.trim().isEmpty())
                mWindowName = name.trim();
        }

        // Skip opening a duplicate: if another live window already targets this
        // socket, bring it to the front and drop this (freshly spawned) task.
        MainActivity existing = sWindowsBySocket.get(resolveSocketPath());
        if (existing != null && existing != this && !existing.isFinishing()) {
            ActivityManager am = getSystemService(ActivityManager.class);
            if (am != null) am.moveTaskToFront(existing.getTaskId(), 0);
            finishAndRemoveTask();
            return;
        }

        // The target must exist AND be a unix-domain socket before we bring up any
        // pipeline. If it is not: a parameter launch has nowhere to fall back to
        // (toast and quit); a plain launcher start bounces to Settings so the user
        // can fix the path.
        if (!isSocketFile(resolveSocketPath())) {
            if (mSocketOverride != null) {
                android.widget.Toast.makeText(this, "Socket Not Found",
                        android.widget.Toast.LENGTH_SHORT).show();
                finishAndRemoveTask();
                return;
            }
            mForceSettings = true;
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        sInstance = this;

        // Each window owns its own native pipeline.
        mNative = new Native();
        setTaskDescription(new ActivityManager.TaskDescription(mWindowName));

        clipboard = new Clipboard(this, mNative);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        // Take over inset handling: the IME insets are dispatched to our
        // OnApplyWindowInsetsListener (so we can resize the surface) instead of
        // the system auto-panning the fullscreen window.
        getWindow().setDecorFitsSystemWindows(false);

        surfaceView = new SurfaceView(this);
        systemIme = new SystemIME(this, this, mNative);

        FrameLayout root = new FrameLayout(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        // 1x1 so the IME target never overlaps the surface and steals touches.
        root.addView(systemIme.getInputView(), new FrameLayout.LayoutParams(1, 1));

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
                mNative.sendKey(0, scanCode);
            }
            @Override
            public void onKeyUp(int scanCode) {
                mNative.sendKey(1, scanCode);
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
                systemIme.releaseHiddenInput();
            applyImeInset(insets);
            return v.onApplyWindowInsets(insets);
        });

        setupFullscreen();
        setupCursorHiding();

        // ===== 加载触摸板设置 =====
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isTouchpadMode = prefs.getBoolean(KEY_TOUCHPAD_MODE, false);
        virtualTouchpad = new VirtualTouchpad(this, mNative);
        virtualTouchpad.setAccelStrength(prefs.getFloat(KEY_MOUSE_ACCEL, 1.0f));

        registerWindow();
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

        // Bounced to Settings from onCreate (socket missing): nothing was set up, so
        // just exit this window instead of running the connect logic.
        if (mForceSettings) {
            finish();
            return;
        }

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
        setExtraKeysBarVisible(shouldShowBar(systemIme.isImeVisible()));

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
            mNative.stop();
            applyConnectionConfig();
            startNative(surfaceView.getHolder().getSurface());
            pushRefreshRate();
            applyMicState();
            applyAudioLatency();
        }

        // ===== 重新读取触摸板设置 =====
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isTouchpadMode = prefs.getBoolean(KEY_TOUCHPAD_MODE, false);
        virtualTouchpad.setAccelStrength(prefs.getFloat(KEY_MOUSE_ACCEL, 1.0f));

        // The socket pref may have been edited in Settings; keep our dedup key current.
        registerWindow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Socket-missing bounce: no pipeline exists, so skip teardown (mNative is
        // null) and don't let the jump to Settings trigger any of it.
        if (mForceSettings) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null)
            dm.unregisterDisplayListener(displayListener);
        mNative.stop();
    }

    @Override
    protected void onDestroy() {
        if (mRegisteredSocket != null) {
            sWindowsBySocket.remove(mRegisteredSocket, this);
            mRegisteredSocket = null;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        // Release only THIS window's native pipeline (its consumer_state, audio bridge
        // and camera client). The camera service itself is a process-global shared by
        // every window, so it is intentionally not torn down here -- destroying it
        // would cut the camera for the other open windows.
        if (mNative != null) {
            mNative.destroy();
            mNative = null;
        }
        cameraInited = false;
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
        mNative.setAudioLatency(speakerMs, micMs);
    }

    private void applyMicState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean want = prefs.getBoolean(KEY_MIC_ENABLED, false);
        if (!want) {
            mNative.setMicEnabled(false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            mNative.setMicEnabled(true);
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
            mNative.setMicEnabled(granted);
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
        mNative.stop();
        applyConnectionConfig();
        startNative(holder.getSurface());
        pushRefreshRate();
        applyMicState();
        applyAudioLatency();

        // ===== 更新屏幕尺寸并重置平滑状态 =====
        virtualTouchpad.onSurfaceChanged();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        mNative.stop();
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

    // Desired extra-keys bar visibility for the current keyboard state. The
    // single three-way preference replaces the old two-switch pair:
    //   "always"       – bar always visible
    //   "never"        – bar always hidden
    //   "with_keyboard" – bar tracks the soft keyboard (default)
    private boolean shouldShowBar(boolean imeVisible) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String mode = prefs.getString(KEY_EXTRA_KEYS_MODE, "with_keyboard");
        switch (mode) {
            case "always":   return true;
            case "never":    return false;
            default:         return imeVisible;
        }
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
            @Override public void key(int action, int evdev) { mNative.sendKey(action, evdev); }
            @Override public void text(String s) {
                if (!s.isEmpty()) mNative.sendTextInput(s.getBytes(StandardCharsets.UTF_8));
            }
            // Tapping the ⌨ key keeps the original behaviour: toggle the system IME.
            @Override public void toggleKeyboard() { systemIme.toggleSystemKeyboard(); }
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
        setExtraKeysBarVisible(shouldShowBar(systemIme.isImeVisible()));
        relayout();
    }

    // Toggle the extra-keys bar on its own (e.g. from the Back key), independent of
    // the soft keyboard. Showing it just compresses the display area above the bar.
    private void toggleExtraKeysBar() {
        boolean visible = extraKeysBar != null
            && extraKeysBar.getVisibility() == View.VISIBLE;
        setExtraKeysBarVisible(!visible);
    }

    // ---- SystemIME.Host ----

    @Override
    public ExtraKeysBar getExtraKeysBar() {
        return extraKeysBar;
    }

    // The IME was shown/hidden via SystemIME's toggle. In freeform mode the inset
    // callback may not fire, so sync the extra-keys bar explicitly here in all modes.
    @Override
    public void onImeVisibilityChanged(boolean visible) {
        setExtraKeysBarVisible(shouldShowBar(visible));
    }

    // ================================================================
    // 原有 onTouchEvent 仅在最前面插入了一个分支判断，其余原封不动
    // ================================================================
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // ===== 触摸板模式优先处理（仅针对非鼠标触摸事件） =====
        if (isTouchpadMode && !isMouseEvent(event)) {
            return virtualTouchpad.onTouch(event);
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
        
                mNative.sendMouseMotion(event.getX()*scaleX, event.getY()*scaleY,
                                      event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                                      event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y));
                return true;
            }
            if (action == MotionEvent.ACTION_SCROLL) {
                float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                if (vScroll != 0)
                    mNative.sendMouseScroll(0, -vScroll * 10);
                if (hScroll != 0)
                    mNative.sendMouseScroll(1, hScroll * 10);
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
            systemIme.toggleSystemKeyboard();   // Keep original bound key behavior (system IME)
            return true;
        }

        // Back key toggles the extra-keys bar (without opening the soft keyboard)
        // when enabled in settings. Leaves the default swallow behaviour otherwise.
        if (keyCode == KeyEvent.KEYCODE_BACK
                && prefs.getBoolean(KEY_BACK_OPENS_EXTRA_KEYS, true)) {
            toggleExtraKeysBar();
            return true;
        }

        forwardKeyToLinux(event);
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

        return forwardKeyToLinux(event);
    }

    private boolean forwardKeyToLinux(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction() == KeyEvent.ACTION_DOWN ? 0 : 1;
        int evdev = -1;

        // Reserved Android keys may carry vendor scan codes that Linux does not
        // recognize, so prefer their explicit evdev mapping.
        if (shouldPreferMappedKey(keyCode))
            evdev = KeyCodeMapper.getScanCode(keyCode);

        if (evdev == -1 && event.getScanCode() != 0)
            evdev = event.getScanCode();

        if (evdev == -1)
            evdev = KeyCodeMapper.getScanCode(keyCode);

        if (evdev == -1)
            return false;

        mNative.sendKey(action, evdev);
        return true;
    }

    private static boolean shouldPreferMappedKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_META_LEFT
                || keyCode == KeyEvent.KEYCODE_META_RIGHT
                || keyCode == KeyEvent.KEYCODE_SEARCH
                || keyCode == KeyEvent.KEYCODE_ASSIST
                || (keyCode >= KeyEvent.KEYCODE_F13 && keyCode <= KeyEvent.KEYCODE_F24);
    }

    public boolean isAccessibilityInterceptEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ACCESSIBILITY_ENABLED, false);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        forwardKeyToLinux(event);
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
        mNative.sendMouseMotion(event.getX() * scaleX, event.getY() * scaleY, dx, dy);

        int currentBS = event.getButtonState();
        for (int[] btn : BUTTON_MAP) {
            boolean wasDown = (savedBS & btn[0]) != 0;
            boolean isDown  = (currentBS & btn[0]) != 0;
            if (wasDown != isDown)
                mNative.sendMouseButton(btn[1], isDown);
        }
        savedBS = currentBS;
        return true;
    }

    private boolean handleTouchpadScroll(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float scrollX = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE);
            float scrollY = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE);
            if (scrollY != 0)
                mNative.sendMouseScroll(0, scrollY);
            if (scrollX != 0)
                mNative.sendMouseScroll(1, -scrollX);
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
                mNative.sendTouch(0, 
                    event.getX(pointerIdx) * scaleX, 
                    event.getY(pointerIdx) * scaleY, 
                    pointerId);
                mNative.sendTouchFrame();
                return true;
            
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                mNative.sendTouch(1, 
                    event.getX(pointerIdx) * scaleX, 
                    event.getY(pointerIdx) * scaleY, 
                    pointerId);
                mNative.sendTouchFrame();
                return true;
            
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    mNative.sendTouch(2, 
                        event.getX(i) * scaleX, 
                        event.getY(i) * scaleY, 
                        event.getPointerId(i));
                }
                mNative.sendTouchFrame();
                return true;
            
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    mNative.sendTouch(1, 
                        event.getX(i) * scaleX, 
                        event.getY(i) * scaleY, 
                        event.getPointerId(i));
                }
                mNative.sendTouchFrame();
                return true;
        }
        return false;
    }

}
