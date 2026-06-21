package com.anland.consumer;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VirtualKeyboardView extends View {

    private static final String TAG = "VirtualKeyboard";

    // ---------- 最终键盘布局 ----------
    private final String[][] keyboardRows = {
            {"ESC", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"},
            {"`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "⌫"},
            {"Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]", "\\"},
            {"Caps", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'", "Enter"},
            {"Shift", "Z", "X", "C", "V", "B", "N", "M", ",", "↑", ".", "/", "Shift"},
            {"Ctrl", "Alt", "Space", "Alt", "Home", "←", "↓", "→", "End", "Ctrl"}
    };

    // ---------- 符号映射表 ----------
    private static final Map<String, String> SYMBOL_CHAR_MAP = new HashMap<>();
    static {
        String[] digits = {"1","2","3","4","5","6","7","8","9","0"};
        String[] digitSymbols = {"!", "@", "#", "$", "%", "^", "&", "*", "(", ")"};
        for (int i = 0; i < digits.length; i++) {
            SYMBOL_CHAR_MAP.put(digits[i], digitSymbols[i]);
        }
        SYMBOL_CHAR_MAP.put("`", "~");
        SYMBOL_CHAR_MAP.put("-", "_");
        SYMBOL_CHAR_MAP.put("=", "+");
        SYMBOL_CHAR_MAP.put("[", "{");
        SYMBOL_CHAR_MAP.put("]", "}");
        SYMBOL_CHAR_MAP.put("\\", "|");
        SYMBOL_CHAR_MAP.put(";", ":");
        SYMBOL_CHAR_MAP.put("'", "\"");
        SYMBOL_CHAR_MAP.put(",", "<");
        SYMBOL_CHAR_MAP.put(".", ">");
        SYMBOL_CHAR_MAP.put("/", "?");
    }

    private boolean hasSymbol(String label) {
        return SYMBOL_CHAR_MAP.containsKey(label);
    }

    // ---------- 状态 ----------
    private boolean leftShiftOn = false;
    private boolean leftCtrlOn  = false;
    private boolean leftAltOn   = false;
    private boolean rightShiftPressed = false;
    private boolean capsLockOn = false;
    private boolean symbolLayerActive = false;

    private final List<KeyData> keys = new ArrayList<>();
    private final SparseArray<KeyData> activePointers = new SparseArray<>();

    private float lastRawX, lastRawY;
    private boolean isDragging = false;
    private final int dragHandleHeight;

    private int screenWidth, screenHeight;   // 物理屏幕尺寸（仅用于上限）
    private OnKeyEventListener listener;

    private Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint keyBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint pressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint modActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int keyHeight;
    private int padding = 4;
    private int cornerRadius = 6;

    private int keyColor = Color.parseColor("#88E8E8E8");
    private int pressedColor = Color.parseColor("#88B0B0B0");
    private int modActiveColor = Color.parseColor("#8866B0FF");
    private int textColor = Color.WHITE;
    private int bgColor = Color.parseColor("#33000000");

    // 键盘宽度（动态计算）
    private int fixedWidth = -1;

    public interface OnKeyEventListener {
        void onKeyDown(int scanCode);
        void onKeyUp(int scanCode);
    }

    public VirtualKeyboardView(Context context) {
        super(context);
        dragHandleHeight = dpToPx(20);
        updatePhysicalScreenSize();
        initKeys();
        initPaints();
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClipToOutline(false);
        bringToFront();
    }

    // 获取物理屏幕尺寸（用于上限）
    private void updatePhysicalScreenSize() {
        try {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                Point realSize = new Point();
                display.getRealSize(realSize);
                screenWidth = realSize.x;
                screenHeight = realSize.y;
                Log.d(TAG, "Physical screen size: " + screenWidth + "x" + screenHeight);
            }
        } catch (Exception e) {
            Log.e(TAG, "updatePhysicalScreenSize error", e);
            screenWidth = 1920;
            screenHeight = 1080;
        }
    }

    @Override
    public void setVisibility(int visibility) {
        try {
            super.setVisibility(visibility);
            if (visibility == VISIBLE) {
                // 每次显示时重新测量宽度（基于当前窗口）
                requestLayout();
                post(() -> {
                    try {
                        setInitialPosition();
                        applySymbolLayer();
                    } catch (Exception e) {
                        Log.e(TAG, "setVisibility VISIBLE init error", e);
                    }
                });
            } else if (visibility == GONE) {
                leftShiftOn = false;
                leftCtrlOn = false;
                leftAltOn = false;
                rightShiftPressed = false;
                symbolLayerActive = false;
                if (listener != null) {
                    listener.onKeyUp(KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_SHIFT_RIGHT));
                }
                for (KeyData k : keys) {
                    k.modActive = false;
                    k.pressed = false;
                }
                applySymbolLayer();
                invalidate();
            }
        } catch (Exception e) {
            Log.e(TAG, "setVisibility error", e);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            ViewParent parent = getParent();
            while (parent instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) parent;
                vg.setClipChildren(false);
                vg.setClipToPadding(false);
                parent = vg.getParent();
            }
            View root = getRootView();
            if (root instanceof ViewGroup) {
                ((ViewGroup) root).setClipChildren(false);
                ((ViewGroup) root).setClipToPadding(false);
            }
            if (getParent() instanceof ViewGroup) {
                bringToFront();
            }
        } catch (Exception e) {
            Log.e(TAG, "onAttachedToWindow error", e);
        }
    }

    private void initPaints() {
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAntiAlias(true);
        textPaint.setColor(textColor);
        textPaint.setShadowLayer(2, 0, 1, Color.BLACK);
        keyBgPaint.setAntiAlias(true);
        pressedPaint.setAntiAlias(true);
        modActivePaint.setAntiAlias(true);
        modActivePaint.setColor(modActiveColor);
        handlePaint.setColor(Color.parseColor("#66FFFFFF"));
        handlePaint.setAntiAlias(true);
    }

    // ========== 初始化按键 ==========
    private void initKeys() {
        for (int r = 0; r < keyboardRows.length; r++) {
            String[] row = keyboardRows[r];
            for (int c = 0; c < row.length; c++) {
                String rawLabel = row[c];
                String internalLabel = rawLabel;
                if (rawLabel.equals("Shift")) {
                    if (r == 4) {
                        if (c == 0) internalLabel = "ShiftL";
                        else if (c == row.length - 1) internalLabel = "ShiftR";
                    }
                } else if (rawLabel.equals("Ctrl")) {
                    if (r == 5) {
                        if (c == 0) internalLabel = "CtrlL";
                        else if (c == row.length - 1) internalLabel = "CtrlR";
                    }
                } else if (rawLabel.equals("Alt")) {
                    if (r == 5) {
                        if (c == 1) internalLabel = "AltL";
                        else if (c == 3) internalLabel = "AltR";
                    }
                }

                String displayLabel;
                if (internalLabel.startsWith("Shift")) displayLabel = "Shift";
                else if (internalLabel.startsWith("Ctrl")) displayLabel = "Ctrl";
                else if (internalLabel.startsWith("Alt")) displayLabel = "Alt";
                else displayLabel = rawLabel;

                int keyCode = getKeyCodeForLabel(internalLabel);
                float weight = getWeightForLabel(internalLabel);
                boolean hasSym = hasSymbol(rawLabel);

                KeyData k = new KeyData(displayLabel, keyCode, weight, hasSym);
                k.internalLabel = internalLabel;
                if (hasSym) {
                    k.symbolChar = SYMBOL_CHAR_MAP.get(rawLabel);
                } else {
                    k.symbolChar = null;
                }
                k.currentLabel = displayLabel;
                k.currentKeyCode = keyCode;
                keys.add(k);
            }
        }
        Log.d(TAG, "Keys initialized: " + keys.size());
    }

    private int getKeyCodeForLabel(String label) {
        switch (label) {
            case "ESC": return KeyEvent.KEYCODE_ESCAPE;
            case "F1": return KeyEvent.KEYCODE_F1;
            case "F2": return KeyEvent.KEYCODE_F2;
            case "F3": return KeyEvent.KEYCODE_F3;
            case "F4": return KeyEvent.KEYCODE_F4;
            case "F5": return KeyEvent.KEYCODE_F5;
            case "F6": return KeyEvent.KEYCODE_F6;
            case "F7": return KeyEvent.KEYCODE_F7;
            case "F8": return KeyEvent.KEYCODE_F8;
            case "F9": return KeyEvent.KEYCODE_F9;
            case "F10": return KeyEvent.KEYCODE_F10;
            case "F11": return KeyEvent.KEYCODE_F11;
            case "F12": return KeyEvent.KEYCODE_F12;
            case "`": return KeyEvent.KEYCODE_GRAVE;
            case "1": return KeyEvent.KEYCODE_1;
            case "2": return KeyEvent.KEYCODE_2;
            case "3": return KeyEvent.KEYCODE_3;
            case "4": return KeyEvent.KEYCODE_4;
            case "5": return KeyEvent.KEYCODE_5;
            case "6": return KeyEvent.KEYCODE_6;
            case "7": return KeyEvent.KEYCODE_7;
            case "8": return KeyEvent.KEYCODE_8;
            case "9": return KeyEvent.KEYCODE_9;
            case "0": return KeyEvent.KEYCODE_0;
            case "-": return KeyEvent.KEYCODE_MINUS;
            case "=": return KeyEvent.KEYCODE_EQUALS;
            case "⌫": return KeyEvent.KEYCODE_DEL;
            case "Tab": return KeyEvent.KEYCODE_TAB;
            case "Q": return KeyEvent.KEYCODE_Q;
            case "W": return KeyEvent.KEYCODE_W;
            case "E": return KeyEvent.KEYCODE_E;
            case "R": return KeyEvent.KEYCODE_R;
            case "T": return KeyEvent.KEYCODE_T;
            case "Y": return KeyEvent.KEYCODE_Y;
            case "U": return KeyEvent.KEYCODE_U;
            case "I": return KeyEvent.KEYCODE_I;
            case "O": return KeyEvent.KEYCODE_O;
            case "P": return KeyEvent.KEYCODE_P;
            case "[": return KeyEvent.KEYCODE_LEFT_BRACKET;
            case "]": return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case "\\": return KeyEvent.KEYCODE_BACKSLASH;
            case "Caps": return KeyEvent.KEYCODE_CAPS_LOCK;
            case "A": return KeyEvent.KEYCODE_A;
            case "S": return KeyEvent.KEYCODE_S;
            case "D": return KeyEvent.KEYCODE_D;
            case "F": return KeyEvent.KEYCODE_F;
            case "G": return KeyEvent.KEYCODE_G;
            case "H": return KeyEvent.KEYCODE_H;
            case "J": return KeyEvent.KEYCODE_J;
            case "K": return KeyEvent.KEYCODE_K;
            case "L": return KeyEvent.KEYCODE_L;
            case ";": return KeyEvent.KEYCODE_SEMICOLON;
            case "'": return KeyEvent.KEYCODE_APOSTROPHE;
            case "Enter": return KeyEvent.KEYCODE_ENTER;
            case "ShiftL": return KeyEvent.KEYCODE_SHIFT_LEFT;
            case "ShiftR": return KeyEvent.KEYCODE_SHIFT_RIGHT;
            case "Z": return KeyEvent.KEYCODE_Z;
            case "X": return KeyEvent.KEYCODE_X;
            case "C": return KeyEvent.KEYCODE_C;
            case "V": return KeyEvent.KEYCODE_V;
            case "B": return KeyEvent.KEYCODE_B;
            case "N": return KeyEvent.KEYCODE_N;
            case "M": return KeyEvent.KEYCODE_M;
            case ",": return KeyEvent.KEYCODE_COMMA;
            case "↑": return KeyEvent.KEYCODE_DPAD_UP;
            case ".": return KeyEvent.KEYCODE_PERIOD;
            case "/": return KeyEvent.KEYCODE_SLASH;
            case "CtrlL": return KeyEvent.KEYCODE_CTRL_LEFT;
            case "CtrlR": return KeyEvent.KEYCODE_CTRL_RIGHT;
            case "AltL": return KeyEvent.KEYCODE_ALT_LEFT;
            case "AltR": return KeyEvent.KEYCODE_ALT_RIGHT;
            case "Home": return KeyEvent.KEYCODE_MOVE_HOME;
            case "End": return KeyEvent.KEYCODE_MOVE_END;
            case "Space": return KeyEvent.KEYCODE_SPACE;
            case "←": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "↓": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "→": return KeyEvent.KEYCODE_DPAD_RIGHT;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    private float getWeightForLabel(String label) {
        switch (label) {
            case "ESC":
            case "F1": case "F2": case "F3": case "F4": case "F5":
            case "F6": case "F7": case "F8": case "F9": case "F10":
            case "F11": case "F12":
            case "Home": case "End":
                return 1.0f;
            case "⌫": return 2.0f;
            case "Tab": return 1.5f;
            case "Caps": return 1.5f;
            case "Enter": return 1.5f;
            case "ShiftL": return 1.75f;
            case "ShiftR": return 1.75f;
            case "CtrlL": return 1.5f;
            case "CtrlR": return 1.5f;
            case "AltL": return 1.5f;
            case "AltR": return 1.5f;
            case "Space": return 3.0f;
            default: return 1.0f;
        }
    }

    public void setOnKeyEventListener(OnKeyEventListener l) {
        this.listener = l;
    }

    // ========== 测量与布局（动态宽度，自适应窗口） ==========
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        try {
            // 获取父容器建议宽度（即当前窗口宽度）
            int windowWidth = MeasureSpec.getSize(widthMeasureSpec);
            // 获取物理屏幕宽度（用于上限）
            updatePhysicalScreenSize();
            int physicalWidth = screenWidth;

            // 计算期望宽度：窗口宽度的85%，但不超过物理宽度的45%，且不小于400dp
            float ratioWindow = 0.85f;
            float ratioPhysical = 0.45f;
            int minWidth = dpToPx(400);
            int desiredWidth = (int) (windowWidth * ratioWindow);
            int maxWidth = (int) (physicalWidth * ratioPhysical);
            if (desiredWidth > maxWidth) desiredWidth = maxWidth;
            if (desiredWidth < minWidth) desiredWidth = minWidth;

            fixedWidth = desiredWidth;

            int rowCount = keyboardRows.length;
            int keyH = dpToPx(32);
            int totalHeight = dragHandleHeight + padding + rowCount * (keyH + padding) + padding;
            if (totalHeight <= 0) totalHeight = 350;

            setMeasuredDimension(fixedWidth, totalHeight);
            Log.d(TAG, "onMeasure: windowWidth=" + windowWidth + ", physicalWidth=" + physicalWidth
                    + ", fixedWidth=" + fixedWidth);
        } catch (Exception e) {
            Log.e(TAG, "onMeasure error", e);
            setMeasuredDimension(600, 350);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        try {
            layoutKeys(w, h);
            post(this::setInitialPosition);
        } catch (Exception e) {
            Log.e(TAG, "onSizeChanged error", e);
        }
    }

    private void layoutKeys(int viewW, int viewH) {
        int totalRows = keyboardRows.length;
        if (totalRows == 0) return;

        int availH = viewH - dragHandleHeight - padding * (totalRows + 1);
        if (availH < 20) availH = 20;
        keyHeight = availH / totalRows;
        if (keyHeight < 20) keyHeight = 20;

        int keyIndex = 0;
        for (int row = 0; row < totalRows; row++) {
            String[] rowLabels = keyboardRows[row];
            int numKeys = rowLabels.length;

            float totalWeight = 0;
            for (int c = 0; c < numKeys; c++) {
                KeyData k = keys.get(keyIndex + c);
                totalWeight += k.weight;
            }
            if (totalWeight <= 0) totalWeight = 1.0f;

            int rowWidth = viewW - padding * (numKeys + 1);
            if (rowWidth < 10) rowWidth = viewW;
            float unit = rowWidth / totalWeight;

            int rowY = dragHandleHeight + padding + row * (keyHeight + padding);
            if (rowY < 0) rowY = 0;

            int x = padding;
            for (int c = 0; c < numKeys; c++) {
                KeyData k = keys.get(keyIndex + c);
                int w = (int) (unit * k.weight);
                if (w < 25) w = 25;
                if (c == numKeys - 1) {
                    w = viewW - x - padding;
                    if (w < 25) w = 25;
                }
                k.rect.set(x, rowY, x + w, rowY + keyHeight);
                x += w + padding;
            }
            keyIndex += numKeys;
        }
        Log.d(TAG, "layoutKeys done, keyHeight=" + keyHeight);
    }

    // ========== 设置初始位置（基于当前窗口尺寸） ==========
    public void setInitialPosition() {
        try {
            if (getWidth() == 0 || getHeight() == 0) {
                post(this::setInitialPosition);
                return;
            }
            int w = getWidth();
            int h = getHeight();

            View root = getRootView();
            if (root == null) return;
            int windowWidth = root.getWidth();
            int windowHeight = root.getHeight();
            if (windowWidth <= 0 || windowHeight <= 0) {
                post(this::setInitialPosition);
                return;
            }

            float x = (windowWidth - w) / 2f;
            float y = windowHeight - h - dpToPx(50);
            setTranslationX(x);
            setTranslationY(y);
            bringToFront();
            Log.d(TAG, "setInitialPosition: window=" + windowWidth + "x" + windowHeight
                    + ", keyboard pos=(" + x + "," + y + ")");
        } catch (Exception e) {
            Log.e(TAG, "setInitialPosition error", e);
        }
    }

    // ========== 核心状态管理 ==========
    private void updateSymbolLayer() {
        symbolLayerActive = leftShiftOn || rightShiftPressed;
        applySymbolLayer();
    }

    private void applySymbolLayer() {
        try {
            for (KeyData k : keys) {
                if (k.hasSymbol && k.symbolChar != null) {
                    if (symbolLayerActive) {
                        k.currentLabel = k.symbolChar;
                        k.currentKeyCode = getKeyCodeForSymbol(k.symbolChar);
                    } else {
                        k.currentLabel = k.defaultLabel;
                        k.currentKeyCode = k.defaultKeyCode;
                    }
                }
            }
            for (KeyData k : keys) {
                if ("ShiftL".equals(k.internalLabel)) {
                    k.modActive = leftShiftOn;
                } else if ("ShiftR".equals(k.internalLabel)) {
                    k.modActive = rightShiftPressed;
                } else if ("CtrlL".equals(k.internalLabel)) {
                    k.modActive = leftCtrlOn;
                } else if ("AltL".equals(k.internalLabel)) {
                    k.modActive = leftAltOn;
                } else if ("Caps".equals(k.internalLabel)) {
                    k.modActive = capsLockOn;
                }
            }
            invalidate();
        } catch (Exception e) {
            Log.e(TAG, "applySymbolLayer error", e);
        }
    }

    private int getKeyCodeForSymbol(String symbol) {
        for (Map.Entry<String, String> entry : SYMBOL_CHAR_MAP.entrySet()) {
            if (entry.getValue().equals(symbol)) {
                return getKeyCodeForLabel(entry.getKey());
            }
        }
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    // ========== 发送按键 ==========
    private void sendKey(int keyCode, boolean down) {
        if (listener == null) return;
        int scan = KeyCodeMapper.getScanCode(keyCode);
        if (scan != -1) {
            if (down) listener.onKeyDown(scan);
            else listener.onKeyUp(scan);
        }
    }

    private void sendKeyWithCaps(int keyCode, boolean down) {
        if (listener == null) return;
        boolean isLetter = keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z;
        boolean shouldApplyCaps = isLetter && capsLockOn && !rightShiftPressed;

        if (shouldApplyCaps) {
            if (down) {
                sendKey(KeyEvent.KEYCODE_SHIFT_LEFT, true);
                sendKey(keyCode, true);
            } else {
                sendKey(keyCode, false);
                sendKey(KeyEvent.KEYCODE_SHIFT_LEFT, false);
            }
        } else {
            sendKey(keyCode, down);
        }
    }

    // ---------- 左侧修饰键组合 ----------
    private void executeCombination(int normalKeyCode) {
        List<KeyData> mods = new ArrayList<>();
        for (KeyData k : keys) {
            if (isLeftModifier(k) && getLeftModifierState(k)) {
                mods.add(k);
            }
        }
        if (mods.isEmpty()) return;

        for (KeyData k : mods) {
            sendKey(k.currentKeyCode, true);
        }
        sendKeyWithCaps(normalKeyCode, true);
        sendKeyWithCaps(normalKeyCode, false);
        for (int i = mods.size() - 1; i >= 0; i--) {
            sendKey(mods.get(i).currentKeyCode, false);
        }

        leftShiftOn = false;
        leftCtrlOn = false;
        leftAltOn = false;
        for (KeyData k : keys) {
            if (isLeftModifier(k)) {
                k.modActive = false;
            }
        }
        updateSymbolLayer();
        invalidate();
    }

    private boolean isLeftModifier(KeyData k) {
        if (k == null) return false;
        String in = k.internalLabel;
        return "ShiftL".equals(in) || "CtrlL".equals(in) || "AltL".equals(in);
    }

    private boolean getLeftModifierState(KeyData k) {
        if (k == null) return false;
        String in = k.internalLabel;
        if ("ShiftL".equals(in)) return leftShiftOn;
        if ("CtrlL".equals(in)) return leftCtrlOn;
        if ("AltL".equals(in)) return leftAltOn;
        return false;
    }

    private void toggleLeftModifier(KeyData k) {
        if (k == null) return;
        String in = k.internalLabel;
        boolean newState;
        if ("ShiftL".equals(in)) {
            newState = !leftShiftOn;
            leftShiftOn = newState;
        } else if ("CtrlL".equals(in)) {
            newState = !leftCtrlOn;
            leftCtrlOn = newState;
        } else if ("AltL".equals(in)) {
            newState = !leftAltOn;
            leftAltOn = newState;
        } else {
            return;
        }
        k.modActive = newState;
        updateSymbolLayer();
        invalidate();
    }

    // ---------- 右修饰键 ----------
    private void pressRightModifier(KeyData k) {
        if (k == null) return;
        String in = k.internalLabel;
        if ("ShiftR".equals(in)) {
            rightShiftPressed = true;
            sendKey(KeyEvent.KEYCODE_SHIFT_RIGHT, true);
            updateSymbolLayer();
        } else if ("CtrlR".equals(in)) {
            sendKey(KeyEvent.KEYCODE_CTRL_RIGHT, true);
        } else if ("AltR".equals(in)) {
            sendKey(KeyEvent.KEYCODE_ALT_RIGHT, true);
        }
        k.pressed = true;
        invalidate();
    }

    private void releaseRightModifier(KeyData k) {
        if (k == null) return;
        String in = k.internalLabel;
        if ("ShiftR".equals(in)) {
            rightShiftPressed = false;
            sendKey(KeyEvent.KEYCODE_SHIFT_RIGHT, false);
            updateSymbolLayer();
        } else if ("CtrlR".equals(in)) {
            sendKey(KeyEvent.KEYCODE_CTRL_RIGHT, false);
        } else if ("AltR".equals(in)) {
            sendKey(KeyEvent.KEYCODE_ALT_RIGHT, false);
        }
        k.pressed = false;
        invalidate();
    }

    // ========== onTouchEvent ==========
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        try {
            int action = event.getActionMasked();
            float x = event.getX();
            float y = event.getY();

            if (action == MotionEvent.ACTION_DOWN && y < dragHandleHeight) {
                isDragging = true;
                lastRawX = event.getRawX();
                lastRawY = event.getRawY();
                bringToFront();
                return true;
            }

            if (isDragging) {
                switch (action) {
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - lastRawX;
                        float dy = event.getRawY() - lastRawY;
                        float newX = getTranslationX() + dx;
                        float newY = getTranslationY() + dy;
                        setTranslationX(newX);
                        setTranslationY(newY);
                        bringToFront();
                        lastRawX = event.getRawX();
                        lastRawY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        isDragging = false;
                        return true;
                }
                return true;
            }

            int pointerIndex = event.getActionIndex();
            int pointerId = event.getPointerId(pointerIndex);
            float touchX = event.getX(pointerIndex);
            float touchY = event.getY(pointerIndex);
            KeyData hitKey = findKeyAt(touchX, touchY);

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (hitKey == null) break;

                    if (isLeftModifier(hitKey)) {
                        toggleLeftModifier(hitKey);
                        return true;
                    }

                    if ("Caps".equals(hitKey.internalLabel)) {
                        capsLockOn = !capsLockOn;
                        hitKey.modActive = capsLockOn;
                        invalidate();
                        return true;
                    }

                    if (isRightModifier(hitKey)) {
                        activePointers.put(pointerId, hitKey);
                        pressRightModifier(hitKey);
                        return true;
                    }

                    int code = hitKey.currentKeyCode;
                    if (isDirectionKey(code) || code == KeyEvent.KEYCODE_MOVE_HOME || code == KeyEvent.KEYCODE_MOVE_END) {
                        activePointers.put(pointerId, hitKey);
                        hitKey.pressed = true;
                        sendKey(code, true);
                        invalidate();
                        return true;
                    }

                    if (leftShiftOn || leftCtrlOn || leftAltOn) {
                        executeCombination(code);
                        return true;
                    } else {
                        activePointers.put(pointerId, hitKey);
                        hitKey.pressed = true;
                        sendKeyWithCaps(code, true);
                        invalidate();
                        return true;
                    }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    KeyData released = activePointers.get(pointerId);
                    if (released == null) break;
                    int relCode = released.currentKeyCode;

                    if (isRightModifier(released)) {
                        releaseRightModifier(released);
                    } else if (isDirectionKey(relCode) || relCode == KeyEvent.KEYCODE_MOVE_HOME || relCode == KeyEvent.KEYCODE_MOVE_END) {
                        sendKey(relCode, false);
                        released.pressed = false;
                    } else if (!isLeftModifier(released) && !"Caps".equals(released.internalLabel)) {
                        sendKeyWithCaps(relCode, false);
                        released.pressed = false;
                    }
                    activePointers.remove(pointerId);
                    invalidate();
                    break;
                }

                case MotionEvent.ACTION_MOVE: {
                    for (int i = 0; i < activePointers.size(); i++) {
                        int pid = activePointers.keyAt(i);
                        KeyData key = activePointers.valueAt(i);
                        int idx = event.findPointerIndex(pid);
                        if (idx < 0) continue;
                        float px = event.getX(idx);
                        float py = event.getY(idx);
                        if (!key.rect.contains((int) px, (int) py)) {
                            key.pressed = false;
                            int kc = key.currentKeyCode;
                            if (isRightModifier(key)) {
                                releaseRightModifier(key);
                            } else if (isDirectionKey(kc) || kc == KeyEvent.KEYCODE_MOVE_HOME || kc == KeyEvent.KEYCODE_MOVE_END) {
                                sendKey(kc, false);
                            } else if (!isLeftModifier(key) && !"Caps".equals(key.internalLabel)) {
                                sendKeyWithCaps(kc, false);
                            }
                            activePointers.remove(pid);
                            invalidate();
                        }
                    }
                    break;
                }

                case MotionEvent.ACTION_CANCEL: {
                    for (int i = 0; i < activePointers.size(); i++) {
                        KeyData key = activePointers.valueAt(i);
                        key.pressed = false;
                        int kc = key.currentKeyCode;
                        if (isRightModifier(key)) {
                            releaseRightModifier(key);
                        } else if (isDirectionKey(kc) || kc == KeyEvent.KEYCODE_MOVE_HOME || kc == KeyEvent.KEYCODE_MOVE_END) {
                            sendKey(kc, false);
                        } else if (!isLeftModifier(key) && !"Caps".equals(key.internalLabel)) {
                            sendKeyWithCaps(kc, false);
                        }
                    }
                    activePointers.clear();
                    leftShiftOn = false;
                    leftCtrlOn = false;
                    leftAltOn = false;
                    rightShiftPressed = false;
                    updateSymbolLayer();
                    invalidate();
                    break;
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "onTouchEvent fatal error", e);
            return false;
        }
    }

    private boolean isRightModifier(KeyData k) {
        if (k == null) return false;
        String in = k.internalLabel;
        return "ShiftR".equals(in) || "CtrlR".equals(in) || "AltR".equals(in);
    }

    private boolean isDirectionKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private KeyData findKeyAt(float x, float y) {
        for (KeyData k : keys) {
            if (k.rect.contains((int) x, (int) y)) return k;
        }
        return null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            if (keys.isEmpty()) return;

            bgPaint.setColor(bgColor);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), cornerRadius, cornerRadius, bgPaint);

            canvas.drawRoundRect(0, 0, getWidth(), dragHandleHeight, cornerRadius, cornerRadius, handlePaint);
            handlePaint.setColor(Color.parseColor("#66FFFFFF"));
            float dotY = dragHandleHeight / 2f;
            float dotSpacing = dpToPx(8);
            float startX = getWidth() / 2f - dotSpacing;
            for (int i = 0; i < 3; i++) {
                canvas.drawCircle(startX + i * dotSpacing, dotY, dpToPx(2), handlePaint);
            }

            for (KeyData k : keys) {
                Rect r = k.rect;
                int bg;
                if (k.pressed) {
                    bg = pressedColor;
                } else if (k.modActive) {
                    bg = modActiveColor;
                } else {
                    bg = keyColor;
                }
                keyBgPaint.setColor(bg);
                canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, cornerRadius, cornerRadius, keyBgPaint);

                keyBgPaint.setColor(Color.parseColor("#44FFFFFF"));
                keyBgPaint.setStyle(Paint.Style.STROKE);
                keyBgPaint.setStrokeWidth(1);
                canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, cornerRadius, cornerRadius, keyBgPaint);
                keyBgPaint.setStyle(Paint.Style.FILL);

                textPaint.setColor(textColor);
                float textSize = keyHeight * 0.4f;
                if (textSize <= 0) textSize = 20;
                textPaint.setTextSize(textSize);
                float cx = r.centerX();
                float cy = r.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2);
                String display = k.currentLabel != null ? k.currentLabel : k.defaultLabel;
                canvas.drawText(display, cx, cy, textPaint);
            }
        } catch (Exception e) {
            Log.e(TAG, "onDraw error", e);
        }
    }

    private int dpToPx(int dp) {
        try {
            return (int) (dp * getResources().getDisplayMetrics().density);
        } catch (Exception e) {
            return dp;
        }
    }

    // ========== 内部数据类 ==========
    private static class KeyData {
        Rect rect = new Rect();
        String defaultLabel;
        int defaultKeyCode;
        String internalLabel;
        boolean hasSymbol;
        String symbolChar;
        String currentLabel;
        int currentKeyCode;
        float weight;
        boolean pressed = false;
        boolean modActive = false;

        KeyData(String label, int keyCode, float weight, boolean hasSymbol) {
            this.defaultLabel = label;
            this.defaultKeyCode = keyCode;
            this.currentLabel = label;
            this.currentKeyCode = keyCode;
            this.weight = weight;
            this.hasSymbol = hasSymbol;
        }
    }
}