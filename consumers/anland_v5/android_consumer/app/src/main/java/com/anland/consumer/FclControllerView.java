package com.anland.consumer;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Checkable;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a FoldCraftLauncher controller (FCL-Controllers JSON) as a floating
 * overlay and turns its buttons / direction pads into Linux input events.
 *
 * The event model follows FCL's ControlButton / ControlDirection behaviour:
 * press / long-press / click / double-click events, auto-keep latching,
 * auto-click repeat, movable buttons, pointer-follow buttons, view-group
 * toggling and the Input (IME) button. Its editor follows FCL's information /
 * event structure while using lightweight views that fit anland's dependency-
 * free Android client.
 */
public class FclControllerView extends FrameLayout {

    private static final String TAG = "FclController";

    // Editor palette.  The controller overlay itself still uses the colours
    // from its imported FCL profile; these colours only style the editor UI.
    private static final int UI_ACCENT = 0xFF3478F6;
    private static final int UI_ACCENT_SOFT = 0xFFEAF1FF;
    private static final int UI_SURFACE = 0xFFFFFFFF;
    private static final int UI_SURFACE_ALT = 0xFFF7F9FC;
    private static final int UI_BORDER = 0xFFE2E7F0;
    private static final int UI_TEXT = 0xFF172033;
    private static final int UI_TEXT_MUTED = 0xFF687386;
    private static final int UI_DANGER = 0xFFD83A52;
    private static final int UI_DANGER_SOFT = 0xFFFFEDF0;

    private static final int LONG_PRESS_MS = 400;
    private static final int AUTO_CLICK_MS = 20;

    // Full FCL keycode table (FCLKeycodes) + anland mouse keys.
    private static final Object[][] KEYCODE_ENTRIES = {
        {0, "RESERVED"},
                {1, "ESC"},
                {2, "1"},
                {3, "2"},
                {4, "3"},
                {5, "4"},
                {6, "5"},
                {7, "6"},
                {8, "7"},
                {9, "8"},
                {10, "9"},
                {11, "0"},
                {12, "MINUS"},
                {13, "EQUAL"},
                {14, "BACKSPACE"},
                {15, "TAB"},
                {16, "Q"},
                {17, "W"},
                {18, "E"},
                {19, "R"},
                {20, "T"},
                {21, "Y"},
                {22, "U"},
                {23, "I"},
                {24, "O"},
                {25, "P"},
                {26, "LEFTBRACE"},
                {27, "RIGHTBRACE"},
                {28, "ENTER"},
                {29, "LEFTCTRL"},
                {30, "A"},
                {31, "S"},
                {32, "D"},
                {33, "F"},
                {34, "G"},
                {35, "H"},
                {36, "J"},
                {37, "K"},
                {38, "L"},
                {39, "SEMICOLON"},
                {40, "APOSTROPHE"},
                {41, "GRAVE"},
                {42, "LEFTSHIFT"},
                {43, "BACKSLASH"},
                {44, "Z"},
                {45, "X"},
                {46, "C"},
                {47, "V"},
                {48, "B"},
                {49, "N"},
                {50, "M"},
                {51, "COMMA"},
                {52, "DOT"},
                {53, "SLASH"},
                {54, "RIGHTSHIFT"},
                {55, "KPASTERISK"},
                {56, "LEFTALT"},
                {57, "SPACE"},
                {58, "CAPSLOCK"},
                {59, "F1"},
                {60, "F2"},
                {61, "F3"},
                {62, "F4"},
                {63, "F5"},
                {64, "F6"},
                {65, "F7"},
                {66, "F8"},
                {67, "F9"},
                {68, "F10"},
                {69, "NUMLOCK"},
                {70, "SCROLLLOCK"},
                {71, "KP7"},
                {72, "KP8"},
                {73, "KP9"},
                {74, "KPMINUS"},
                {75, "KP4"},
                {76, "KP5"},
                {77, "KP6"},
                {78, "KPPLUS"},
                {79, "KP1"},
                {80, "KP2"},
                {81, "KP3"},
                {82, "KP0"},
                {83, "KPDOT"},
                {87, "F11"},
                {88, "F12"},
                {96, "KPENTER"},
                {97, "RIGHTCTRL"},
                {98, "KPSLASH"},
                {99, "SYSRQ"},
                {100, "RIGHTALT"},
                {102, "HOME"},
                {103, "UP"},
                {104, "PAGEUP"},
                {105, "LEFT"},
                {106, "RIGHT"},
                {107, "END"},
                {108, "DOWN"},
                {109, "PAGEDOWN"},
                {110, "INSERT"},
                {111, "DELETE"},
                {117, "KPEQUAL"},
                {119, "PAUSE"},
                {121, "KPCOMMA"},
                {125, "LEFTMATA"},
                {126, "RIGHTMETA"},
                {183, "F13"},
                {184, "F14"},
                {185, "F15"},
                {186, "F16"},
                {187, "F17"},
                {188, "F18"},
                {189, "F19"},
                {190, "F20"},
                {191, "F21"},
                {192, "F22"},
                {193, "F23"},
                {194, "F24"},
                {240, "UNKNOWN"},
        {1000, "鼠标左键"}, {1001, "鼠标中键"}, {1002, "鼠标右键"},
        {1003, "滚轮上"}, {1004, "滚轮下"},
    };

    // FCL special keycodes (see FCLInput.MOUSE_* / FCLKeycodes).
    private static final int FCL_MOUSE_LEFT = 1000;
    private static final int FCL_MOUSE_MIDDLE = 1001;
    private static final int FCL_MOUSE_RIGHT = 1002;
    private static final int FCL_MOUSE_SCROLL_UP = 1003;
    private static final int FCL_MOUSE_SCROLL_DOWN = 1004;

    // Linux input-event-codes.h BTN_* values, matching MainActivity's mouse map.
    private static final int EV_BTN_LEFT = 0x110;
    private static final int EV_BTN_RIGHT = 0x111;
    private static final int EV_BTN_MIDDLE = 0x112;

    /** Bridge from controller events to the anland native input pipeline. */
    public interface Bridge {
        void key(int action, int evdev);                    // 0 = down, 1 = up
        void mouseButton(int button, boolean pressed);
        void mouseMove(float dx, float dy);
        void mouseScroll(int axis, float value, int discrete);
        void text(String text);
        void toggleIme();
        void toggleVirtualKeyboard();
        void openSettings();
        /** Switch the current orientation's controller profile; null = default. */
        void selectController(String id);
        /** The overlay must yield while its editor dialogs are open: the overlay
         *  window sits above any app dialog and would swallow its touches. */
        void setEditorDialogOpen(boolean open);
    }

    /**
     * Receives touches that land outside the controller controls. The overlay
     * routes every pointer itself while visible so that holding a button and
     * swiping the desktop surface work at the same time (multi-touch).
     */
    public interface SurfaceTouchForwarder {
        boolean onSurfaceTouch(MotionEvent event);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final List<View> controls = new ArrayList<>();
    private final Map<String, Boolean> groupVisible = new HashMap<>();
    private FclController controller;
    private Bridge bridge;
    private int openEditorDialogCount;
    private float mouseSensitivity = 1f;
    private boolean editMode = false;
    private SurfaceTouchForwarder surfaceForwarder;
    private final List<View> passThroughViews = new ArrayList<>();
    private final Map<View, Integer> controlPointers = new HashMap<>();
    // The single surface (desktop/touchpad) pointer, if one is being tracked.
    // A pointer keeps the role it was assigned on DOWN for its whole lifetime:
    // a control pointer is never also forwarded to the surface, and vice versa,
    // so a finger that slides off a button cannot fight the touchpad finger.
    private int surfacePointerId = -1;

    // Position overrides: control id -> [x thousandths, y thousandths].
    // Saved overrides survive rebuilds; pending ones only exist while editing.
    private final Map<String, int[]> savedPositions = new HashMap<>();
    private final Map<String, int[]> pendingPositions = new HashMap<>();
    private static final String PREFS_NAME = "anland_settings";

    public FclControllerView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClipChildren(false);
    }

    public void setBridge(Bridge bridge) {
        this.bridge = bridge;
    }

    /** Show a dialog from the overlay context, hiding the overlay first so the
     *  fullscreen overlay window cannot block the dialog's touches. */
    private void showOverlayDialog(Dialog dialog) {
        // Dialogs can be nested (the property editor opens the keycode picker).
        // Reference counting prevents the fullscreen controller overlay from
        // being restored over the still-open parent dialog.
        openEditorDialogCount++;
        if (openEditorDialogCount == 1 && bridge != null) {
            bridge.setEditorDialogOpen(true);
        }
        dialog.setOnDismissListener(d -> {
            openEditorDialogCount = Math.max(0, openEditorDialogCount - 1);
            if (openEditorDialogCount == 0 && bridge != null) {
                bridge.setEditorDialogOpen(false);
            }
        });
        try {
            dialog.show();
        } catch (RuntimeException e) {
            openEditorDialogCount = Math.max(0, openEditorDialogCount - 1);
            if (openEditorDialogCount == 0 && bridge != null) {
                bridge.setEditorDialogOpen(false);
            }
            throw e;
        }
    }

    public void setSurfaceTouchForwarder(SurfaceTouchForwarder forwarder) {
        this.surfaceForwarder = forwarder;
    }

    /** Other overlays (IME, extra-keys bar...) that keep normal touch dispatch. */
    public void setPassThroughViews(List<View> views) {
        passThroughViews.clear();
        if (views != null) {
            passThroughViews.addAll(views);
        }
    }

    public void setController(FclController controller) {
        this.controller = controller;
        rebuild();
    }

    public boolean hasController() {
        return controller != null;
    }

