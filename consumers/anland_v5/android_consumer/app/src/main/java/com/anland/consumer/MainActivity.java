package com.anland.consumer;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.content.SharedPreferences;
import android.graphics.Color;
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
    // Latency presets in ms; 0 = engine default. Shared with SettingsActivity.
    static final String KEY_SPEAKER_LATENCY_MS = "speaker_latency_ms";
    static final String KEY_MIC_LATENCY_MS = "mic_latency_ms";
    private static final int REQ_RECORD_AUDIO = 1001;
    private static final String DEFAULT_SOCKET_PATH = "/data/local/tmp/display_daemon.sock";
    private static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_key_intercept";
    private static final String KEY_EXTRA_KEYS_ENABLED = "extra_keys_bar";
    private static final String KEY_AUTO_SHOW_EXTRA_KEYS = "auto_show_extra_keys";
    private EditText hiddenInput;
    private InputMethodManager imm;
    private int mImeBottom = 0;   // last IME bottom inset
    private int mBarHeight = 0;   // extra-keys bar height in px
    private ExtraKeysBar extraKeysBar;

    public static MainActivity sInstance;

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
    public static final String KEY_MOUSE_SPEED = "mouse_speed";

    private boolean isTouchpadMode = true;      // true=相对移动(触摸板), false=绝对定位(触摸屏)
    private float mouseSpeed = 1.0f;            // 鼠标速度倍率

    // 触摸板手势状态机
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

    // 鼠标位置（用于相对模式）
    private float mouseX = 0;
    private float mouseY = 0;
    private int screenWidth = 1920;
    private int screenHeight = 1080;

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
    private native void nativeSetCustomResolution(int width, int height);

    // Called from native event thread to set clipboard text on Android
    public void nativeSetClipboardText(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            mLastSentClip = text;
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
            if (mClipListening) return;
            cm.addPrimaryClipChangedListener(clipListener);
            mClipListening = true;
        } else {
            if (!mClipListening) return;
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
        boolean useRoot = prefs.getBoolean(KEY_USE_ROOT, false);
        String helperPath = getApplicationInfo().nativeLibraryDir + "/libfdhelper.so";
        String bridgePath = getCacheDir().getAbsolutePath() + "/anland_fdbridge.sock";
        nativeConfigure(sock.trim(), useRoot, helperPath, bridgePath);
        int customW = prefs.getInt("custom_width", 0);
        int customH = prefs.getInt("custom_height", 0);
        customScreenWidth = customW;
        customScreenHeight = customH;
        nativeSetCustomResolution(customW, customH);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sInstance = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        // Take over inset handling
        getWindow().setDecorFitsSystemWindows(false);

        // ---- 新增：加载触摸板设置 ----
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isTouchpadMode = prefs.getBoolean(KEY_TOUCHPAD_MODE, true);
        mouseSpeed = prefs.getFloat(KEY_MOUSE_SPEED, 1.0f);
        mouseSpeed = Math.max(0.5f, Math.min(5.0f, mouseSpeed));

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        updateScreenSize();
        mouseX = screenWidth / 2f;
        mouseY = screenHeight / 2f;

        surfaceView = new SurfaceView(this);
        initHiddenInput();

        FrameLayout root = new FrameLayout(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(hiddenInput, new FrameLayout.LayoutParams(1, 1));

        // Bottom extra-keys bar
        float density = getResources().getDisplayMetrics().density;
        mBarHeight = Math.round(37.5f * density * ExtraKeysBar.rowCount());
        extraKeysBar = new ExtraKeysBar(this, new ExtraKeysBar.Sender() {
            @Override public void key(int action, int evdev) { nativeSendKey(action, evdev); }
            @Override public void text(String s) {
                if (!s.isEmpty()) nativeSendTextInput(s.getBytes(StandardCharsets.UTF_8));
            }
            @Override public void toggleKeyboard() { MainActivity.this.toggleKeyboard(); }
            @Override public void openSettings() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        extraKeysBar.setVisibility(View.GONE);
        root.addView(extraKeysBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, mBarHeight, Gravity.BOTTOM));

        setContentView(root);
        surfaceView.getHolder().addCallback(this);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (!insets.isVisible(WindowInsets.Type.ime()))
                releaseHiddenInput();
            applyImeInset(insets);
            return v.onApplyWindowInsets(insets);
        });

        setupFullscreen();
        setupCursorHiding();
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

        // Re-check accessibility service state on resume
        KeyInterceptor.recheck();

        // Sync extra-keys bar visibility
        setExtraKeysBarVisible(shouldShowBar(isImeVisible()));

        setupFullscreen();
        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null)
            dm.registerDisplayListener(displayListener, null);

        // ---- 重新读取触摸板设置（可能被SettingsActivity修改） ----
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isTouchpadMode = prefs.getBoolean(KEY_TOUCHPAD_MODE, true);
        mouseSpeed = prefs.getFloat(KEY_MOUSE_SPEED, 1.0f);
        mouseSpeed = Math.max(0.5f, Math.min(5.0f, mouseSpeed));

        if (surfaceReady) {
            nativeStop();
            applyConnectionConfig();
            nativeStart(surfaceView.getHolder().getSurface());
            pushRefreshRate();
            applyMicState();
            applyAudioLatency();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null)
            dm.unregisterDisplayListener(displayListener);
        nativeStop();
    }

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
        nativeStop();
        applyConnectionConfig();
        nativeStart(holder.getSurface());
        pushRefreshRate();
        applyMicState();
        applyAudioLatency();
        updateScreenSize();
        mouseX = clamp(mouseX, 0, screenWidth);
        mouseY = clamp(mouseY, 0, screenHeight);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        nativeStop();
    }

    private void updateScreenSize() {
        android.graphics.Point size = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------- IME / Keyboard related ----------
    private void initHiddenInput() {
        imm = getSystemService(InputMethodManager.class);

        hiddenInput = new EditText(this) {
            @Override
            public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                super.onCreateInputConnection(outAttrs);
                return new ForwardingInputConnection(this);
            }
        };
        hiddenInput.setBackgroundColor(Color.TRANSPARENT);
        hiddenInput.setCursorVisible(false);
        hiddenInput.setAlpha(0f);
        hiddenInput.setEnabled(false);
        hiddenInput.setFocusable(false);
        hiddenInput.setFocusableInTouchMode(false);
        hiddenInput.setClickable(false);
        hiddenInput.setLongClickable(false);
        hiddenInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        hiddenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL);
    }

    private void sendText(String text) {
        if (text.isEmpty()) return;
        nativeSendTextInput(text.getBytes(StandardCharsets.UTF_8));
    }

    private void tapKey(int evdevCode) {
        nativeSendKey(0, evdevCode);
        nativeSendKey(1, evdevCode);
    }

    private final KeyCharacterMap mVirtualKcm =
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

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

    private final class ForwardingInputConnection extends BaseInputConnection {
        private final StringBuilder composing = new StringBuilder();

        ForwardingInputConnection(View target) {
            super(target, false);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            final String s = text == null ? "" : text.toString();
            if (maybeSendModifierCombo(s)) {
                composing.setLength(0);
                return true;
            }
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
            composing.setLength(0);
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            for (int i = 0; i < beforeLength; i++) tapKey(EVDEV_BACKSPACE);
            for (int i = 0; i < afterLength; i++) tapKey(EVDEV_DELETE);
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            final int evdev = toEvdevKey(event.getKeyCode());
            if (evdev == 0) return super.sendKeyEvent(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                nativeSendKey(0, evdev);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                nativeSendKey(1, evdev);
            }
            return true;
        }

        private void replaceComposing(String next) {
            final String prev = composing.toString();
            int prefix = 0;
            final int min = Math.min(prev.length(), next.length());
            while (prefix < min && prev.charAt(prefix) == next.charAt(prefix)) {
                prefix++;
            }
            if (prefix > 0 && Character.isHighSurrogate(prev.charAt(prefix - 1))) {
                prefix--;
            }
            final int erase = prev.codePointCount(prefix, prev.length());
            for (int i = 0; i < erase; i++) tapKey(EVDEV_BACKSPACE);
            if (prefix < next.length()) {
                sendText(next.substring(prefix));
            }
            composing.setLength(0);
            composing.append(next);
        }

        private void eraseComposing() {
            final int erase = composing.codePointCount(0, composing.length());
            for (int i = 0; i < erase; i++) tapKey(EVDEV_BACKSPACE);
            composing.setLength(0);
        }
    }

    private void applyImeInset(WindowInsets insets) {
        int newImeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
        boolean imeVisible = newImeBottom > 0;
        boolean wasImeVisible = mImeBottom > 0;

        mImeBottom = newImeBottom;

        if (imeVisible != wasImeVisible)
            setExtraKeysBarVisible(shouldShowBar(imeVisible));

        relayout();
    }

    private boolean shouldShowBar(boolean imeVisible) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean autoShow = prefs.getBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, true);
        if (autoShow)
            return imeVisible;
        return prefs.getBoolean(KEY_EXTRA_KEYS_ENABLED, false);
    }

    private void relayout() {
        boolean barVisible = extraKeysBar != null && extraKeysBar.getVisibility() == View.VISIBLE;
        int barH = barVisible ? mBarHeight : 0;
        int target = mImeBottom + barH;

        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.bottomMargin != target) {
            lp.bottomMargin = target;
            surfaceView.setLayoutParams(lp);
        }
        if (extraKeysBar != null)
            extraKeysBar.setTranslationY(-mImeBottom);
    }

    private void setExtraKeysBarVisible(boolean visible) {
        if (extraKeysBar == null) return;
        boolean cur = extraKeysBar.getVisibility() == View.VISIBLE;
        if (cur == visible) return;
        extraKeysBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) extraKeysBar.reset();
        relayout();
    }

    private boolean isImeVisible() {
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }

    private void releaseHiddenInput() {
        if (!hiddenInput.isEnabled()) return;
        hiddenInput.clearFocus();
        hiddenInput.setFocusable(false);
        hiddenInput.setEnabled(false);
    }

    private void toggleKeyboard() {
        if (imm == null) imm = getSystemService(InputMethodManager.class);
        if (imm == null) return;
        if (isImeVisible()) {
            imm.hideSoftInputFromWindow(hiddenInput.getWindowToken(), 0);
            releaseHiddenInput();
        } else {
            hiddenInput.setEnabled(true);
            hiddenInput.setFocusable(true);
            hiddenInput.setFocusableInTouchMode(true);
            hiddenInput.requestFocus();
            imm.showSoftInput(hiddenInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    // ==================== 触摸事件（分支） ====================
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isTouchpadMode) {
            return handleTouchpadGesture(event);
        } else {
            // 绝对定位模式：保留原绝对触摸逻辑
            return handleAbsoluteTouch(event);
        }
    }

    // ---- 原有绝对触摸逻辑（原 handleTouchEvent，重命名） ----
    private boolean handleAbsoluteTouch(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIdx = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIdx);

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

    // ---- 新增：触摸板模式（相对移动） ----
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
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                isMultiFinger = true;
                isSingleTapCandidate = false;
                isLongPressPossible = false;
                if (currentState == STATE_DRAGGING) {
                    nativeSendMouseButton(0x110, false); // BTN_LEFT up
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
                    float dx = x - lastX1;
                    float dy = y - lastY1;
                    float dist = (float) Math.hypot(x - startX1, y - startY1);

                    if (dist > touchSlop) {
                        isLongPressPossible = false;
                        isSingleTapCandidate = false;
                    }

                    // 长按拖动
                    if (isLongPressPossible && !hasLongPressed &&
                            (event.getEventTime() - downTime1) >= TOUCH_LONG_PRESS_TIMEOUT) {
                        hasLongPressed = true;
                        currentState = STATE_DRAGGING;
                        isDraggingActive = true;
                        nativeSendMouseButton(0x110, true); // BTN_LEFT down
                        // 确保鼠标位置在屏幕内
                        mouseX = clamp(mouseX, 0, screenWidth);
                        mouseY = clamp(mouseY, 0, screenHeight);
                        nativeSendMouseMotion(mouseX, mouseY, 0f, 0f);
                        break;
                    }

                    if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
                        // 应用鼠标速度
                        float effectiveSpeed = mouseSpeed;
                        mouseX = clamp(mouseX + dx * effectiveSpeed, 0, screenWidth);
                        mouseY = clamp(mouseY + dy * effectiveSpeed, 0, screenHeight);
                        nativeSendMouseMotion(mouseX, mouseY, 0f, 0f);
                        lastX1 = x;
                        lastY1 = y;
                    }
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
                            // 垂直滚动
                            if (Math.abs(avgDy) > Math.abs(avgDx) * 0.5) {
                                nativeSendMouseScroll(0, -avgDy * 0.5f);
                            }
                            // 水平滚动
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
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                long duration = event.getEventTime() - downTime1;
                boolean isQuickTap = duration < 300;

                if (isDraggingActive) {
                    nativeSendMouseButton(0x110, false); // BTN_LEFT up
                    isDraggingActive = false;
                    resetTouchpadState();
                    return true;
                }

                // 双指轻触 = 右键
                if (isTwoFingerTapCandidate && isQuickTap) {
                    nativeSendMouseButton(0x111, true); // BTN_RIGHT down
                    nativeSendMouseButton(0x111, false);
                    resetTouchpadState();
                    return true;
                }

                // 单指轻触 = 左键（双击检测）
                if (currentState == STATE_ONE_FINGER && isSingleTapCandidate && isQuickTap) {
                    long gap = event.getEventTime() - lastTapTime;
                    float dist = (float) Math.hypot(lastX1 - lastTapX, lastY1 - lastTapY);
                    if (gap < 300 && dist < touchSlop && !isDoubleTapPending) {
                        // 双击
                        isDoubleTapPending = true;
                        nativeSendMouseButton(0x110, true);
                        nativeSendMouseButton(0x110, false);
                        nativeSendMouseButton(0x110, true);
                        nativeSendMouseButton(0x110, false);
                        isDoubleTapPending = false;
                        lastTapTime = 0;
                    } else {
                        // 单击
                        nativeSendMouseButton(0x110, true);
                        nativeSendMouseButton(0x110, false);
                        lastTapTime = event.getEventTime();
                        lastTapX = lastX1;
                        lastTapY = lastY1;
                        isDoubleTapPending = false;
                    }
                    resetTouchpadState();
                    return true;
                }
                resetTouchpadState();
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (isDraggingActive) {
                    nativeSendMouseButton(0x110, false);
                    isDraggingActive = false;
                }
                resetTouchpadState();
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

    // ==================== 按键事件（完全保留原始逻辑，无音量特殊功能） ====================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() > 0)
            return true;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int boundKeycode = prefs.getInt(KEY_BOUND_KEYCODE, -1);
        if (boundKeycode != -1 && keyCode == boundKeycode) {
            toggleKeyboard();
            return true;
        }

        int scanCode = event.getScanCode();
        if (scanCode != 0) {
            nativeSendKey(0, scanCode);
            return true;
        }

        int evdev = KeyCodeMapper.getScanCode(keyCode);
        if (evdev != -1) {
            nativeSendKey(0, evdev);
            return true;
        }
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode != 0) {
            nativeSendKey(1, scanCode);
            return true;
        }

        int evdev = KeyCodeMapper.getScanCode(keyCode);
        if (evdev != -1) {
            nativeSendKey(1, evdev);
            return true;
        }
        return true;
    }

    // Called from KeyInterceptor
    public boolean handleAccessibilityKey(KeyEvent event) {
        if (event.getRepeatCount() > 0)
            return true;

        int scanCode = event.getScanCode();
        if (scanCode != 0 && event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) {
            nativeSendKey(event.getAction() == KeyEvent.ACTION_DOWN ? 0 : 1, scanCode);
            return true;
        }

        int evdev = KeyCodeMapper.getScanCode(event.getKeyCode());
        if (evdev != -1) {
            nativeSendKey(event.getAction() == KeyEvent.ACTION_DOWN ? 0 : 1, evdev);
            return true;
        }

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

    // ==================== 鼠标/外设事件（原有逻辑） ====================
    private static final int CLASSIFICATION_TWO_FINGER_SWIPE = 3;
    private static final int CLASSIFICATION_MULTI_FINGER_SWIPE = 4;
    private static final int CLASSIFICATION_PINCH = 5;

    private int savedBS = 0;
    private static final int[][] BUTTON_MAP = {
            {MotionEvent.BUTTON_PRIMARY,   0x110},
            {MotionEvent.BUTTON_SECONDARY, 0x111},
            {MotionEvent.BUTTON_TERTIARY,  0x112},
            {MotionEvent.BUTTON_BACK,      0x113},
            {MotionEvent.BUTTON_FORWARD,   0x114},
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

        float scaleX = (customScreenWidth > 0 && viewWidth > 0) ?
                (float)customScreenWidth / viewWidth : 1.0f;
        float scaleY = (customScreenHeight > 0 && viewHeight > 0) ?
                (float)customScreenHeight / viewHeight : 1.0f;

        if (event.getHistorySize() > 0) {
            int last = event.getHistorySize() - 1;
            dx = (event.getX() - event.getHistoricalX(0, last)) * scaleX;
            dy = (event.getY() - event.getHistoricalY(0, last)) * scaleY;
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

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isMouseEvent(event)) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_MOVE) {
                float scaleX = (customScreenWidth > 0 && viewWidth > 0) ?
                        (float)customScreenWidth / viewWidth : 1.0f;
                float scaleY = (customScreenHeight > 0 && viewHeight > 0) ?
                        (float)customScreenHeight / viewHeight : 1.0f;
                nativeSendMouseMotion(event.getX() * scaleX, event.getY() * scaleY,
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
}