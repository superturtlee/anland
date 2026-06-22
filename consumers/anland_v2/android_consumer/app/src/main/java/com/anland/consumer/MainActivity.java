package com.anland.consumer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "Anland";

    // ========== 持久化存储 ==========
    private static final String PREFS_NAME = "anland_prefs";
    private static final String KEY_SENSITIVITY = "sensitivity";
    private SharedPreferences prefs;

    private SurfaceView surfaceView;
    private boolean surfaceReady = false;
    private VirtualKeyboardView keyboardView;
    private FrameLayout rootLayout;

    // ========== 功能开关 ==========
    private boolean isTouchpadMode = true;
    private boolean isPortrait = true;
    private float sensitivity = 1.0f;

    // 鼠标绝对位置（像素）
    private float mouseX = 0;
    private float mouseY = 0;
    private int screenWidth = 1920;
    private int screenHeight = 1080;

    // ---------- Surface 初始化防抖 ----------
    private final Handler surfaceInitHandler = new Handler(Looper.getMainLooper());
    private Runnable surfaceInitRunnable = null;
    private static final long SURFACE_INIT_DELAY = 120; // 毫秒，足够 Surface 稳定

    // ---------- 音量键持续调节状态 ----------
    private long volumeUpLastAdjustTime = 0;
    private boolean volumeUpHasAdjusted = false;
    private long volumeDownLastAdjustTime = 0;
    private boolean volumeDownHasAdjusted = false;
    private static final long LONG_PRESS_THRESHOLD = 500;

    // ---------- 触摸板手势状态机 ----------
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
    private boolean isThreeFingerTapCandidate = false;
    private boolean isDraggingActive = false;

    private long lastTapTime = 0;
    private float lastTapX, lastTapY;
    private boolean isDoubleTapPending = false;

    private static final long TOUCH_LONG_PRESS_TIMEOUT = 500;
    private boolean hasLongPressed = false;
    private boolean isLongPressPossible = false;
    private boolean isMultiFinger = false;

    // ---------- 加速度参数 ----------
    private static final float BASE_SCALE = 2.0f;
    private static final float SCALE_STEP = 0.12f;
    private static final float MAX_SCALE = 6.0f;

    // 鼠标按钮常量
    private static final int BTN_LEFT = 0x110;
    private static final int BTN_RIGHT = 0x111;
    private static final int BTN_MIDDLE = 0x112;

    // ---------- 刷新率监听 ----------
    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}
                @Override
                public void onDisplayRemoved(int displayId) {}
                @Override
                public void onDisplayChanged(int displayId) {
                    Display d = getDisplay();
                    if (d != null && d.getDisplayId() == displayId)
                        pushRefreshRate();
                }
            };

    // ---------- Native 方法 ----------
    static {
        System.loadLibrary("anland_consumer");
    }

    private native void nativeStart(Surface surface);
    private native void nativeStop();
    private native void nativeSendTouch(int action, float x, float y, int pointerId);
    private native void nativeSendTouchFrame();
    private native void nativeSendKey(int action, int keycode);
    private native void nativeSendMouseMotion(float x, float y, float dx, float dy);
    private native void nativeSendMouseButton(int button, boolean pressed);
    private native void nativeSendMouseScroll(int axis, float value);
    private native void nativeSetRefreshRate(float hz);

    // ---------- 生命周期 ----------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.0f);
        sensitivity = Math.max(0.5f, Math.min(5.0f, sensitivity));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        updateScreenSize();
        isPortrait = (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT);

        rootLayout = new FrameLayout(this);
        rootLayout.setClipChildren(false);
        rootLayout.setClipToPadding(false);
        rootLayout.setFitsSystemWindows(false);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setClickable(false);
        surfaceView.setFocusable(false);
        rootLayout.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        keyboardView = new VirtualKeyboardView(this);
        keyboardView.setVisibility(View.GONE);
        keyboardView.setOnKeyEventListener(new VirtualKeyboardView.OnKeyEventListener() {
            @Override
            public void onKeyDown(int scanCode) {
                nativeSendKey(0, scanCode);
            }
            @Override
            public void onKeyUp(int scanCode) {
                nativeSendKey(1, scanCode);
            }
        });
        rootLayout.addView(keyboardView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(rootLayout);

        View decorView = getWindow().getDecorView();
        if (decorView instanceof ViewGroup) {
            ((ViewGroup) decorView).setClipChildren(false);
            ((ViewGroup) decorView).setClipToPadding(false);
        }

        keyboardView.post(() -> {
            try {
                keyboardView.setInitialPosition();
            } catch (Exception e) {
                Log.e(TAG, "Initial position error", e);
            }
        });

        setupFullscreen();
        setupCursorHiding();

        mouseX = screenWidth / 2f;
        mouseY = screenHeight / 2f;
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupFullscreen();
        updateScreenSize();

        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null)
            dm.registerDisplayListener(displayListener, null);

        // 立即初始化，无需延迟
        if (surfaceReady) {
            nativeStop();
            nativeStart(surfaceView.getHolder().getSurface());
            pushRefreshRate();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null)
            dm.unregisterDisplayListener(displayListener);

        // 取消待执行的 surface 初始化
        if (surfaceInitRunnable != null) {
            surfaceInitHandler.removeCallbacks(surfaceInitRunnable);
            surfaceInitRunnable = null;
        }
        nativeStop();
    }

    // ========== 横竖屏切换：仅更新尺寸，不重建 Surface（交给 surfaceChanged 防抖） ==========
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        isPortrait = (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT);
        updateScreenSize();
        mouseX = clamp(mouseX, 0, screenWidth);
        mouseY = clamp(mouseY, 0, screenHeight);
        pushRefreshRate();

        if (keyboardView != null && keyboardView.getVisibility() == View.VISIBLE) {
            keyboardView.post(() -> {
                try {
                    keyboardView.setInitialPosition();
                } catch (Exception e) {
                    Log.e(TAG, "setInitialPosition error in onConfigurationChanged", e);
                }
            });
        }
        Log.d(TAG, "onConfigurationChanged: portrait=" + isPortrait + ", screen " + screenWidth + "x" + screenHeight);
        // 注意：不在此处重建 Surface，由 surfaceChanged 的防抖逻辑处理
    }

    // ---------- SurfaceHolder.Callback ----------
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 无需操作
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
        surfaceReady = true;
        updateScreenSize();

        // 取消之前的延迟任务（避免快速旋转时多次执行）
        if (surfaceInitRunnable != null) {
            surfaceInitHandler.removeCallbacks(surfaceInitRunnable);
            surfaceInitRunnable = null;
        }

        // 延迟执行 surface 初始化，给系统足够时间完成旋转布局
        surfaceInitRunnable = () -> {
            surfaceInitRunnable = null;
            // 再次确认 surface 有效
            Surface surface = holder.getSurface();
            if (surface != null && surface.isValid()) {
                Log.d(TAG, "Surface init: calling nativeStart");
                nativeStop();
                nativeStart(surface);
                pushRefreshRate();
                Log.d(TAG, "Surface init: nativeStart done");
            } else {
                Log.w(TAG, "Surface init: surface is null or invalid, retry in 100ms");
                // 如果无效，再延迟一次
                surfaceInitHandler.postDelayed(() -> {
                    if (surfaceReady) {
                        nativeStop();
                        nativeStart(holder.getSurface());
                        pushRefreshRate();
                    }
                }, 100);
            }
        };
        surfaceInitHandler.postDelayed(surfaceInitRunnable, SURFACE_INIT_DELAY);

        // 键盘位置立即更新
        if (keyboardView != null && keyboardView.getVisibility() == View.VISIBLE) {
            keyboardView.post(() -> {
                try {
                    keyboardView.setInitialPosition();
                } catch (Exception e) {
                    Log.e(TAG, "setInitialPosition error in surfaceChanged", e);
                }
            });
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        // 取消待执行的任务
        if (surfaceInitRunnable != null) {
            surfaceInitHandler.removeCallbacks(surfaceInitRunnable);
            surfaceInitRunnable = null;
        }
        nativeStop();
    }

    // ---------- 辅助方法 ----------
    private void updateScreenSize() {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
        Log.d(TAG, "Screen size: " + screenWidth + "x" + screenHeight);
    }

    private void setupFullscreen() {
        WindowInsetsController ctrl = getWindow().getInsetsController();
        if (ctrl != null) {
            ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            ctrl.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    }

    private void setupCursorHiding() {
        surfaceView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
    }

    private void pushRefreshRate() {
        Display d = getDisplay();
        if (d != null) {
            float rate = d.getRefreshRate();
            if (rate > 0) {
                nativeSetRefreshRate(rate);
            }
        }
    }

    // ================================================================
    // 音量键处理（每 500ms 步进 0.5，松手后显示，持久化）
    // ================================================================
    private void saveSensitivity() {
        prefs.edit().putFloat(KEY_SENSITIVITY, sensitivity).apply();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getRepeatCount() == 0) {
                volumeUpLastAdjustTime = event.getEventTime();
                volumeUpHasAdjusted = false;
            } else {
                long now = event.getEventTime();
                if (now - volumeUpLastAdjustTime >= LONG_PRESS_THRESHOLD) {
                    volumeUpHasAdjusted = true;
                    sensitivity = Math.min(5.0f, sensitivity + 0.5f);
                    saveSensitivity();
                    volumeUpLastAdjustTime = now;
                }
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) {
                volumeDownLastAdjustTime = event.getEventTime();
                volumeDownHasAdjusted = false;
            } else {
                long now = event.getEventTime();
                if (now - volumeDownLastAdjustTime >= LONG_PRESS_THRESHOLD) {
                    volumeDownHasAdjusted = true;
                    sensitivity = Math.max(0.5f, sensitivity - 0.5f);
                    saveSensitivity();
                    volumeDownLastAdjustTime = now;
                }
            }
            return true;
        }

        if (event.getRepeatCount() == 0) {
            int scan = event.getScanCode();
            if (scan != 0) {
                nativeSendKey(0, scan);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (volumeUpHasAdjusted) {
                Toast.makeText(this, "灵敏度: " + String.format("%.1f", sensitivity), Toast.LENGTH_SHORT).show();
            } else {
                isTouchpadMode = !isTouchpadMode;
                Toast.makeText(this, isTouchpadMode ? "触摸板模式（相对移动）" : "普通触摸模式（绝对定位）", Toast.LENGTH_SHORT).show();
                resetTouchpadState();
            }
            volumeUpHasAdjusted = false;
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volumeDownHasAdjusted) {
                Toast.makeText(this, "灵敏度: " + String.format("%.1f", sensitivity), Toast.LENGTH_SHORT).show();
            } else {
                if (keyboardView != null) {
                    try {
                        boolean show = keyboardView.getVisibility() == View.GONE;
                        keyboardView.setVisibility(show ? View.VISIBLE : View.GONE);
                        if (show) {
                            keyboardView.post(() -> {
                                try {
                                    keyboardView.setInitialPosition();
                                    keyboardView.bringToFront();
                                } catch (Exception e) {
                                    Log.e(TAG, "Error showing keyboard", e);
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to toggle keyboard visibility", e);
                    }
                }
            }
            volumeDownHasAdjusted = false;
            return true;
        }

        int scan = event.getScanCode();
        if (scan != 0) {
            nativeSendKey(1, scan);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---------- 触摸事件 ----------
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isTouchpadMode) {
            return handleTouchpadGesture(event);
        } else {
            return handleTouchEventWithMouseFollow(event);
        }
    }

    // ---------- 鼠标/外设事件 ----------
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isMouseEvent(event)) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_MOVE) {
                float x = event.getX();
                float y = event.getY();
                mouseX = clamp(x, 0, screenWidth);
                mouseY = clamp(y, 0, screenHeight);
                nativeSendMouseMotion(mouseX, mouseY, 0f, 0f);
                return true;
            }
            if (action == MotionEvent.ACTION_SCROLL) {
                float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float h = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                if (v != 0) nativeSendMouseScroll(0, -v * 10);
                if (h != 0) nativeSendMouseScroll(1, h * 10);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    // ----- 绝对定位模式（竖屏不发送鼠标移动） -----
    private boolean handleTouchEventWithMouseFollow(MotionEvent event) {
        int action = event.getActionMasked();

        if (!isPortrait) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN ||
                    action == MotionEvent.ACTION_MOVE) {
                if (event.getPointerCount() > 0) {
                    float fx = event.getX(0);
                    float fy = event.getY(0);
                    mouseX = clamp(fx, 0, screenWidth);
                    mouseY = clamp(fy, 0, screenHeight);
                    nativeSendMouseMotion(mouseX, mouseY, 0f, 0f);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                nativeSendMouseMotion(mouseX, mouseY, 0f, 0f);
            }
        } else {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN ||
                    action == MotionEvent.ACTION_MOVE) {
                if (event.getPointerCount() > 0) {
                    mouseX = clamp(event.getX(0), 0, screenWidth);
                    mouseY = clamp(event.getY(0), 0, screenHeight);
                }
            }
        }

        return handleTouchEvent(event);
    }

    private boolean handleTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int idx = event.getActionIndex();
        int pid = event.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                nativeSendTouch(0, event.getX(idx), event.getY(idx), pid);
                nativeSendTouchFrame();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                nativeSendTouch(1, event.getX(idx), event.getY(idx), pid);
                nativeSendTouchFrame();
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    nativeSendTouch(2, event.getX(i), event.getY(i), event.getPointerId(i));
                }
                nativeSendTouchFrame();
                return true;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    nativeSendTouch(1, event.getX(i), event.getY(i), event.getPointerId(i));
                }
                nativeSendTouchFrame();
                return true;
        }
        return false;
    }

    // ========================================================================
    // 触摸板手势（完整实现）
    // ========================================================================
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
                isThreeFingerTapCandidate = false;
                isDoubleTapPending = false;
                isMultiFinger = false;
                currentState = STATE_ONE_FINGER;
                break;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                isMultiFinger = true;
                isSingleTapCandidate = false;
                isDoubleTapPending = false;
                isLongPressPossible = false;
                if (currentState == STATE_DRAGGING) {
                    nativeSendMouseButton(BTN_LEFT, false);
                    isDraggingActive = false;
                }

                if (pointerCount == 2) {
                    currentState = STATE_TWO_FINGER;
                    isTwoFingerTapCandidate = true;
                    isThreeFingerTapCandidate = false;
                    lastX1 = event.getX(0);
                    lastY1 = event.getY(0);
                    lastX2 = event.getX(1);
                    lastY2 = event.getY(1);
                } else if (pointerCount == 3) {
                    currentState = STATE_IDLE;
                    isTwoFingerTapCandidate = false;
                    isThreeFingerTapCandidate = true;
                    lastX1 = event.getX(0);
                    lastY1 = event.getY(0);
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

                    if (isLongPressPossible && !hasLongPressed &&
                            (event.getEventTime() - downTime1) >= TOUCH_LONG_PRESS_TIMEOUT) {
                        hasLongPressed = true;
                        currentState = STATE_DRAGGING;
                        isDraggingActive = true;
                        nativeSendMouseButton(BTN_LEFT, true);
                        mouseX = clamp(mouseX, 0, screenWidth);
                        mouseY = clamp(mouseY, 0, screenHeight);
                        nativeSendMouseMotion(mouseX, mouseY, 0f, 0f);
                        break;
                    }

                    if (currentState != STATE_DRAGGING && (Math.abs(dx) > 1 || Math.abs(dy) > 1)) {
                        float scale = getAcceleration(dx, dy) * sensitivity;
                        mouseX = clamp(mouseX + dx * scale, 0, screenWidth);
                        mouseY = clamp(mouseY + dy * scale, 0, screenHeight);
                        nativeSendMouseMotion(mouseX, mouseY, 0f, 0f);
                        lastX1 = x;
                        lastY1 = y;
                    } else if (currentState == STATE_DRAGGING) {
                        if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
                            float scale = getAcceleration(dx, dy) * sensitivity;
                            mouseX = clamp(mouseX + dx * scale, 0, screenWidth);
                            mouseY = clamp(mouseY + dy * scale, 0, screenHeight);
                            nativeSendMouseMotion(mouseX, mouseY, 0f, 0f);
                            lastX1 = x;
                            lastY1 = y;
                        }
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
                            if (Math.abs(avgDy) > Math.abs(avgDx) * 0.5) {
                                nativeSendMouseScroll(0, -avgDy);
                            }
                            if (Math.abs(avgDx) > Math.abs(avgDy) * 0.5) {
                                nativeSendMouseScroll(1, avgDx);
                            }
                            lastX1 = x1;
                            lastY1 = y1;
                            lastX2 = x2;
                            lastY2 = y2;
                        }
                    }
                } else if (pointerCount >= 3) {
                    if (isThreeFingerTapCandidate) {
                        float x1 = event.getX(0);
                        float y1 = event.getY(0);
                        float dist = (float) Math.hypot(x1 - lastX1, y1 - lastY1);
                        if (dist > touchSlop) {
                            isThreeFingerTapCandidate = false;
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
                    isDoubleTapPending = false;
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
                    nativeSendMouseButton(BTN_LEFT, false);
                    isDraggingActive = false;
                    resetTouchpadState();
                    return true;
                }

                if (isThreeFingerTapCandidate && isQuickTap) {
                    nativeSendMouseButton(BTN_MIDDLE, true);
                    nativeSendMouseButton(BTN_MIDDLE, false);
                    Log.d(TAG, "三指单击 → 中键");
                    resetTouchpadState();
                    return true;
                }

                if (isTwoFingerTapCandidate && isQuickTap) {
                    nativeSendMouseButton(BTN_RIGHT, true);
                    nativeSendMouseButton(BTN_RIGHT, false);
                    Log.d(TAG, "双指单击 → 右键");
                    resetTouchpadState();
                    return true;
                }

                if (currentState == STATE_ONE_FINGER && isSingleTapCandidate && isQuickTap) {
                    long gap = event.getEventTime() - lastTapTime;
                    float dist = (float) Math.hypot(lastX1 - lastTapX, lastY1 - lastTapY);
                    if (gap < 300 && dist < touchSlop && !isDoubleTapPending) {
                        isDoubleTapPending = true;
                        nativeSendMouseButton(BTN_LEFT, true);
                        nativeSendMouseButton(BTN_LEFT, false);
                        nativeSendMouseButton(BTN_LEFT, true);
                        nativeSendMouseButton(BTN_LEFT, false);
                        Log.d(TAG, "单指双击 → 左键双击");
                        isDoubleTapPending = false;
                        lastTapTime = 0;
                    } else {
                        nativeSendMouseButton(BTN_LEFT, true);
                        nativeSendMouseButton(BTN_LEFT, false);
                        Log.d(TAG, "单指单击 → 左键单击");
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
                    nativeSendMouseButton(BTN_LEFT, false);
                    isDraggingActive = false;
                }
                resetTouchpadState();
                break;
            }
        }
        return true;
    }

    // ----- 加速度计算 -----
    private float getAcceleration(float dx, float dy) {
        float distance = (float) Math.hypot(dx, dy);
        if (distance < 0.5f) return BASE_SCALE;
        float scale = BASE_SCALE + distance * SCALE_STEP;
        return Math.min(scale, MAX_SCALE);
    }

    private void resetTouchpadState() {
        currentState = STATE_IDLE;
        isSingleTapCandidate = false;
        isTwoFingerTapCandidate = false;
        isThreeFingerTapCandidate = false;
        isDoubleTapPending = false;
        hasLongPressed = false;
        isDraggingActive = false;
        isLongPressPossible = false;
        isMultiFinger = false;
        lastX1 = lastY1 = 0;
        lastX2 = lastY2 = 0;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------- 鼠标/触摸辅助 ----------
    private int savedBS = 0;
    private static final int[][] BUTTON_MAP = {
            {MotionEvent.BUTTON_PRIMARY, 0x110},
            {MotionEvent.BUTTON_SECONDARY, 0x111},
            {MotionEvent.BUTTON_TERTIARY, 0x112},
            {MotionEvent.BUTTON_BACK, 0x113},
            {MotionEvent.BUTTON_FORWARD, 0x114},
    };

    private boolean isMouseEvent(MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) return false;
        if ((source & InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE) return false;
        int toolType = event.getToolType(event.getActionIndex());
        return toolType == MotionEvent.TOOL_TYPE_MOUSE || toolType == MotionEvent.TOOL_TYPE_FINGER;
    }

    private boolean handleMouseEvent(MotionEvent event) {
        nativeSendMouseMotion(event.getX(), event.getY(), 0f, 0f);
        int cur = event.getButtonState();
        for (int[] btn : BUTTON_MAP) {
            boolean was = (savedBS & btn[0]) != 0;
            boolean now = (cur & btn[0]) != 0;
            if (was != now) nativeSendMouseButton(btn[1], now);
        }
        savedBS = cur;
        return true;
    }

    private boolean handleTouchpadScroll(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float sx = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE);
            float sy = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE);
            if (sy != 0) nativeSendMouseScroll(0, sy);
            if (sx != 0) nativeSendMouseScroll(1, -sx);
        }
        return true;
    }
}