    /** Toggle layout-editing mode: controls can be dragged and repositioned. */
    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        if (!editMode) {
            pendingPositions.clear();
        }
        invalidate();
    }

    public boolean isEditMode() {
        return editMode;
    }

    /** Persist pending drag positions as the saved layout for this controller. */
    public void savePositions() {
        if (controller == null) {
            return;
        }
        savedPositions.putAll(pendingPositions);
        pendingPositions.clear();
        writePositions();
        setEditMode(false);
        rebuild();
    }

    /** Discard pending drag positions and go back to the saved layout. */
    public void discardPositions() {
        pendingPositions.clear();
        setEditMode(false);
        rebuild();
    }

    /** Clear all saved position overrides and restore the original controller JSON layout. */
    public void resetPositions() {
        savedPositions.clear();
        pendingPositions.clear();
        writePositions();
        setEditMode(false);
        rebuild();
    }

    public void setMouseSensitivity(float sensitivity) {
        this.mouseSensitivity = sensitivity;
    }

    public void toggleGroup(String groupId) {
        Boolean visible = groupVisible.get(groupId);
        if (visible == null) {
            return;
        }
        boolean next = !visible;
        groupVisible.put(groupId, next);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (groupId.equals(child.getTag())) {
                child.setVisibility(next ? VISIBLE : GONE);
            }
        }
    }

    /** Rebuild all controls for the current controller and overlay size. */
    public void rebuild() {
        if (controller == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            // A GONE view is never measured (width/height stay 0); rebuilding is
            // triggered again from setVisibility(VISIBLE) once it can be laid out.
            if (getVisibility() != GONE) {
                post(this::rebuild);
            }
            return;
        }

        removeAllViews();
        controls.clear();
        groupVisible.clear();
        loadPositions();

        for (FclController.ViewGroup group : controller.viewGroups) {
            boolean visible = "VISIBLE".equals(group.visibility);
            groupVisible.put(group.id, visible);
            for (FclController.Button button : group.buttons) {
                if ("EDIT".equals(button.baseInfo.visibilityType)) {
                    continue;
                }
                FclButtonView view = new FclButtonView(getContext(), button);
                view.setTag(group.id);
                view.setVisibility(visible ? VISIBLE : GONE);
                addView(view);
                controls.add(view);
            }
        for (FclController.Direction direction : group.directions) {
                if ("EDIT".equals(direction.baseInfo.visibilityType)) {
                    continue;
                }
                FclDirectionView view = new FclDirectionView(getContext(), direction);
                view.setTag(group.id);
                view.setVisibility(visible ? VISIBLE : GONE);
                addView(view);
                controls.add(view);
            }
        }
        requestLayout();
        postInvalidate();
    }

    /** Save pending drag positions and per-key edits into the controller file. */
    public void saveEdit() {
        applyPendingPositionsToJson();
        controller.saveToFile(getContext());
        reloadController();
        setEditMode(false);
    }

    private void applyPendingPositionsToJson() {
        if (controller == null) {
            return;
        }
        for (Map.Entry<String, int[]> e : pendingPositions.entrySet()) {
            JSONObject ctrl = controller.findControlJson(e.getKey());
            if (ctrl == null) {
                continue;
            }
            int[] pos = e.getValue();
            try {
                JSONObject base = ctrl.optJSONObject("baseInfo");
                if (base == null) {
                    base = new JSONObject();
                    ctrl.put("baseInfo", base);
                }
                base.put("xPosition", pos[0]);
                base.put("yPosition", pos[1]);
            } catch (JSONException ignored) {
            }
        }
    }

    /** Back / 完成 in edit mode: ask whether to keep the changes. */
    public void promptExitEditMode() {
        if (!editMode) {
            return;
        }
        LinearLayout root = createEditorCard(
                "保存修改？", "退出编辑模式前，请处理尚未保存的位置调整");
        TextView message = new TextView(getContext());
        message.setText("保存会把当前拖动位置写入所选控制器；不保存只放弃尚未保存的拖动。");
        message.setTextSize(14);
        message.setTextColor(UI_TEXT);
        message.setLineSpacing(0, 1.15f);
        message.setPadding(dp(4), dp(18), dp(4), dp(18));
        root.addView(message);

        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(12), 0, 0);
        Button cancelBtn = ghostButton("继续编辑");
        Button discardBtn = dangerButton("不保存");
        Button saveBtn = accentButton("保存");
        bottom.addView(cancelBtn);
        LinearLayout.LayoutParams discardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        discardParams.setMarginStart(dp(8));
        bottom.addView(discardBtn, discardParams);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.setMarginStart(dp(8));
        bottom.addView(saveBtn, saveParams);
        root.addView(divider());
        root.addView(bottom);

        Dialog dialog = createEditorDialog(root, false);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        discardBtn.setOnClickListener(v -> {
            discardPositions();
            dialog.dismiss();
        });
        saveBtn.setOnClickListener(v -> {
            saveEdit();
            dialog.dismiss();
        });
        showOverlayDialog(dialog);
    }

    /** Re-parse the controller from disk/asset (after edits) and rebuild. */
    private void reloadController() {
        if (controller == null) {
            return;
        }
        FclController fresh = FclController.load(getContext(), controller.id);
        if (fresh != null) {
            controller = fresh;
            savedPositions.clear();
            pendingPositions.clear();
            writePositions();
            rebuild();
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE && controller != null) {
            rebuild();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && controller != null && getVisibility() == VISIBLE) {
            rebuild();
        }
    }

    /**
     * Multi-touch routing. While the overlay is visible every pointer is handled
     * here: pointers inside a control go to that control (so several buttons can
     * be pressed at once), pointers outside go to the surface forwarder. Without
     * this, Android gives the whole gesture to whichever view got the first
     * touch, making it impossible to hold a key and swipe the screen together.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (controller == null || getVisibility() != VISIBLE || editMode
                || surfaceForwarder == null || hitPassThrough(ev)) {
            return super.dispatchTouchEvent(ev);
        }
        routeTouchEvent(ev);
        return true;
    }

    private boolean hitPassThrough(MotionEvent ev) {
        if (passThroughViews.isEmpty()) {
            return false;
        }
        for (int i = 0; i < ev.getPointerCount(); i++) {
            float x = ev.getX(i);
            float y = ev.getY(i);
            for (View v : passThroughViews) {
                if (v != null && v.getVisibility() == VISIBLE
                        && x >= v.getX() && x <= v.getX() + v.getWidth()
                        && y >= v.getY() && y <= v.getY() + v.getHeight()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void routeTouchEvent(MotionEvent ev) {
        int masked = ev.getActionMasked();
        int idx = ev.getActionIndex();
        View control = controlAt(ev.getX(idx), ev.getY(idx));
        switch (masked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (control != null) {
                    controlPointers.put(control, ev.getPointerId(idx));
                    dispatchControl(control, ev, MotionEvent.ACTION_DOWN, idx);
                } else {
                    surfacePointerDown(ev, idx);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                List<Map.Entry<View, Integer>> entries =
                        new ArrayList<>(controlPointers.entrySet());
                for (Map.Entry<View, Integer> e : entries) {
                    int pi = pointerIndex(ev, e.getValue());
                    if (pi >= 0) {
                        dispatchControl(e.getKey(), ev, MotionEvent.ACTION_MOVE, pi);
                    }
                }
                int si = pointerIndex(ev, surfacePointerId);
                if (si >= 0) {
                    forwardSurface(ev, MotionEvent.ACTION_MOVE, si);
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                int pid = ev.getPointerId(idx);
                View mapped = controlByPointerId(pid);
                if (mapped != null) {
                    dispatchControl(mapped, ev, MotionEvent.ACTION_UP, idx);
                    controlPointers.remove(mapped);
                } else if (ev.getPointerId(idx) == surfacePointerId) {
                    forwardSurface(ev, MotionEvent.ACTION_UP, idx);
                    surfacePointerId = -1;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                for (Map.Entry<View, Integer> e : new ArrayList<>(controlPointers.entrySet())) {
                    int pi = pointerIndex(ev, e.getValue());
                    dispatchControl(e.getKey(), ev, MotionEvent.ACTION_CANCEL, Math.max(0, pi));
                }
                controlPointers.clear();
                if (surfacePointerId >= 0) {
                    forwardSurface(ev, MotionEvent.ACTION_CANCEL, idx);
                    surfacePointerId = -1;
                }
                break;
        }
    }

    private void surfacePointerDown(MotionEvent ev, int idx) {
        if (surfacePointerId >= 0) {
            return; // a second surface finger is not tracked while controls are active
        }
        surfacePointerId = ev.getPointerId(idx);
        forwardSurface(ev, MotionEvent.ACTION_DOWN, idx);
    }

    private void dispatchControl(View view, MotionEvent src, int action, int idx) {
        float x = src.getX(idx) - view.getX();
        float y = src.getY(idx) - view.getY();
        MotionEvent e = MotionEvent.obtain(src.getDownTime(), src.getEventTime(),
                action, x, y, src.getMetaState());
        view.onTouchEvent(e);
        e.recycle();
    }

    private void forwardSurface(MotionEvent src, int action, int idx) {
        MotionEvent e = MotionEvent.obtain(src.getDownTime(), src.getEventTime(),
                action, src.getX(idx), src.getY(idx), src.getMetaState());
        surfaceForwarder.onSurfaceTouch(e);
        e.recycle();
    }

    private int pointerIndex(MotionEvent ev, int pointerId) {
        for (int i = 0; i < ev.getPointerCount(); i++) {
            if (ev.getPointerId(i) == pointerId) {
                return i;
            }
        }
        return -1;
    }

    private View controlByPointerId(int pointerId) {
        for (Map.Entry<View, Integer> e : controlPointers.entrySet()) {
            if (e.getValue() == pointerId) {
                return e.getKey();
            }
        }
        return null;
    }

    private View controlAt(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE) {
                continue;
            }
            if (x >= child.getX() && x <= child.getX() + child.getWidth()
                    && y >= child.getY() && y <= child.getY() + child.getHeight()) {
                return child;
            }
        }
        return null;
    }

    /** Release every held key/button (call when hiding the overlay). */
    public void releaseAll() {
        for (View v : controls) {
            if (v instanceof FclButtonView) {
                ((FclButtonView) v).releaseAll();
            } else if (v instanceof FclDirectionView) {
                ((FclDirectionView) v).releaseAll();
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private GradientDrawable roundedDrawable(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private GradientDrawable roundedStrokeDrawable(int color, int radiusPx,
                                                    int strokeColor, int strokeWidthPx) {
        GradientDrawable g = roundedDrawable(color, radiusPx);
        if (strokeWidthPx > 0) {
            g.setStroke(strokeWidthPx, strokeColor);
        }
        return g;
    }

    private Drawable rippleDrawable(int color, int radiusPx,
                                    int strokeColor, int strokeWidthPx) {
        GradientDrawable content = roundedStrokeDrawable(
                color, radiusPx, strokeColor, strokeWidthPx);
        GradientDrawable mask = roundedDrawable(Color.WHITE, radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(0x1F3478F6), content, mask);
    }

    private void prepareButton(Button b, String text) {
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(14), dp(7), dp(14), dp(7));
        b.setGravity(Gravity.CENTER);
        b.setStateListAnimator(null);
    }

    private Button accentButton(String text) {
        Button b = new Button(getContext());
        prepareButton(b, text);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setBackground(rippleDrawable(UI_ACCENT, dp(12), UI_ACCENT, 0));
        return b;
    }

    private Button tabButton(String text, boolean selected) {
        Button b = new Button(getContext());
        prepareButton(b, text);
        b.setTextSize(12);
        setTabSelected(b, selected);
        return b;
    }

    private void setTabSelected(Button button, boolean selected) {
        button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(selected ? Color.WHITE : UI_TEXT_MUTED);
        button.setBackground(rippleDrawable(
                selected ? UI_ACCENT : UI_SURFACE_ALT,
                dp(11), selected ? UI_ACCENT : UI_BORDER, dp(1)));
    }

    /** Compact FCL-style side navigation, with text instead of font-dependent emoji. */
    private Button railIcon(String label) {
        Button b = new Button(getContext());
        prepareButton(b, label);
        b.setTextSize(12);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(62), dp(46)));
        setRailSelected(b, false);
        return b;
    }

    private void setRailSelected(Button button, boolean selected) {
        button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(selected ? UI_ACCENT : UI_TEXT_MUTED);
        button.setBackground(rippleDrawable(
                selected ? UI_ACCENT_SOFT : Color.TRANSPARENT,
                dp(12), selected ? 0x553478F6 : Color.TRANSPARENT, dp(1)));
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(UI_TEXT_MUTED);
        tv.setPadding(dp(2), dp(8), 0, dp(7));
        return tv;
    }

    private View divider() {
        View v = new View(getContext());
        v.setBackgroundColor(UI_BORDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private TextView formLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(UI_TEXT);
        tv.setMaxLines(2);
        return tv;
    }

    /** Label on the left, control on the right (FCL form-row style). */
    private LinearLayout formRow(View label, View control) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        row.setPadding(dp(14), dp(6), dp(12), dp(6));
        row.setBackground(rippleDrawable(
                UI_SURFACE, dp(13), UI_BORDER, dp(1)));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rowParams);

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginEnd(dp(12));
        row.addView(label, labelParams);
        row.addView(control, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (control instanceof FclToggle) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> ((FclToggle) control).toggle());
        }
        return row;
    }

    private EditText fclEditText(String hint) {
        EditText et = new EditText(getContext());
        et.setHint(hint);
        et.setTextSize(14);
        et.setTextColor(UI_TEXT);
        et.setHintTextColor(0xFF9AA3B2);
        et.setSingleLine(true);
        et.setSelectAllOnFocus(true);
        et.setMinWidth(dp(160));
        et.setMinimumHeight(dp(42));
        et.setPadding(dp(12), dp(7), dp(12), dp(7));
        et.setBackground(roundedStrokeDrawable(
                UI_SURFACE_ALT, dp(10), UI_BORDER, dp(1)));
        return et;
    }

    private Spinner fclSpinner(List<String> items) {
        Spinner spinner = new Spinner(getContext(), Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getContext(),
                android.R.layout.simple_spinner_item, items) {
            private TextView styleView(View view, boolean dropdown) {
                TextView tv = (TextView) view;
                tv.setTextSize(13);
                tv.setTextColor(UI_TEXT);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setMinHeight(dp(dropdown ? 46 : 40));
                tv.setPadding(dp(12), dp(8), dp(12), dp(8));
                if (dropdown) {
                    tv.setBackgroundColor(UI_SURFACE);
                }
                return tv;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return styleView(super.getView(position, convertView, parent), false);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return styleView(super.getDropDownView(position, convertView, parent), true);
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setMinimumWidth(dp(160));
        spinner.setMinimumHeight(dp(42));
        spinner.setPopupBackgroundDrawable(roundedStrokeDrawable(
                UI_SURFACE, dp(10), UI_BORDER, dp(1)));
        spinner.setBackground(rippleDrawable(
                UI_SURFACE_ALT, dp(10), UI_BORDER, dp(1)));
        return spinner;
    }

    private FclToggle fclSwitch(String label) {
        return new FclToggle(getContext(), label);
    }

    private FclToggle fclSelectionSwitch(String label) {
        return new FclToggle(getContext(), label, "选中", "未选");
    }

    /** Compact multi-column cell used by the keycode picker. */
    private TextView keycodeChip(String label, boolean selected) {
        TextView chip = new TextView(getContext());
        chip.setTag(label);
        chip.setTextSize(12);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setPadding(dp(8), dp(6), dp(8), dp(6));
        chip.setMinimumHeight(dp(42));
        chip.setClickable(true);
        chip.setFocusable(true);
        setKeycodeChipSelected(chip, selected);
        return chip;
    }

    private void setKeycodeChipSelected(TextView chip, boolean selected) {
        String label = (String) chip.getTag();
        chip.setSelected(selected);
        chip.setText((selected ? "✓ " : "") + label);
        chip.setTextColor(selected ? Color.WHITE : UI_TEXT);
        chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        chip.setBackground(rippleDrawable(
                selected ? UI_ACCENT : UI_SURFACE,
                dp(11), selected ? UI_ACCENT : UI_BORDER, dp(1)));
        chip.setContentDescription(label + (selected ? "，已选中" : "，未选中"));
    }

    private Button ghostButton(String text) {
        Button b = new Button(getContext());
        prepareButton(b, text);
        b.setTextColor(UI_ACCENT);
        b.setBackground(rippleDrawable(
                UI_SURFACE, dp(11), UI_BORDER, dp(1)));
        return b;
    }

    private Button dangerButton(String text) {
        Button b = new Button(getContext());
        prepareButton(b, text);
        b.setTextColor(UI_DANGER);
        b.setBackground(rippleDrawable(
                UI_DANGER_SOFT, dp(11), 0x33D83A52, dp(1)));
        return b;
    }

    private LinearLayout createEditorCard(String title, String subtitle) {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(14));
        root.setBackground(roundedDrawable(UI_SURFACE_ALT, dp(22)));
        root.setClipToOutline(true);
        root.setElevation(dp(14));

        TextView titleView = new TextView(getContext());
        titleView.setText(title);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(UI_TEXT);
        root.addView(titleView);

        TextView subtitleView = new TextView(getContext());
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(12);
        subtitleView.setTextColor(UI_TEXT_MUTED);
        subtitleView.setPadding(0, dp(3), 0, dp(12));
        root.addView(subtitleView);
        root.addView(divider());
        return root;
    }

    /**
     * Put the editor card in a full-window transparent host.  Centering the
     * card inside real window bounds is reliable in fullscreen/freeform modes,
     * unlike assigning physical display x/y coordinates to a floating dialog.
     */
    private Dialog createEditorDialog(View card) {
        return createEditorDialog(card, true);
    }

    private Dialog createEditorDialog(View card, boolean fillHeight) {
        Dialog dialog = new Dialog(getContext(), R.style.AnlandEditorDialog);
        FrameLayout host = new FrameLayout(getContext());
        host.setClipChildren(false);
        host.setPadding(dp(18), dp(26), dp(18), dp(26));

        int availableWidth = Math.max(1,
                getResources().getDisplayMetrics().widthPixels - dp(36));
        int cardWidth = Math.min(dp(520), availableWidth);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                cardWidth, fillHeight ? FrameLayout.LayoutParams.MATCH_PARENT
                : FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        host.addView(card, cardParams);
        host.addOnLayoutChangeListener((v, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            int actualWidth = Math.max(1, right - left
                    - host.getPaddingLeft() - host.getPaddingRight());
            int targetWidth = Math.min(dp(520), actualWidth);
            ViewGroup.LayoutParams params = card.getLayoutParams();
            if (params.width != targetWidth) {
                params.width = targetWidth;
                card.setLayoutParams(params);
            }
        });
        dialog.setContentView(host, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window == null) {
                return;
            }
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.width = WindowManager.LayoutParams.MATCH_PARENT;
            attrs.height = WindowManager.LayoutParams.MATCH_PARENT;
            attrs.gravity = Gravity.CENTER;
            attrs.x = 0;
            attrs.y = 0;
            attrs.dimAmount = 0.55f;
            window.setAttributes(attrs);
            window.getDecorView().setPadding(0, 0, 0, 0);
            if (getContext() instanceof android.app.Activity) {
                View sourceDecor = ((android.app.Activity) getContext())
                        .getWindow().getDecorView();
                window.getDecorView().setSystemUiVisibility(
                        sourceDecor.getSystemUiVisibility());
            }
        });
        return dialog;
    }

    /** Visible, dependency-free replacement for the legacy platform Switch. */
    private final class FclToggle extends LinearLayout implements Checkable {
        private final String label;
        private final String checkedText;
        private final String uncheckedText;
        private final TextView status;
        private final ToggleIndicator indicator;
        private boolean checked;
        private Runnable checkedChangeListener;

        FclToggle(Context context, String label) {
            this(context, label, "开启", "关闭");
        }

        FclToggle(Context context, String label,
                  String checkedText, String uncheckedText) {
            super(context);
            this.label = label;
            this.checkedText = checkedText;
            this.uncheckedText = uncheckedText;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(40));
            setPadding(dp(4), 0, dp(2), 0);
            setClickable(true);
            setFocusable(true);

            status = new TextView(context);
            status.setTextSize(13);
            status.setGravity(Gravity.CENTER);
            status.setMinWidth(dp(38));
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            statusParams.setMarginEnd(dp(8));
            addView(status, statusParams);

            indicator = new ToggleIndicator(context);
            addView(indicator, new LinearLayout.LayoutParams(dp(48), dp(30)));
            setOnClickListener(v -> toggle());
            updateVisuals();
        }

        @Override
        public void setChecked(boolean checked) {
            if (this.checked == checked) {
                updateVisuals();
                return;
            }
            this.checked = checked;
            updateVisuals();
            refreshDrawableState();
            if (checkedChangeListener != null) {
                checkedChangeListener.run();
            }
        }

        @Override
        public boolean isChecked() {
            return checked;
        }

        @Override
        public void toggle() {
            setChecked(!checked);
        }

        void setOnCheckedChangeListener(Runnable listener) {
            checkedChangeListener = listener;
        }

        private void updateVisuals() {
            status.setText(checked ? checkedText : uncheckedText);
            status.setTextColor(checked ? UI_ACCENT : UI_TEXT_MUTED);
            status.setTypeface(null, checked ? Typeface.BOLD : Typeface.NORMAL);
            indicator.setChecked(checked);
            setContentDescription(label + "，" + (checked ? checkedText : uncheckedText));
            setStateDescription(checked ? checkedText : uncheckedText);
        }
    }

    /** The track has an explicit size, so it cannot collapse to 0 px. */
    private final class ToggleIndicator extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;

        ToggleIndicator(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setChecked(boolean checked) {
            this.checked = checked;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float trackHeight = dp(24);
            float left = dp(2);
            float right = getWidth() - dp(2);
            float top = (getHeight() - trackHeight) / 2f;
            float bottom = top + trackHeight;
            float radius = trackHeight / 2f;
            paint.setColor(checked ? UI_ACCENT : 0xFFCBD2DE);
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);

            float thumbRadius = dp(10);
            float cx = checked ? right - radius : left + radius;
            float cy = getHeight() / 2f;
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, thumbRadius, paint);
        }
    }

    private JSONObject groupJsonForControl(String controlId) {
        if (controller == null) {
            return null;
        }
        for (FclController.ViewGroup g : controller.viewGroups) {
            for (FclController.Button b : g.buttons) {
                if (b.id.equals(controlId)) {
                    return controller.findGroupJson(g.id);
                }
            }
            for (FclController.Direction d : g.directions) {
                if (d.id.equals(controlId)) {
                    return controller.findGroupJson(g.id);
                }
            }
        }
        return null;
    }

        private void openKeycodePicker(final List<Integer> codes, final Runnable after) {
            final int n = KEYCODE_ENTRIES.length;
            final List<Integer> workingCodes = new ArrayList<>(codes);
            LinearLayout root = createEditorCard(
                    "选择键码", "支持多选；取消不会改动原来的键位");

            EditText searchInput = fclEditText("名称或数字键码");
            searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            searchParams.setMargins(0, dp(10), 0, dp(4));
            root.addView(searchInput, searchParams);

            LinearLayout selectionBar = new LinearLayout(getContext());
            selectionBar.setOrientation(LinearLayout.HORIZONTAL);
            selectionBar.setGravity(Gravity.CENTER_VERTICAL);
            selectionBar.setPadding(dp(2), dp(3), dp(2), dp(5));
            TextView selectedText = new TextView(getContext());
            selectedText.setTextSize(12);
            selectedText.setTextColor(UI_TEXT_MUTED);
            selectionBar.addView(selectedText, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView clearText = new TextView(getContext());
            clearText.setText("清空");
            clearText.setTextSize(12);
            clearText.setTextColor(UI_ACCENT);
            clearText.setTypeface(null, Typeface.BOLD);
            clearText.setPadding(dp(10), dp(6), dp(2), dp(6));
            clearText.setClickable(true);
            clearText.setFocusable(true);
            selectionBar.addView(clearText);
            root.addView(selectionBar);

            ScrollView listScroll = new ScrollView(getContext());
            listScroll.setFillViewport(true);
            listScroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            LinearLayout grid = new LinearLayout(getContext());
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(dp(1), 0, dp(3), dp(4));
            listScroll.addView(grid);
            root.addView(listScroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            int columns = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 3 : 2;
            final TextView[] chips = new TextView[n];
            final String[] searchTokens = new String[n];
            final List<LinearLayout> chipRows = new ArrayList<>();
            final Runnable updateCount = () -> {
                selectedText.setText("已选择 " + workingCodes.size() + " 项");
                clearText.setAlpha(workingCodes.isEmpty() ? 0.45f : 1f);
            };
            for (int i = 0; i < n; i++) {
                final int code = (Integer) KEYCODE_ENTRIES[i][0];
                String name = (String) KEYCODE_ENTRIES[i][1];
                if (i % columns == 0) {
                    LinearLayout row = new LinearLayout(getContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
                    if (!chipRows.isEmpty()) {
                        rowParams.topMargin = dp(6);
                    }
                    grid.addView(row, rowParams);
                    chipRows.add(row);
                }
                final TextView chip = keycodeChip(name + " · " + code,
                        workingCodes.contains(code));
                chip.setOnClickListener(v -> {
                    boolean selected = !chip.isSelected();
                    setKeycodeChipSelected(chip, selected);
                    if (selected) {
                        if (!workingCodes.contains(code)) {
                            workingCodes.add(code);
                        }
                    } else {
                        workingCodes.remove((Integer) code);
                    }
                    updateCount.run();
                });
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                if (i % columns != 0) {
                    chipParams.setMarginStart(dp(6));
                }
                chipRows.get(chipRows.size() - 1).addView(chip, chipParams);
                chips[i] = chip;
                searchTokens[i] = (name + " " + code)
                        .toLowerCase(java.util.Locale.ROOT);
            }
            int remainder = n % columns;
            if (remainder != 0) {
                LinearLayout lastRow = chipRows.get(chipRows.size() - 1);
                for (int i = remainder; i < columns; i++) {
                    View filler = new View(getContext());
                    filler.setVisibility(INVISIBLE);
                    LinearLayout.LayoutParams fillerParams = new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                    fillerParams.setMarginStart(dp(6));
                    lastRow.addView(filler, fillerParams);
                }
            }
            updateCount.run();

            clearText.setOnClickListener(v -> {
                if (workingCodes.isEmpty()) {
                    return;
                }
                workingCodes.clear();
                for (TextView chip : chips) {
                    setKeycodeChipSelected(chip, false);
                }
                updateCount.run();
            });

            searchInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(android.text.Editable editable) {
                    String query = editable.toString().trim()
                            .toLowerCase(java.util.Locale.ROOT);
                    for (int i = 0; i < chips.length; i++) {
                        chips[i].setVisibility(query.isEmpty()
                                || searchTokens[i].contains(query) ? VISIBLE : GONE);
                    }
                    for (LinearLayout row : chipRows) {
                        boolean visible = false;
                        for (int i = 0; i < row.getChildCount(); i++) {
                            View child = row.getChildAt(i);
                            if (child instanceof TextView && child.getVisibility() == VISIBLE) {
                                visible = true;
                                break;
                            }
                        }
                        row.setVisibility(visible ? VISIBLE : GONE);
                    }
                    listScroll.scrollTo(0, 0);
                }
            });

            LinearLayout bottom = new LinearLayout(getContext());
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            bottom.setPadding(0, dp(8), 0, 0);
            Button cancelBtn = ghostButton("取消");
            Button saveBtn = accentButton("应用");
            bottom.addView(cancelBtn);
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            saveParams.setMarginStart(dp(8));
            bottom.addView(saveBtn, saveParams);
            root.addView(divider());
            root.addView(bottom);

            Dialog dialog = createEditorDialog(root);
            cancelBtn.setOnClickListener(v -> dialog.dismiss());
            saveBtn.setOnClickListener(v -> {
                codes.clear();
                codes.addAll(workingCodes);
                after.run();
                dialog.dismiss();
            });
            // Do not force the software keyboard open as soon as the picker appears.
            root.setFocusableInTouchMode(true);
            root.requestFocus();
            showOverlayDialog(dialog);
        }

        private int parseIntClamped(String s, int min, int max, int def) {
            try {
                int v = Integer.parseInt(s.trim());
                return Math.max(min, Math.min(max, v));
            } catch (Exception e) {
                return def;
            }
        }

        private String joinCodes(List<Integer> codes) {
            StringBuilder sb = new StringBuilder();
            for (int c : codes) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(c);
            }
            return sb.length() == 0 ? "（无）" : sb.toString();
        }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (w <= 0 || h <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
            h = getResources().getDisplayMetrics().heightPixels;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int cw = childWidth(child, w, h);
            int ch = childHeight(child, w, h);
            if (cw <= 0 || ch <= 0) {
                // Non-control children (the management toolbar) measure
                // themselves from their own layout params / content.
                child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            } else {
                child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
            }
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            int x = 0;
            int y = 0;
            if (child instanceof FclButtonView) {
                FclController.BaseInfo base = ((FclButtonView) child).data.baseInfo;
                int[] pos = positionFor(((FclButtonView) child).data.id, base, w, h);
                x = pos[0] <= 0 ? 0 : (int) ((w - cw) * (pos[0] / 1000f));
                y = pos[1] <= 0 ? 0 : (int) ((h - ch) * (pos[1] / 1000f));
            } else if (child instanceof FclDirectionView) {
                FclController.BaseInfo base = ((FclDirectionView) child).data.baseInfo;
                int[] pos = positionFor(((FclDirectionView) child).data.id, base, w, h);
                x = pos[0] <= 0 ? 0 : (int) ((w - cw) * (pos[0] / 1000f));
                y = pos[1] <= 0 ? 0 : (int) ((h - ch) * (pos[1] / 1000f));
            }
            child.layout(x, y, x + cw, y + ch);
        }
    }

    private int[] positionFor(String id, FclController.BaseInfo base, int w, int h) {
        int[] p = pendingPositions.get(id);
        if (p == null) {
            p = savedPositions.get(id);
        }
        if (p != null) {
            return p;
        }
        return new int[]{base.xPosition, base.yPosition};
    }

    /** Record a drag result (in thousandths of the free area) while editing. */
    public void setPendingPosition(String id, int xThousandths, int yThousandths) {
        pendingPositions.put(id, new int[]{
                Math.max(0, Math.min(1000, xThousandths)),
                Math.max(0, Math.min(1000, yThousandths))});
    }

    private void loadPositions() {
        savedPositions.clear();
        if (controller == null) {
            return;
        }
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString("fcl_pos_" + controller.id, null);
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray ids = obj.names();
            if (ids == null) {
                return;
            }
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString(i);
                JSONArray arr = obj.optJSONArray(id);
                if (arr != null && arr.length() >= 2) {
                    savedPositions.put(id, new int[]{arr.optInt(0, 0), arr.optInt(1, 0)});
                }
            }
        } catch (JSONException ignored) {
            // Corrupt override data: fall back to the original layout.
        }
    }

    private void writePositions() {
        if (controller == null) {
            return;
        }
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, int[]> e : savedPositions.entrySet()) {
            try {
                obj.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString("fcl_pos_" + controller.id, obj.toString()).apply();
    }

    private int childWidth(View child, int w, int h) {
        if (child instanceof FclButtonView) {
            return ((FclButtonView) child).data.baseInfo.widthPx(w, h, density);
        }
        if (child instanceof FclDirectionView) {
            return ((FclDirectionView) child).data.baseInfo.widthPx(w, h, density);
        }
        return 0;
    }

    private int childHeight(View child, int w, int h) {
        if (child instanceof FclButtonView) {
            return ((FclButtonView) child).data.baseInfo.heightPx(w, h, density);
        }
        if (child instanceof FclDirectionView) {
            return ((FclDirectionView) child).data.baseInfo.widthPx(w, h, density);
        }
        return 0;
    }

    // ======================================================================
    // Button
    // ======================================================================

    private final class FclButtonView extends View {
        private static final int EVENT_PRESS = 0;
        private static final int EVENT_LONG_PRESS = 1;
        private static final int EVENT_CLICK = 2;
        private static final int EVENT_DOUBLE_CLICK = 3;

        private final FclController.Button data;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        // Movement dead zone for pointer-follow: a tap's micro-jitter must not
        // move the host cursor (it would drift the camera right before a click).
        private final float touchSlop;

        private boolean pressed = false;
        private boolean moved = false;
        private boolean longPressFired = false;
        // Set once the finger has clearly travelled past touchSlop; from then on
        // every MOVE is emitted, so slow drags stay smooth instead of chunking.
        private boolean pointerFollowActive = false;
        private float downX, downY;
        private long downTime;
        private int clickCount = 0;
        private long firstClickTime;

        private FclController.Event autoClickEvent;
        private boolean autoClickRunning = false;
        // Auto-keep (latch) state per event kind, matching FCL: the first press
        // latches the key and keeps the pressed (red) style; the next press
        // releases it and restores the normal style.
        private final boolean[] keepActive = new boolean[4];
        private final Runnable autoClickRunnable = new Runnable() {
            @Override
            public void run() {
                if (autoClickEvent == null) {
                    return;
                }
                keyDown(autoClickEvent);
                keyUp(autoClickEvent);
                if (autoClickRunning) {
                    handler.postDelayed(this, AUTO_CLICK_MS);
                }
            }
        };

        private final Runnable longPressRunnable = new Runnable() {
            @Override
            public void run() {
                longPressFired = true;
                trigger(data.longPressEvent, true, false, EVENT_LONG_PRESS);
                if (data.longPressEvent != null && data.longPressEvent.autoKeep
                        && !keepActive[EVENT_LONG_PRESS]) {
                    pressed = false;
                }
                invalidate();
            }
        };

        FclButtonView(Context context, FclController.Button data) {
            super(context);
            this.data = data;
            this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClickable(true);
            setWillNotDraw(false);
            strokePaint.setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            FclController.ButtonStyle s = data.style;
            float stroke = dp((pressed ? s.strokeWidthPressed : s.strokeWidth) / 10f);
            float corner = dp((pressed ? s.cornerRadiusPressed : s.cornerRadius) / 10f);

            rect.set(stroke, stroke, getWidth() - stroke, getHeight() - stroke);
            fillPaint.setColor(pressed ? s.fillColorPressed : s.fillColor);
            strokePaint.setColor(pressed ? s.strokeColorPressed : s.strokeColor);
            strokePaint.setStrokeWidth(stroke);
            canvas.drawRoundRect(rect, corner, corner, fillPaint);
            canvas.drawRoundRect(rect, corner, corner, strokePaint);

            if (editMode) {
                float eb = dp(2);
                rect.set(eb, eb, getWidth() - eb, getHeight() - eb);
                strokePaint.setColor(0xFFFF4444);
                strokePaint.setStrokeWidth(eb);
                canvas.drawRoundRect(rect, corner, corner, strokePaint);
            }

            String text = data.text;
            if (text == null || text.isEmpty()) {
                return;
            }
            textPaint.setColor(pressed ? s.textColorPressed : s.textColor);
            textPaint.setTextSize((pressed ? s.textSizePressed : s.textSize) * density);
            textPaint.setTextAlign(Paint.Align.CENTER);
            String[] lines = text.split("\n", -1);
            float lineHeight = textPaint.getFontSpacing();
            float totalHeight = lineHeight * lines.length;
            float y0 = (getHeight() - totalHeight) / 2f;
            for (int i = 0; i < lines.length; i++) {
                float baseline = y0 + lineHeight * (i + 0.5f)
                        - (textPaint.ascent() + textPaint.descent()) / 2f;
                canvas.drawText(lines[i], getWidth() / 2f, baseline, textPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (editMode) {
                return handleEditTouch(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    downTime = System.currentTimeMillis();
                    moved = false;
                    longPressFired = false;
                    pointerFollowActive = false;
                    trigger(data.pressEvent, true, false, EVENT_PRESS);
                    pressed = true;
                    // A second press toggles an auto-keep latch off; do not show
                    // the pressed style for that release tap.
                    if (data.pressEvent != null && data.pressEvent.autoKeep
                            && !keepActive[EVENT_PRESS]) {
                        pressed = false;
                    }
                    invalidate();
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (!pointerFollowActive
                            && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        // Confirmed drag: drop the pre-slop travel so the first
                        // emitted delta is small, then stream every frame.
                        pointerFollowActive = true;
                        moved = true;
                        handler.removeCallbacks(longPressRunnable);
                        downX = event.getX();
                        downY = event.getY();
                    }
                    if (pointerFollowActive && (data.pointerFollow || data.dragMoveMouse)) {
                        bridge.mouseMove((event.getX() - downX) * mouseSensitivity,
                                (event.getY() - downY) * mouseSensitivity);
                        downX = event.getX();
                        downY = event.getY();
                    }
                    if (data.movable) {
                        float nx = clamp(getX() + dx, 0, getParentWidth() - getWidth());
                        float ny = clamp(getY() + dy, 0, getParentHeight() - getHeight());
                        setX(nx);
                        setY(ny);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnable);
                    if (longPressFired) {
                        releaseEvent(data.longPressEvent, false, EVENT_LONG_PRESS);
                        longPressFired = false;
                    }
                    releaseEvent(data.pressEvent, false, EVENT_PRESS);

                    boolean tap = !moved && System.currentTimeMillis() - downTime <= 100;
                    if (tap) {
                        trigger(data.clickEvent, true, true, EVENT_CLICK);
                        clickCount++;
                        if (clickCount == 1) {
                            firstClickTime = System.currentTimeMillis();
                        } else if (clickCount == 2) {
                            if (System.currentTimeMillis() - firstClickTime < 400) {
                                trigger(data.doubleClickEvent, true, true, EVENT_DOUBLE_CLICK);
                            } else {
                                clickCount = 1;
                                firstClickTime = System.currentTimeMillis();
                            }
                            clickCount = 0;
                        }
                    }
                    pressed = anyKeepActive();
                    invalidate();
                    return true;
            }
            return true;
        }

        private boolean handleEditTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                        moved = true;
                    }
                    float nx = clamp(getX() + dx, 0, getParentWidth() - getWidth());
                    float ny = clamp(getY() + dy, 0, getParentHeight() - getHeight());
                    setX(nx);
                    setY(ny);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        int pw = getParentWidth();
                        int ph = getParentHeight();
                        int xTh = pw > getWidth()
                                ? Math.round(1000f * getX() / (pw - getWidth())) : 0;
                        int yTh = ph > getHeight()
                                ? Math.round(1000f * getY() / (ph - getHeight())) : 0;
                        setPendingPosition(data.id, xTh, yTh);
                    } else {
                        openPropertyDialog();
                    }
                    return true;
            }
            return true;
        }

        /** FCL-style editor: 信息/事件 tabs, four event types, keycode picker. */
        private void openPropertyDialog() {
            if (controller == null) {
                return;
            }
            final JSONObject btn = controller.findControlJson(data.id);
            if (btn == null) {
                return;
            }
            final String[] eventKeys = {"pressEvent", "longPressEvent",
                    "clickEvent", "doubleClickEvent"};
            final String[] eventTabs = {"按下", "长按", "单击", "双击"};
            final String[] flagNames = {"持续按住", "连点", "打开菜单",
                    "切换触摸模式", "切换鼠标模式", "输入", "快捷输入"};
            final String[] flagKeys = {"autoKeep", "autoClick", "openMenu",
                    "switchTouchMode", "switchMouseMode", "input", "quickInput"};

            String title = "编辑按键" + (data.text == null || data.text.isEmpty()
                    ? "" : " · " + data.text);
            LinearLayout root = createEditorCard(
                    title, "配置按键外观、位置与触发事件");

            // ---- FCL-style: left icon rail (信息/事件) + scrollable content ----
            LinearLayout body = new LinearLayout(getContext());
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.setPadding(0, dp(12), 0, dp(6));

            LinearLayout rail = new LinearLayout(getContext());
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            rail.setPadding(dp(4), dp(5), dp(4), dp(5));
            rail.setBackground(roundedDrawable(UI_SURFACE, dp(16)));
            final Button infoTab = railIcon("信息");
            final Button eventTab = railIcon("事件");
            rail.addView(infoTab);
            LinearLayout.LayoutParams eventRailParams = new LinearLayout.LayoutParams(
                    dp(62), dp(46));
            eventRailParams.topMargin = dp(6);
            eventTab.setLayoutParams(eventRailParams);
            rail.addView(eventTab);
            setRailSelected(infoTab, true);
            LinearLayout.LayoutParams railParams = new LinearLayout.LayoutParams(
                    dp(70), LinearLayout.LayoutParams.MATCH_PARENT);
            railParams.setMarginEnd(dp(12));
            body.addView(rail, railParams);

            final ScrollView scroll = new ScrollView(getContext());
            scroll.setFillViewport(true);
            scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            LinearLayout content = new LinearLayout(getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(1), 0, dp(3), dp(8));
            scroll.addView(content);
            body.addView(scroll, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            root.addView(body, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            // ---- info panel ----
            LinearLayout infoPanel = new LinearLayout(getContext());
            infoPanel.setOrientation(LinearLayout.VERTICAL);
            infoPanel.addView(sectionHeader("信息"));
            infoPanel.addView(divider());

            EditText textInput = fclEditText("按键文字");
            textInput.setText(data.text);
            infoPanel.addView(formRow(formLabel("文字"), textInput));

            FclToggle dragSwitch = fclSwitch("按住拖动移动鼠标");
            dragSwitch.setChecked(data.dragMoveMouse);
            infoPanel.addView(formRow(formLabel("按住拖动移动鼠标"), dragSwitch));

            EditText xInput = fclEditText("0-1000");
            xInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            xInput.setText(String.valueOf(data.baseInfo.xPosition));
            xInput.setWidth(dp(120));
            infoPanel.addView(formRow(formLabel("X位置"), xInput));

            EditText yInput = fclEditText("0-1000");
            yInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            yInput.setText(String.valueOf(data.baseInfo.yPosition));
            yInput.setWidth(dp(120));
            infoPanel.addView(formRow(formLabel("Y位置"), yInput));

            String[] sizeNames = {"百分比", "绝对dp"};
            Spinner sizeSpinner = fclSpinner(java.util.Arrays.asList(sizeNames));
            sizeSpinner.setSelection("ABSOLUTE".equals(data.baseInfo.sizeType) ? 1 : 0);
            infoPanel.addView(formRow(formLabel("尺寸类型"), sizeSpinner));

            String[] refNames = {"参照屏宽", "参照屏高"};
            Spinner refSpinner = fclSpinner(java.util.Arrays.asList(refNames));
            refSpinner.setSelection("SCREEN_WIDTH"
                    .equals(data.baseInfo.percentageWidth.reference) ? 0 : 1);
            infoPanel.addView(formRow(formLabel("参照"), refSpinner));

            EditText sizeInput = fclEditText("0-1000 或 dp");
            sizeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            int sizeVal = "ABSOLUTE".equals(data.baseInfo.sizeType)
                    ? data.baseInfo.absoluteWidth
                    : data.baseInfo.percentageWidth.size;
            sizeInput.setText(String.valueOf(sizeVal));
            sizeInput.setWidth(dp(120));
            infoPanel.addView(formRow(formLabel("大小"), sizeInput));

            List<String> styleNames = new ArrayList<>(controller.buttonStylesByName.keySet());
            if (styleNames.isEmpty()) {
                styleNames.add("Default");
            }
            Spinner styleSpinner = fclSpinner(styleNames);
            int styleIdx = styleNames.indexOf(btn.optString("style", "Default"));
            styleSpinner.setSelection(styleIdx < 0 ? 0 : styleIdx);
            infoPanel.addView(formRow(formLabel("样式"), styleSpinner));

            // ---- event panel ----
            LinearLayout eventPanel = new LinearLayout(getContext());
            eventPanel.setOrientation(LinearLayout.VERTICAL);
            eventPanel.addView(sectionHeader("事件"));
            eventPanel.addView(divider());

            FclToggle pointerSwitch = fclSwitch("指针跟随");
            pointerSwitch.setChecked(data.pointerFollow);
            eventPanel.addView(formRow(formLabel("指针跟随"), pointerSwitch));

            FclToggle movableSwitch = fclSwitch("可移动");
            movableSwitch.setChecked(data.movable);
            eventPanel.addView(formRow(formLabel("可移动"), movableSwitch));

            final List<List<Integer>> evCodes = new ArrayList<>();
            final String[] evText = new String[4];
            final boolean[][] evFlags = new boolean[4][7];
            for (int i = 0; i < 4; i++) {
                JSONObject e = btn.optJSONObject("event") != null
                        ? btn.optJSONObject("event").optJSONObject(eventKeys[i]) : null;
                evText[i] = e != null ? e.optString("outputText", "") : "";
                List<Integer> codes = new ArrayList<>();
                JSONArray arr = e != null ? e.optJSONArray("outputKeycodes") : null;
                if (arr != null) {
                    for (int k = 0; k < arr.length(); k++) {
                        codes.add(arr.optInt(k, 0));
                    }
                }
                evCodes.add(codes);
                for (int f = 0; f < 7; f++) {
                    evFlags[i][f] = e != null && e.optBoolean(flagKeys[f], false);
                }
            }

            LinearLayout eventTabBar = new LinearLayout(getContext());
            eventTabBar.setOrientation(LinearLayout.HORIZONTAL);
            eventTabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
            eventTabBar.setBackground(roundedDrawable(UI_SURFACE, dp(14)));
            final Button[] eventTabButtons = new Button[4];
            for (int i = 0; i < 4; i++) {
                eventTabButtons[i] = tabButton(eventTabs[i], i == 0);
                LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                        0, dp(42), 1f);
                if (i < 3) {
                    tabParams.setMarginEnd(dp(4));
                }
                eventTabBar.addView(eventTabButtons[i], tabParams);
            }
            LinearLayout.LayoutParams tabBarParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tabBarParams.setMargins(0, dp(4), 0, dp(10));
            eventPanel.addView(eventTabBar, tabBarParams);

            final LinearLayout eventBody = new LinearLayout(getContext());
            eventBody.setOrientation(LinearLayout.VERTICAL);
            eventPanel.addView(eventBody);

            final View[] eventBodies = new View[4];
            final FclToggle[][] evSwitches = new FclToggle[4][7];
            final EditText[] evTextInputs = new EditText[4];
            final Button[] evCodeButtons = new Button[4];
            for (int i = 0; i < 4; i++) {
                final int fi = i;
                LinearLayout bodyPanel = new LinearLayout(getContext());
                bodyPanel.setOrientation(LinearLayout.VERTICAL);
                for (int f = 0; f < 7; f++) {
                    evSwitches[i][f] = fclSwitch(flagNames[f]);
                    evSwitches[i][f].setChecked(evFlags[i][f]);
                    bodyPanel.addView(formRow(formLabel(flagNames[f]), evSwitches[i][f]));
                }
                evTextInputs[i] = fclEditText("输出文本");
                evTextInputs[i].setText(evText[i]);
                bodyPanel.addView(formRow(formLabel("输出文本"), evTextInputs[i]));
                evCodeButtons[i] = ghostButton(joinCodes(evCodes.get(i)) + "  ›");
                evCodeButtons[i].setSingleLine(true);
                evCodeButtons[i].setEllipsize(android.text.TextUtils.TruncateAt.END);
                evCodeButtons[i].setMaxWidth(dp(210));
                evCodeButtons[i].setOnClickListener(v -> openKeycodePicker(
                        evCodes.get(fi),
                        () -> evCodeButtons[fi].setText(
                                joinCodes(evCodes.get(fi)) + "  ›")));
                bodyPanel.addView(formRow(formLabel("键码"), evCodeButtons[i]));
                eventBodies[i] = bodyPanel;
            }
            eventBody.addView(eventBodies[0]);
            for (int i = 0; i < 4; i++) {
                final int fi = i;
                eventTabButtons[i].setOnClickListener(v -> {
                    eventBody.removeAllViews();
                    eventBody.addView(eventBodies[fi]);
                    for (int k = 0; k < 4; k++) {
                        setTabSelected(eventTabButtons[k], k == fi);
                    }
                });
            }

            content.addView(infoPanel);
            content.addView(eventPanel);
            eventPanel.setVisibility(GONE);
            infoTab.setOnClickListener(v -> {
                infoPanel.setVisibility(VISIBLE);
                eventPanel.setVisibility(GONE);
                setRailSelected(infoTab, true);
                setRailSelected(eventTab, false);
                scroll.scrollTo(0, 0);
            });
            eventTab.setOnClickListener(v -> {
                infoPanel.setVisibility(GONE);
                eventPanel.setVisibility(VISIBLE);
                setRailSelected(eventTab, true);
                setRailSelected(infoTab, false);
                scroll.scrollTo(0, 0);
            });

            // ---- bottom actions: FCL layout 克隆/删除 left, 取消/确定 right ----
            LinearLayout bottom = new LinearLayout(getContext());
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setPadding(0, dp(12), 0, 0);
            Button cloneBtn = ghostButton("克隆");
            Button delBtn = dangerButton("删除");
            Button cancelBtn = ghostButton("取消");
            Button okBtn = accentButton("保存");
            bottom.addView(cloneBtn);
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            deleteParams.setMarginStart(dp(8));
            bottom.addView(delBtn, deleteParams);
            View spacer = new View(getContext());
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
            bottom.addView(spacer);
            bottom.addView(cancelBtn);
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            saveParams.setMarginStart(dp(8));
            bottom.addView(okBtn, saveParams);
            root.addView(divider());
            root.addView(bottom);

            Dialog dialog = createEditorDialog(root);
            okBtn.setOnClickListener(v -> {
                for (int i = 0; i < 4; i++) {
                    evText[i] = evTextInputs[i].getText().toString();
                    for (int f = 0; f < 7; f++) {
                        evFlags[i][f] = evSwitches[i][f].isChecked();
                    }
                }
                savePropertyJson(
                        textInput.getText().toString(),
                        xInput.getText().toString(),
                        yInput.getText().toString(),
                        sizeSpinner.getSelectedItemPosition(),
                        refSpinner.getSelectedItemPosition(),
                        sizeInput.getText().toString(),
                        styleSpinner.getSelectedItem().toString(),
                        pointerSwitch.isChecked(),
                        dragSwitch.isChecked(),
                        movableSwitch.isChecked(),
                        evFlags, evText, evCodes);
                dialog.dismiss();
            });
            cancelBtn.setOnClickListener(v -> dialog.dismiss());
            cloneBtn.setOnClickListener(v -> {
                cloneButtonJson(btn);
                dialog.dismiss();
            });
            delBtn.setOnClickListener(v -> {
                deleteButtonJson(btn);
                dialog.dismiss();
            });
            showOverlayDialog(dialog);
        }

        private void savePropertyJson(String text, String xs, String ys,
                                      int sizeIdx, int refIdx, String sizeVal,
                                      String style, boolean pointerFollow,
                                      boolean dragMove, boolean movable,
                                      boolean[][] flags, String[] texts,
                                      List<List<Integer>> codes) {
            JSONObject btn = controller.findControlJson(data.id);
            if (btn == null) {
                return;
            }
            try {
                JSONObject base = btn.optJSONObject("baseInfo");
                if (base == null) {
                    base = new JSONObject();
                    btn.put("baseInfo", base);
                }
                JSONObject ev = btn.optJSONObject("event");
                if (ev == null) {
                    ev = new JSONObject();
                    btn.put("event", ev);
                }
                btn.put("text", text);
                btn.put("style", style);
                // Our port has no cursor-mode concept, so controls are always shown.
                base.put("visibilityType", "ALWAYS");
                base.put("xPosition", parseIntClamped(xs, 0, 1000, data.baseInfo.xPosition));
                base.put("yPosition", parseIntClamped(ys, 0, 1000, data.baseInfo.yPosition));
                boolean abs = sizeIdx == 1;
                base.put("sizeType", abs ? "ABSOLUTE" : "PERCENTAGE");
                String reference = refIdx == 0 ? "SCREEN_WIDTH" : "SCREEN_HEIGHT";
                int size = parseIntClamped(sizeVal, 0, abs ? 2000 : 1000, 120);
                if (abs) {
                    base.put("absoluteWidth", size);
                    base.put("absoluteHeight", size);
                } else {
                    base.put("percentageWidth", new JSONObject()
                            .put("reference", reference).put("size", size));
                    base.put("percentageHeight", new JSONObject()
                            .put("reference", reference).put("size", size));
                }
                ev.put("pointerFollow", pointerFollow);
                ev.put("dragMoveMouse", dragMove);
                ev.put("Movable", movable);
                final String[] eventKeys = {"pressEvent", "longPressEvent",
                        "clickEvent", "doubleClickEvent"};
                final String[] flagKeys = {"autoKeep", "autoClick", "openMenu",
                        "switchTouchMode", "switchMouseMode", "input", "quickInput"};
                for (int i = 0; i < 4; i++) {
                    JSONObject e = new JSONObject();
                    for (int f = 0; f < 7; f++) {
                        e.put(flagKeys[f], flags[i][f]);
                    }
                    e.put("outputText", texts[i]);
                    JSONArray arr = new JSONArray();
                    for (int c : codes.get(i)) {
                        arr.put(c);
                    }
                    e.put("outputKeycodes", arr);
                    ev.put(eventKeys[i], e);
                }
                btn.put("event", ev);
                applyPendingPositionsToJson();
                if (controller.saveToFile(getContext())) {
                    reloadController();
                } else {
                    Toast.makeText(getContext(), "保存失败", Toast.LENGTH_SHORT).show();
                }
            } catch (JSONException e) {
                Toast.makeText(getContext(), "保存失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }


        private void cloneButtonJson(JSONObject btn) {
            try {
                JSONObject copy = new JSONObject(btn.toString());
                copy.put("id", String.format(java.util.Locale.US, "%08x",
                        new java.util.Random().nextInt(0x10000000)));
                JSONObject group = groupJsonForControl(data.id);
                if (group == null) {
                    return;
                }
                JSONObject vd = group.optJSONObject("viewData");
                if (vd == null) {
                    vd = new JSONObject();
                    group.put("viewData", vd);
                }
                JSONArray bl = vd.optJSONArray("buttonList");
                if (bl == null) {
                    bl = new JSONArray();
                    vd.put("buttonList", bl);
                }
                bl.put(copy);
                if (controller.saveToFile(getContext())) {
                    reloadController();
                }
            } catch (JSONException e) {
                Toast.makeText(getContext(), "克隆失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void deleteButtonJson(JSONObject btn) {
            JSONObject group = groupJsonForControl(data.id);
            if (group == null) {
                return;
            }
            JSONObject vd = group.optJSONObject("viewData");
            JSONArray bl = vd != null ? vd.optJSONArray("buttonList") : null;
            if (bl == null) {
                return;
            }
            for (int i = 0; i < bl.length(); i++) {
                JSONObject b = bl.optJSONObject(i);
                if (b != null && data.id.equals(b.optString("id"))) {
                    bl.remove(i);
                    break;
                }
            }
            if (controller.saveToFile(getContext())) {
                reloadController();
            }
        }



        private void trigger(FclController.Event ev, boolean enable, boolean clickType,
                             int eventType) {
            if (ev == null || !enable) {
                return;
            }
            if (ev.autoKeep) {
                if (keepActive[eventType]) {
                    // Already latched: this press releases it again (toggle off).
                    keepActive[eventType] = false;
                    if (ev.autoClick) {
                        stopAutoClick();
                    } else {
                        keyUp(ev);
                    }
                } else {
                    keepActive[eventType] = true;
                    if (ev.autoClick) {
                        startAutoClick(ev);
                    } else {
                        keyDown(ev);
                    }
                }
            } else if (ev.autoClick) {
                startAutoClick(ev);
            } else if (clickType) {
                keyDown(ev);
                keyUp(ev);
            } else {
                keyDown(ev);
            }
            sideEffects(ev);
        }

        private void releaseEvent(FclController.Event ev, boolean force, int eventType) {
            if (ev == null) {
                return;
            }
            if (force || !ev.autoKeep) {
                if (ev.autoClick) {
                    stopAutoClick();
                }
                // For a latched event only send the release when it is actually
                // held; otherwise a force-cleanup could lift a key that another
                // control is still pressing.
                if (!ev.autoKeep || keepActive[eventType]) {
                    keyUp(ev);
                }
            }
            if (force) {
                keepActive[eventType] = false;
            }
        }

        private boolean anyKeepActive() {
            return keepActive[EVENT_PRESS] || keepActive[EVENT_LONG_PRESS]
                    || keepActive[EVENT_CLICK] || keepActive[EVENT_DOUBLE_CLICK];
        }

        private void sideEffects(FclController.Event ev) {
            if (ev.openMenu) {
                bridge.openSettings();
            }
            if (ev.input || ev.quickInput) {
                bridge.toggleIme();
            }
            if (ev.outputText != null && !ev.outputText.isEmpty()) {
                bridge.text(ev.outputText);
            }
            for (String groupId : ev.bindViewGroup) {
                toggleGroup(groupId);
            }
        }

        private void startAutoClick(FclController.Event ev) {
            if (autoClickRunning) {
                return;
            }
            autoClickEvent = ev;
            autoClickRunning = true;
            handler.post(autoClickRunnable);
        }

        private void stopAutoClick() {
            autoClickRunning = false;
            handler.removeCallbacks(autoClickRunnable);
        }

        private void keyDown(FclController.Event ev) {
            for (int code : ev.outputKeycodes) {
                sendFclKey(code, 0);
            }
        }

        private void keyUp(FclController.Event ev) {
            for (int code : ev.outputKeycodes) {
                sendFclKey(code, 1);
            }
        }

        void releaseAll() {
            handler.removeCallbacks(longPressRunnable);
            stopAutoClick();
            if (longPressFired) {
                releaseEvent(data.longPressEvent, true, EVENT_LONG_PRESS);
                longPressFired = false;
            }
            releaseEvent(data.pressEvent, true, EVENT_PRESS);
            releaseEvent(data.clickEvent, true, EVENT_CLICK);
            releaseEvent(data.doubleClickEvent, true, EVENT_DOUBLE_CLICK);
            pressed = false;
            clickCount = 0;
            invalidate();
        }

        private int getParentWidth() {
            return FclControllerView.this.getWidth();
        }

        private int getParentHeight() {
            return FclControllerView.this.getHeight();
        }
    }

    // ======================================================================
    // Direction pad / rocker
    // ======================================================================

    private final class FclDirectionView extends View {
        private final FclController.Direction data;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private int rockerSize;
        private int maxDistance;
        private float rockerOffsetX;
        private float rockerOffsetY;

        private boolean dirUp, dirDown, dirLeft, dirRight;
        private boolean sneakActive = false;
        private boolean startClick = false;
        private boolean moved = false;
        private float downX, downY;
        private long downTime;
        private int clickCount = 0;
        private long firstClickTime;

        FclDirectionView(Context context, FclController.Direction data) {
            super(context);
            this.data = data;
            setClickable(true);
            setWillNotDraw(false);
            strokePaint.setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if ("ROCKER".equals(data.style.styleType) && data.style.rockerStyle != null) {
                rockerSize = w * data.style.rockerStyle.rockerSize / 1000;
                maxDistance = Math.max(0, w / 2 - rockerSize / 2);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if ("BUTTON".equals(data.style.styleType)) {
                drawPad(canvas);
            } else {
                drawRocker(canvas);
            }
            if (editMode) {
                float eb = dp(2);
                rect.set(eb, eb, getWidth() - eb, getHeight() - eb);
                strokePaint.setColor(0xFFFF4444);
                strokePaint.setStrokeWidth(eb);
                canvas.drawRoundRect(rect, eb, eb, strokePaint);
            }
        }

        private void drawRocker(Canvas canvas) {
            FclController.RockerStyle rs = data.style.rockerStyle;
            if (rs == null) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            float bgStroke = dp(rs.bgStrokeWidth / 10f);
            float bgCorner = w * rs.bgCornerRadius / 1000f;
            rect.set(bgStroke, bgStroke, w - bgStroke, h - bgStroke);
            fillPaint.setColor(rs.bgFillColor);
            strokePaint.setColor(rs.bgStrokeColor);
            strokePaint.setStrokeWidth(bgStroke);
            canvas.drawRoundRect(rect, bgCorner, bgCorner, fillPaint);
            canvas.drawRoundRect(rect, bgCorner, bgCorner, strokePaint);

            if (rockerSize <= 0) {
                rockerSize = w * rs.rockerSize / 1000;
                maxDistance = Math.max(0, w / 2 - rockerSize / 2);
            }
            float cx = w / 2f + rockerOffsetX;
            float cy = h / 2f + rockerOffsetY;
            float rStroke = dp(rs.rockerStrokeWidth / 10f);
            float rCorner = rockerSize * rs.rockerCornerRadius / 1000f;
            rect.set(cx - rockerSize / 2f, cy - rockerSize / 2f,
                    cx + rockerSize / 2f, cy + rockerSize / 2f);
            fillPaint.setColor(rs.rockerFillColor);
            strokePaint.setColor(rs.rockerStrokeColor);
            strokePaint.setStrokeWidth(rStroke);
            canvas.drawRoundRect(rect, rCorner, rCorner, fillPaint);
            canvas.drawRoundRect(rect, rCorner, rCorner, strokePaint);
        }

        private void drawPad(Canvas canvas) {
            FclController.ButtonStyle bs = data.style.buttonStyle;
            if (bs == null) {
                return;
            }
            int w = getWidth();
            int size = w * (1000 - 2 * (int) bs.strokeWidth) / 3000;
            int p0 = 0;
            int p1 = size + w * (int) bs.strokeWidth / 1000;
            int p2 = w - size;
            drawPadKey(canvas, bs, p1, p0, size, "▲", dirUp);
            drawPadKey(canvas, bs, p0, p1, size, "◀", dirLeft);
            drawPadKey(canvas, bs, p1, p1, size, "◆", !dirUp && !dirDown && !dirLeft && !dirRight);
            drawPadKey(canvas, bs, p2, p1, size, "▶", dirRight);
            drawPadKey(canvas, bs, p1, p2, size, "▼", dirDown);
        }

        private void drawPadKey(Canvas canvas, FclController.ButtonStyle bs,
                                int x, int y, int size, String text, boolean active) {
            float stroke = dp((active ? bs.strokeWidthPressed : bs.strokeWidth) / 10f);
            float corner = dp((active ? bs.cornerRadiusPressed : bs.cornerRadius) / 10f);
            rect.set(x + stroke, y + stroke, x + size - stroke, y + size - stroke);
            fillPaint.setColor(active ? bs.fillColorPressed : bs.fillColor);
            strokePaint.setColor(active ? bs.strokeColorPressed : bs.strokeColor);
            strokePaint.setStrokeWidth(stroke);
            canvas.drawRoundRect(rect, corner, corner, fillPaint);
            canvas.drawRoundRect(rect, corner, corner, strokePaint);
            textPaint.setColor(active ? bs.textColorPressed : bs.textColor);
            textPaint.setTextSize((active ? bs.textSizePressed : bs.textSize) * density);
            canvas.drawText(text, x + size / 2f, y + size / 2f
                    - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (editMode) {
                return handleEditTouch(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    downTime = System.currentTimeMillis();
                    moved = false;
                    startClick = false;
                    if ("ROCKER".equals(data.style.styleType)
                            && ("FOLLOW".equals(data.followOption)
                            || ("CENTER_FOLLOW".equals(data.followOption)
                            && insideRocker(event.getX(), event.getY())))) {
                        startClick = true;
                    }
                    handlePadEvent(event.getX(), event.getY());
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > 10
                            || Math.abs(event.getY() - downY) > 10) {
                        moved = true;
                        startClick = false;
                    }
                    handlePadEvent(event.getX(), event.getY());
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean tap = !moved && System.currentTimeMillis() - downTime <= 100;
                    if (tap && startClick && data.sneak) {
                        clickCount++;
                        if (clickCount == 1) {
                            firstClickTime = System.currentTimeMillis();
                        } else if (clickCount == 2) {
                            if (System.currentTimeMillis() - firstClickTime < 400) {
                                toggleSneak();
                            }
                            clickCount = 0;
                        }
                    }
                    setDirs(false, false, false, false);
                    rockerOffsetX = 0;
                    rockerOffsetY = 0;
                    invalidate();
                    return true;
            }
            return true;
        }

        private boolean handleEditTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                        moved = true;
                    }
                    float nx = clamp(getX() + dx, 0,
                            FclControllerView.this.getWidth() - getWidth());
                    float ny = clamp(getY() + dy, 0,
                            FclControllerView.this.getHeight() - getHeight());
                    setX(nx);
                    setY(ny);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        int pw = FclControllerView.this.getWidth();
                        int ph = FclControllerView.this.getHeight();
                        int xTh = pw > getWidth()
                                ? Math.round(1000f * getX() / (pw - getWidth())) : 0;
                        int yTh = ph > getHeight()
                                ? Math.round(1000f * getY() / (ph - getHeight())) : 0;
                        setPendingPosition(data.id, xTh, yTh);
                    } else {
                        openDirectionDialog();
                    }
                    return true;
            }
            return true;
        }

        /** FCL-style direction editor: position/size/style + direction keycodes. */
        private void openDirectionDialog() {
            if (controller == null) {
                return;
            }
            final JSONObject dir = controller.findControlJson(data.id);
            if (dir == null) {
                return;
            }
            LinearLayout root = createEditorCard(
                    "编辑方向控件", "配置位置、摇杆行为与方向键位");

            LinearLayout body = new LinearLayout(getContext());
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.setPadding(0, dp(12), 0, dp(6));

            LinearLayout rail = new LinearLayout(getContext());
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            rail.setPadding(dp(4), dp(5), dp(4), dp(5));
            rail.setBackground(roundedDrawable(UI_SURFACE, dp(16)));
            final Button infoTab = railIcon("信息");
            final Button keyTab = railIcon("键位");
            rail.addView(infoTab);
            LinearLayout.LayoutParams keyRailParams = new LinearLayout.LayoutParams(
                    dp(62), dp(46));
            keyRailParams.topMargin = dp(6);
            keyTab.setLayoutParams(keyRailParams);
            rail.addView(keyTab);
            setRailSelected(infoTab, true);
            LinearLayout.LayoutParams railParams = new LinearLayout.LayoutParams(
                    dp(70), LinearLayout.LayoutParams.MATCH_PARENT);
            railParams.setMarginEnd(dp(12));
            body.addView(rail, railParams);

            final ScrollView scroll = new ScrollView(getContext());
            scroll.setFillViewport(true);
            scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            LinearLayout content = new LinearLayout(getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(1), 0, dp(3), dp(8));
            scroll.addView(content);
            body.addView(scroll, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            root.addView(body, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            LinearLayout infoPanel = new LinearLayout(getContext());
            infoPanel.setOrientation(LinearLayout.VERTICAL);
            infoPanel.addView(sectionHeader("位置与外观"));
            infoPanel.addView(divider());

            EditText xInput = fclEditText("0-1000");
            xInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            xInput.setText(String.valueOf(data.baseInfo.xPosition));
            infoPanel.addView(formRow(formLabel("X 位置"), xInput));

            EditText yInput = fclEditText("0-1000");
            yInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            yInput.setText(String.valueOf(data.baseInfo.yPosition));
            infoPanel.addView(formRow(formLabel("Y 位置"), yInput));

            String[] sizeNames = {"百分比", "绝对dp"};
            Spinner sizeSpinner = fclSpinner(java.util.Arrays.asList(sizeNames));
            sizeSpinner.setSelection("ABSOLUTE".equals(data.baseInfo.sizeType) ? 1 : 0);
            infoPanel.addView(formRow(formLabel("尺寸类型"), sizeSpinner));

            String[] refNames = {"参照屏宽", "参照屏高"};
            Spinner refSpinner = fclSpinner(java.util.Arrays.asList(refNames));
            refSpinner.setSelection("SCREEN_WIDTH"
                    .equals(data.baseInfo.percentageWidth.reference) ? 0 : 1);
            infoPanel.addView(formRow(formLabel("尺寸参照"), refSpinner));

            EditText sizeInput = fclEditText("0-1000 或 dp");
            sizeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            int sizeVal = "ABSOLUTE".equals(data.baseInfo.sizeType)
                    ? data.baseInfo.absoluteWidth
                    : data.baseInfo.percentageWidth.size;
            sizeInput.setText(String.valueOf(sizeVal));
            infoPanel.addView(formRow(formLabel("尺寸"), sizeInput));

            List<String> styleNames = new ArrayList<>(controller.directionStylesByName.keySet());
            if (styleNames.isEmpty()) {
                styleNames.add("Default");
            }
            Spinner styleSpinner = fclSpinner(styleNames);
            int styleIdx = styleNames.indexOf(dir.optString("style", "Default"));
            styleSpinner.setSelection(styleIdx < 0 ? 0 : styleIdx);
            infoPanel.addView(formRow(formLabel("样式"), styleSpinner));

            String[] followNames = {"固定", "中心跟随", "跟随"};
            Spinner followSpinner = fclSpinner(java.util.Arrays.asList(followNames));
            String follow = data.followOption;
            followSpinner.setSelection("FIXED".equals(follow) ? 0
                    : ("CENTER_FOLLOW".equals(follow) ? 1 : 2));
            infoPanel.addView(formRow(formLabel("跟随方式"), followSpinner));

            final List<Integer> upCodes = new ArrayList<>();
            final List<Integer> downCodes = new ArrayList<>();
            final List<Integer> leftCodes = new ArrayList<>();
            final List<Integer> rightCodes = new ArrayList<>();
            final List<Integer> sneakCodes = new ArrayList<>();
            addAll(upCodes, data.upKeycodes);
            addAll(downCodes, data.downKeycodes);
            addAll(leftCodes, data.leftKeycodes);
            addAll(rightCodes, data.rightKeycodes);
            sneakCodes.add(data.sneakKeycode);

            LinearLayout keyPanel = new LinearLayout(getContext());
            keyPanel.setOrientation(LinearLayout.VERTICAL);
            keyPanel.addView(sectionHeader("方向键位"));
            keyPanel.addView(divider());

            Button upBtn = ghostButton(joinCodes(upCodes) + "  ›");
            upBtn.setSingleLine(true);
            upBtn.setMaxWidth(dp(210));
            upBtn.setOnClickListener(v -> openKeycodePicker(upCodes,
                    () -> upBtn.setText(joinCodes(upCodes) + "  ›")));
            keyPanel.addView(formRow(formLabel("向上"), upBtn));

            Button downBtn = ghostButton(joinCodes(downCodes) + "  ›");
            downBtn.setSingleLine(true);
            downBtn.setMaxWidth(dp(210));
            downBtn.setOnClickListener(v -> openKeycodePicker(downCodes,
                    () -> downBtn.setText(joinCodes(downCodes) + "  ›")));
            keyPanel.addView(formRow(formLabel("向下"), downBtn));

            Button leftBtn = ghostButton(joinCodes(leftCodes) + "  ›");
            leftBtn.setSingleLine(true);
            leftBtn.setMaxWidth(dp(210));
            leftBtn.setOnClickListener(v -> openKeycodePicker(leftCodes,
                    () -> leftBtn.setText(joinCodes(leftCodes) + "  ›")));
            keyPanel.addView(formRow(formLabel("向左"), leftBtn));

            Button rightBtn = ghostButton(joinCodes(rightCodes) + "  ›");
            rightBtn.setSingleLine(true);
            rightBtn.setMaxWidth(dp(210));
            rightBtn.setOnClickListener(v -> openKeycodePicker(rightCodes,
                    () -> rightBtn.setText(joinCodes(rightCodes) + "  ›")));
            keyPanel.addView(formRow(formLabel("向右"), rightBtn));

            FclToggle sneakSwitch = fclSwitch("双击潜行");
            sneakSwitch.setChecked(data.sneak);
            keyPanel.addView(formRow(formLabel("双击潜行"), sneakSwitch));

            Button sneakKeyBtn = ghostButton(joinCodes(sneakCodes) + "  ›");
            sneakKeyBtn.setSingleLine(true);
            sneakKeyBtn.setMaxWidth(dp(210));
            sneakKeyBtn.setOnClickListener(v -> openKeycodePicker(sneakCodes,
                    () -> sneakKeyBtn.setText(joinCodes(sneakCodes) + "  ›")));
            keyPanel.addView(formRow(formLabel("潜行键码"), sneakKeyBtn));

            content.addView(infoPanel);
            content.addView(keyPanel);
            keyPanel.setVisibility(GONE);
            infoTab.setOnClickListener(v -> {
                infoPanel.setVisibility(VISIBLE);
                keyPanel.setVisibility(GONE);
                setRailSelected(infoTab, true);
                setRailSelected(keyTab, false);
                scroll.scrollTo(0, 0);
            });
            keyTab.setOnClickListener(v -> {
                infoPanel.setVisibility(GONE);
                keyPanel.setVisibility(VISIBLE);
                setRailSelected(keyTab, true);
                setRailSelected(infoTab, false);
                scroll.scrollTo(0, 0);
            });

            LinearLayout bottom = new LinearLayout(getContext());
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setPadding(0, dp(12), 0, 0);
            Button cloneBtn = ghostButton("克隆");
            Button delBtn = dangerButton("删除");
            Button cancelBtn = ghostButton("取消");
            Button okBtn = accentButton("保存");
            bottom.addView(cloneBtn);
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            deleteParams.setMarginStart(dp(8));
            bottom.addView(delBtn, deleteParams);
            View spacer = new View(getContext());
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
            bottom.addView(spacer);
            bottom.addView(cancelBtn);
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            saveParams.setMarginStart(dp(8));
            bottom.addView(okBtn, saveParams);
            root.addView(divider());
            root.addView(bottom);

            Dialog dialog = createEditorDialog(root);
            okBtn.setOnClickListener(v -> {
                saveDirectionJson(
                        xInput.getText().toString(),
                        yInput.getText().toString(),
                        sizeSpinner.getSelectedItemPosition(),
                        refSpinner.getSelectedItemPosition(),
                        sizeInput.getText().toString(),
                        styleSpinner.getSelectedItem().toString(),
                        followSpinner.getSelectedItemPosition(),
                        upCodes, downCodes, leftCodes, rightCodes,
                        sneakSwitch.isChecked(), sneakCodes);
                dialog.dismiss();
            });
            cancelBtn.setOnClickListener(v -> dialog.dismiss());
            cloneBtn.setOnClickListener(v -> {
                cloneDirectionJson(dir);
                dialog.dismiss();
            });
            delBtn.setOnClickListener(v -> {
                deleteDirectionJson(dir);
                dialog.dismiss();
            });
            showOverlayDialog(dialog);
        }

        private void saveDirectionJson(String xs, String ys, int sizeIdx, int refIdx,
                                       String sizeVal, String style, int followIdx,
                                       List<Integer> up, List<Integer> down,
                                       List<Integer> left, List<Integer> right,
                                       boolean sneak, List<Integer> sneakCodes) {
            JSONObject dir = controller.findControlJson(data.id);
            if (dir == null) {
                return;
            }
            try {
                JSONObject base = dir.optJSONObject("baseInfo");
                if (base == null) {
                    base = new JSONObject();
                    dir.put("baseInfo", base);
                }
                JSONObject ev = dir.optJSONObject("event");
                if (ev == null) {
                    ev = new JSONObject();
                    dir.put("event", ev);
                }
                dir.put("style", style);
                base.put("visibilityType", "ALWAYS");
                base.put("xPosition", parseIntClamped(xs, 0, 1000, data.baseInfo.xPosition));
                base.put("yPosition", parseIntClamped(ys, 0, 1000, data.baseInfo.yPosition));
                boolean abs = sizeIdx == 1;
                base.put("sizeType", abs ? "ABSOLUTE" : "PERCENTAGE");
                String reference = refIdx == 0 ? "SCREEN_WIDTH" : "SCREEN_HEIGHT";
                int size = parseIntClamped(sizeVal, 0, abs ? 2000 : 1000, 450);
                if (abs) {
                    base.put("absoluteWidth", size);
                    base.put("absoluteHeight", size);
                } else {
                    base.put("percentageWidth", new JSONObject()
                            .put("reference", reference).put("size", size));
                    base.put("percentageHeight", new JSONObject()
                            .put("reference", reference).put("size", size));
                }
                ev.put("upKeycode", toKeycodeArray(up));
                ev.put("downKeycode", toKeycodeArray(down));
                ev.put("leftKeycode", toKeycodeArray(left));
                ev.put("rightKeycode", toKeycodeArray(right));
                ev.put("followOption", followIdx == 0 ? "FIXED"
                        : (followIdx == 1 ? "CENTER_FOLLOW" : "FOLLOW"));
                ev.put("sneak", sneak);
                ev.put("sneakKeycode", sneakCodes.isEmpty() ? 42 : sneakCodes.get(0));
                dir.put("event", ev);
                applyPendingPositionsToJson();
                if (controller.saveToFile(getContext())) {
                    reloadController();
                } else {
                    Toast.makeText(getContext(), "保存失败", Toast.LENGTH_SHORT).show();
                }
            } catch (JSONException e) {
                Toast.makeText(getContext(), "保存失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void cloneDirectionJson(JSONObject dir) {
            try {
                JSONObject copy = new JSONObject(dir.toString());
                copy.put("id", String.format(java.util.Locale.US, "%08x",
                        new java.util.Random().nextInt(0x10000000)));
                JSONObject group = groupJsonForControl(data.id);
                if (group == null) {
                    return;
                }
                JSONObject vd = group.optJSONObject("viewData");
                if (vd == null) {
                    vd = new JSONObject();
                    group.put("viewData", vd);
                }
                JSONArray dl = vd.optJSONArray("directionList");
                if (dl == null) {
                    dl = new JSONArray();
                    vd.put("directionList", dl);
                }
                dl.put(copy);
                if (controller.saveToFile(getContext())) {
                    reloadController();
                }
            } catch (JSONException e) {
                Toast.makeText(getContext(), "克隆失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void deleteDirectionJson(JSONObject dir) {
            JSONObject group = groupJsonForControl(data.id);
            if (group == null) {
                return;
            }
            JSONObject vd = group.optJSONObject("viewData");
            JSONArray dl = vd != null ? vd.optJSONArray("directionList") : null;
            if (dl == null) {
                return;
            }
            for (int i = 0; i < dl.length(); i++) {
                JSONObject d = dl.optJSONObject(i);
                if (d != null && data.id.equals(d.optString("id"))) {
                    dl.remove(i);
                    break;
                }
            }
            if (controller.saveToFile(getContext())) {
                reloadController();
            }
        }

        private void addAll(List<Integer> target, int[] codes) {
            if (codes != null) {
                for (int c : codes) {
                    target.add(c);
                }
            }
        }

        private JSONArray toKeycodeArray(List<Integer> codes) {
            JSONArray arr = new JSONArray();
            for (int c : codes) {
                arr.put(c);
            }
            return arr;
        }

        private boolean insideRocker(float x, float y) {
            if (rockerSize <= 0) {
                return false;
            }
            float cx = getWidth() / 2f + rockerOffsetX;
            float cy = getHeight() / 2f + rockerOffsetY;
            return x >= cx - rockerSize / 2f && x <= cx + rockerSize / 2f
                    && y >= cy - rockerSize / 2f && y <= cy + rockerSize / 2f;
        }

        private void handlePadEvent(float x, float y) {
            if ("BUTTON".equals(data.style.styleType)) {
                handleButtonEvent((int) x, (int) y);
            } else {
                handleRockerEvent((int) x, (int) y);
            }
        }

        private void handleButtonEvent(int x, int y) {
            if (data.style.buttonStyle == null) {
                return;
            }
            int w = getWidth();
            int interval = (int) data.style.buttonStyle.strokeWidth;
            int size = w * (1000 - 2 * interval) / 3000;
            int p1 = size + w * interval / 1000;
            int p2 = w - size;
            boolean up = y <= size;
            boolean down = y >= p2;
            boolean left = x <= size;
            boolean right = x >= p2;
            if (x >= p1 && x <= p1 + size && y >= p1 && y <= p1 + size) {
                up = down = left = right = false;
            }
            setDirs(up, down, left, right);
        }

        private void handleRockerEvent(int x, int y) {
            if (rockerSize <= 0 || maxDistance <= 0) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            float dx = x - w / 2f;
            float dy = y - h / 2f;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > maxDistance) {
                dx = dx / dist * maxDistance;
                dy = dy / dist * maxDistance;
            }
            rockerOffsetX = dx;
            rockerOffsetY = dy;

            float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
            if (angle < 0) {
                angle += 360f;
            }
            boolean up = angle >= 202.5f && angle < 292.5f;
            boolean down = angle >= 22.5f && angle < 157.5f;
            boolean left = angle >= 157.5f && angle < 202.5f;
            boolean right = angle >= 292.5f || angle < 22.5f;
            // Corner sectors: FCL treats them as the two adjacent directions.
            boolean upLeft = angle >= 202.5f && angle < 247.5f;
            boolean upRight = angle >= 292.5f && angle < 337.5f;
            boolean downLeft = angle >= 112.5f && angle < 157.5f;
            boolean downRight = angle >= 22.5f && angle < 67.5f;
            if (upLeft) {
                up = left = true;
            } else if (upRight) {
                up = right = true;
            } else if (downLeft) {
                down = left = true;
            } else if (downRight) {
                down = right = true;
            }
            setDirs(up, down, left, right);
            invalidate();
        }

        private void setDirs(boolean up, boolean down, boolean left, boolean right) {
            if (up != dirUp) {
                dirUp = up;
                sendDirCodes(data.upKeycodes, up);
            }
            if (down != dirDown) {
                dirDown = down;
                sendDirCodes(data.downKeycodes, down);
            }
            if (left != dirLeft) {
                dirLeft = left;
                sendDirCodes(data.leftKeycodes, left);
            }
            if (right != dirRight) {
                dirRight = right;
                sendDirCodes(data.rightKeycodes, right);
            }
            invalidate();
        }

        private void sendDirCodes(int[] codes, boolean press) {
            for (int code : codes) {
                sendFclKey(code, press ? 0 : 1);
            }
        }

        private void toggleSneak() {
            sneakActive = !sneakActive;
            sendFclKey(data.sneakKeycode, sneakActive ? 0 : 1);
        }

        void releaseAll() {
            setDirs(false, false, false, false);
            if (sneakActive) {
                sneakActive = false;
                sendFclKey(data.sneakKeycode, 1);
            }
            rockerOffsetX = 0;
            rockerOffsetY = 0;
            clickCount = 0;
            invalidate();
        }
    }

    // ======================================================================

    private void sendFclKey(int code, int action) {
        if (bridge == null) {
            return;
        }
        // FCL uses -1/0 for "no key" in some community controllers.
        if (code <= 0) {
            return;
        }
        boolean down = action == 0;
        if (code == FCL_MOUSE_LEFT) {
            bridge.mouseButton(EV_BTN_LEFT, down);
        } else if (code == FCL_MOUSE_MIDDLE) {
            bridge.mouseButton(EV_BTN_MIDDLE, down);
        } else if (code == FCL_MOUSE_RIGHT) {
            bridge.mouseButton(EV_BTN_RIGHT, down);
        } else if (code == FCL_MOUSE_SCROLL_UP) {
            bridge.mouseScroll(0, down ? 10f : 0f, down ? 1 : 0);
        } else if (code == FCL_MOUSE_SCROLL_DOWN) {
            bridge.mouseScroll(0, down ? -10f : 0f, down ? -1 : 0);
        } else {
            bridge.key(action, code);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

}
