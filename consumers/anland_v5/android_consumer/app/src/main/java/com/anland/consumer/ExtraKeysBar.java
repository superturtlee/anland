package com.anland.consumer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained Termux-style bottom extra-keys bar. Built on the framework
 * GridLayout (no androidx). Replicates Termux's operational behaviour:
 *   - modifier keys (CTRL/ALT/SHIFT) toggle active on tap, lock on long-press;
 *   - directional / editing keys auto-repeat while held;
 *   - keys with a popup fire the popup key on swipe-up.
 *
 * Keys are sent out through a {@link Sender} bridge so this view never touches
 * MainActivity's native methods directly. Ordinary keys are sent as evdev
 * scancodes (down+up); text keys ('/', '-', '|') are sent as UTF-8 text.
 */
public class ExtraKeysBar extends GridLayout {

    /** Bridge to the host: native key/text injection, IME toggle and settings. */
    public interface Sender {
        void key(int action, int evdev);   // action: 0 = down, 1 = up
        void text(String s);
        void toggleKeyboard();          // toggle the system IME (soft keyboard)
        void toggleVirtualKeyboard();   // toggle the floating virtual keyboard
        void openSettings();
    }

    // evdev keycodes (linux/input-event-codes.h)
    private static final int EV_ESC = 1, EV_TAB = 15, EV_DELETE = 111;
    private static final int EV_HOME = 102, EV_END = 107, EV_PAGEUP = 104, EV_PAGEDOWN = 109;
    private static final int EV_UP = 103, EV_DOWN = 108, EV_LEFT = 105, EV_RIGHT = 106;
    private static final int EV_LEFTCTRL = 29, EV_LEFTSHIFT = 42, EV_LEFTALT = 56;

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int ACTIVE_TEXT_COLOR = 0xFF80DEEA;
    private static final int BG_COLOR = 0x00000000;
    private static final int ACTIVE_BG_COLOR = 0xFF7F7F7F;

    // Bar backdrop: nearly opaque in the default (display-resizing) mode; more
    // translucent in floating mode so the desktop shows through behind the keys.
    private static final int BAR_BG_COLOR = 0xCC000000;
    private static final int BAR_BG_FLOATING_COLOR = 0x99000000;

    private static final long REPEAT_DELAY_MS = 80;

    private static final int TYPE_KEY = 0;        // evdev down+up
    private static final int TYPE_TEXT = 1;       // text out
    private static final int TYPE_MODIFIER = 2;   // CTRL/ALT/SHIFT toggle
    private static final int TYPE_KEYBOARD = 3;   // toggle system IME
    private static final int TYPE_SETTINGS = 4;   // open settings
    private static final int TYPE_VKEYBOARD = 5;  // toggle floating virtual keyboard

    // Glyphs for icon-style keys.
    private static final String GLYPH_KEYBOARD = "⌨";  // ⌨
    private static final String GLYPH_SETTINGS = "⚙";  // ⚙
    private static final String GLYPH_VKEYBOARD = "VK"; // pull-up popup on ⌨

    private static final int ROWS = 2;
    private static final int COLS = 8;

    // Settings storage for the user-customizable layout. Shared with
    // SettingsActivity / MainActivity (same SharedPreferences file).
    private static final String PREFS_NAME = "anland_settings";
    private static final String KEY_EXTRA_KEYS_LAYOUT = "extra_keys_layout";

    // JSON key/value names for the serialized layout (see DEFAULT_LAYOUT_JSON).
    private static final String J_ROWS = "rows";
    private static final String J_LABEL = "label";
    private static final String J_TYPE = "type";
    private static final String J_CODE = "code";
    private static final String J_TEXT = "text";
    private static final String J_REPEAT = "repeat";
    private static final String J_POPUP = "popup";

    // Editable template shown in Settings and the source for the default layout.
    // Mirrors the built-in layout() exactly.
    private static final String DEFAULT_LAYOUT_JSON =
        "{\n" +
        "  \"rows\": [\n" +
        "    [\n" +
        "      {\"label\":\"ESC\",  \"type\":\"key\",      \"code\":1},\n" +
        "      {\"label\":\"/\",    \"type\":\"text\",     \"text\":\"/\"},\n" +
        "      {\"label\":\"-\",    \"type\":\"text\",     \"text\":\"-\", \"popup\":{\"label\":\"|\",\"type\":\"text\",\"text\":\"|\"}},\n" +
        "      {\"label\":\"HOME\", \"type\":\"key\",      \"code\":102, \"repeat\":true},\n" +
        "      {\"label\":\"↑\",    \"type\":\"key\",      \"code\":103, \"repeat\":true},\n" +
        "      {\"label\":\"END\",  \"type\":\"key\",      \"code\":107, \"repeat\":true},\n" +
        "      {\"label\":\"PGUP\", \"type\":\"key\",      \"code\":104, \"repeat\":true},\n" +
        "      {\"label\":\"⌨\",    \"type\":\"keyboard\", \"popup\":{\"label\":\"VK\",\"type\":\"vkeyboard\"}}\n" +
        "    ],\n" +
        "    [\n" +
        "      {\"label\":\"TAB\",  \"type\":\"key\",      \"code\":15},\n" +
        "      {\"label\":\"CTRL\", \"type\":\"modifier\", \"code\":29},\n" +
        "      {\"label\":\"ALT\",  \"type\":\"modifier\", \"code\":56},\n" +
        "      {\"label\":\"←\",    \"type\":\"key\",      \"code\":105, \"repeat\":true},\n" +
        "      {\"label\":\"↓\",    \"type\":\"key\",      \"code\":108, \"repeat\":true},\n" +
        "      {\"label\":\"→\",    \"type\":\"key\",      \"code\":106, \"repeat\":true},\n" +
        "      {\"label\":\"PGDN\", \"type\":\"key\",      \"code\":109, \"repeat\":true},\n" +
        "      {\"label\":\"⚙\",    \"type\":\"settings\"}\n" +
        "    ]\n" +
        "  ]\n" +
        "}\n";

    /** A single key definition. */
    private static final class Key {
        final String display;
        final int type;
        final int evdev;      // for TYPE_KEY / TYPE_MODIFIER
        final String text;    // for TYPE_TEXT
        final boolean repeatable;
        final Key popup;      // swipe-up secondary key (may be null)

        Key(String display, int type, int evdev, String text, boolean repeatable, Key popup) {
            this.display = display;
            this.type = type;
            this.evdev = evdev;
            this.text = text;
            this.repeatable = repeatable;
            this.popup = popup;
        }

        static Key key(String d, int evdev) { return new Key(d, TYPE_KEY, evdev, null, false, null); }
        static Key rep(String d, int evdev) { return new Key(d, TYPE_KEY, evdev, null, true, null); }
        static Key text(String d) { return new Key(d, TYPE_TEXT, 0, d, false, null); }
        static Key textPopup(String d, Key popup) { return new Key(d, TYPE_TEXT, 0, d, false, popup); }
        static Key mod(String d, int evdev) { return new Key(d, TYPE_MODIFIER, evdev, null, false, null); }
        static Key kbd(String d, Key popup) { return new Key(d, TYPE_KEYBOARD, 0, null, false, popup); }
        static Key vkbd(String d) { return new Key(d, TYPE_VKEYBOARD, 0, null, false, null); }
        static Key settings(String d) { return new Key(d, TYPE_SETTINGS, 0, null, false, null); }
    }

    /** Mutable modifier state shared by name. */
    private static final class ModState {
        boolean active;
        boolean locked;
        int evdev;      // scancode emitted while this modifier is held
        final List<Button> buttons = new ArrayList<>();
    }

    private final Sender mSender;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final long mLongPressTimeout = ViewConfiguration.getLongPressTimeout();

    // modifier name ("CTRL"/"ALT"/"SHIFT") -> state
    private final Map<String, ModState> mModifiers = new LinkedHashMap<>();

    private PopupWindow mPopupWindow;

    // Active grid, resolved from the user's JSON layout (or the built-in default).
    private final Key[][] mLayout;
    private final int mRows;
    private final int mCols;

    public ExtraKeysBar(Context context, Sender sender) {
        super(context);
        mSender = sender;

        // Build from the user's saved JSON layout; fall back to the hardcoded
        // default if it's absent or malformed.
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_EXTRA_KEYS_LAYOUT, null);
        Key[][] parsed = null;
        if (json != null && !json.trim().isEmpty()) {
            try {
                parsed = parseLayout(json);
                if (parsed.length == 0) parsed = null;  // empty layout: use default
            } catch (JSONException e) {
                parsed = null;  // fall through to the built-in default
            }
        }
        mLayout = (parsed != null) ? parsed : layout();

        int cols = 0;
        for (Key[] row : mLayout) cols = Math.max(cols, row.length);
        mRows = mLayout.length;
        mCols = Math.max(1, cols);

        setColumnCount(mCols);
        setRowCount(mRows);
        setBackgroundColor(BAR_BG_COLOR);
        buildKeys();
    }

    /** Number of rows in the active layout, used by the host to size the bar. */
    public int getRowCount() { return mRows; }

    /**
     * Switch the bar backdrop between the default (nearly opaque) and floating
     * (translucent) appearance. In floating mode the bar overlays the display
     * instead of compressing it, so the desktop should remain partly visible.
     */
    public void setFloating(boolean floating) {
        setBackgroundColor(floating ? BAR_BG_FLOATING_COLOR : BAR_BG_COLOR);
    }

    /** The editable default layout template (also used by SettingsActivity). */
    public static String defaultLayoutJson() { return DEFAULT_LAYOUT_JSON; }

    /**
     * Validate a serialized layout. Returns null if it parses cleanly, otherwise
     * a short human-readable error message. Used by SettingsActivity to give the
     * user inline feedback without building a view.
     */
    public static String validateLayout(String json) {
        if (json == null || json.trim().isEmpty())
            return "Empty (the built-in default will be used)";
        try {
            Key[][] rows = parseLayout(json);
            if (rows.length == 0) return "No rows defined";
            return null;
        } catch (JSONException e) {
            String msg = e.getMessage();
            return "Invalid: " + (msg != null ? msg : "JSON parse error");
        }
    }

    private Key[][] layout() {
        return new Key[][] {
            {
                Key.key("ESC", EV_ESC),
                Key.text("/"),
                Key.textPopup("-", Key.text("|")),
                Key.rep("HOME", EV_HOME),
                Key.rep("↑", EV_UP),
                Key.rep("END", EV_END),
                Key.rep("PGUP", EV_PAGEUP),
                Key.kbd(GLYPH_KEYBOARD, Key.vkbd(GLYPH_VKEYBOARD)),
            },
            {
                Key.key("TAB", EV_TAB),
                Key.mod("CTRL", EV_LEFTCTRL),
                Key.mod("ALT", EV_LEFTALT),
                Key.rep("←", EV_LEFT),
                Key.rep("↓", EV_DOWN),
                Key.rep("→", EV_RIGHT),
                Key.rep("PGDN", EV_PAGEDOWN),
                Key.settings(GLYPH_SETTINGS),
            },
        };
    }

    // Parse a serialized layout into a Key grid. Throws JSONException on any
    // structural problem; callers fall back to the built-in default.
    private static Key[][] parseLayout(String json) throws JSONException {
        JSONArray rows = new JSONObject(json).getJSONArray(J_ROWS);
        Key[][] out = new Key[rows.length()][];
        for (int r = 0; r < rows.length(); r++) {
            JSONArray row = rows.getJSONArray(r);
            Key[] keys = new Key[row.length()];
            for (int c = 0; c < row.length(); c++)
                keys[c] = parseKey(row.getJSONObject(c));
            out[r] = keys;
        }
        return out;
    }

    private static Key parseKey(JSONObject o) throws JSONException {
        String label = o.optString(J_LABEL, "");
        String type = o.optString(J_TYPE, "key");
        Key popup = o.has(J_POPUP) ? parseKey(o.getJSONObject(J_POPUP)) : null;
        switch (type) {
            case "text":
                return new Key(label, TYPE_TEXT, 0, o.optString(J_TEXT, label), false, popup);
            case "modifier":
                return new Key(label, TYPE_MODIFIER, o.optInt(J_CODE, 0), null, false, popup);
            case "keyboard": 
                return new Key(label, TYPE_KEYBOARD, 0, null, false, popup);
            case "vkeyboard":
                return new Key(label, TYPE_VKEYBOARD, 0, null, false, popup);
            case "settings":
                return new Key(label, TYPE_SETTINGS, 0, null, false, popup);
            case "key":
            default: {
                return new Key(label, TYPE_KEY, o.optInt(J_CODE, 0), null,
                               o.optBoolean(J_REPEAT, false), popup);
            }
        }
    }

    private void buildKeys() {
        Key[][] rows = mLayout;
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length; c++) {
                Key key = rows[r][c];
                Button button = new Button(getContext(), null, android.R.attr.buttonBarButtonStyle);
                button.setText(key.display);
                button.setTextColor(TEXT_COLOR);
                button.setAllCaps(true);
                button.setPadding(0, 0, 0, 0);
                button.setBackgroundColor(BG_COLOR);

                if (key.type == TYPE_MODIFIER) {
                    ModState state = mModifiers.get(key.display);
                    if (state == null) {
                        state = new ModState();
                        state.evdev = key.evdev;
                        mModifiers.put(key.display, state);
                    }
                    state.buttons.add(button);
                }

                GridLayout.LayoutParams param = new GridLayout.LayoutParams();
                param.width = 0;
                param.height = 0;
                param.setMargins(0, 0, 0, 0);
                param.columnSpec = GridLayout.spec(c, GridLayout.FILL, 1f);
                param.rowSpec = GridLayout.spec(r, GridLayout.FILL, 1f);
                button.setLayoutParams(param);

                attachListeners(button, key);
                addView(button);
            }
        }
    }

    private void attachListeners(final Button button, final Key key) {
        button.setOnClickListener(v -> onKeyClick(key));

        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    button.setBackgroundColor(ACTIVE_BG_COLOR);
                    startScheduled(button, key);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (key.popup != null) {
                        if (mPopupWindow == null && event.getY() < 0) {
                            stopScheduled();
                            button.setBackgroundColor(BG_COLOR);
                            showPopup(button, key.popup);
                        }
                        if (mPopupWindow != null && event.getY() > 0) {
                            button.setBackgroundColor(ACTIVE_BG_COLOR);
                            dismissPopup();
                        }
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    button.setBackgroundColor(BG_COLOR);
                    stopScheduled();
                    return true;

                case MotionEvent.ACTION_UP:
                    button.setBackgroundColor(BG_COLOR);
                    boolean repeated = mRepeatActive;
                    stopScheduled();
                    if (mPopupWindow != null) {
                        Key popup = key.popup;
                        dismissPopup();
                        if (popup != null)
                            onKeyClick(popup);
                    } else if (!repeated && !mLongHoldFired) {
                        button.performClick();
                    }
                    return true;
            }
            return false;
        });
    }

    // ------- click / send -------

    private void onKeyClick(Key key) {
        switch (key.type) {
            case TYPE_KEYBOARD:
                mSender.toggleKeyboard();
                return;
            case TYPE_VKEYBOARD:
                mSender.toggleVirtualKeyboard();
                return;
            case TYPE_SETTINGS:
                mSender.openSettings();
                return;
            case TYPE_MODIFIER:
                toggleModifier(key.display);
                return;
            case TYPE_TEXT:
                sendWithModifiers(() -> mSender.text(key.text));
                return;
            case TYPE_KEY:
            default:
                sendWithModifiers(() -> {
                    mSender.key(0, key.evdev);
                    mSender.key(1, key.evdev);
                });
        }
    }

    /** Wrap a key emission with currently-active modifier down/up, then clear unlocked ones. */
    private void sendWithModifiers(Runnable emit) {
        List<ModState> held = new ArrayList<>();
        for (ModState m : mModifiers.values())
            if (m.active) held.add(m);

        for (ModState m : held) mSender.key(0, modEvdev(m));
        emit.run();
        for (int i = held.size() - 1; i >= 0; i--) mSender.key(1, modEvdev(held.get(i)));

        // Auto-release modifiers that aren't locked.
        for (ModState m : held) {
            if (!m.locked)
                setModifierActive(m, false);
        }
    }

    private int modEvdev(ModState state) {
        return state.evdev;
    }

    private void toggleModifier(String name) {
        ModState state = mModifiers.get(name);
        if (state == null) return;
        setModifierActive(state, !state.active);
        if (!state.active) state.locked = false;
    }

    private void setModifierActive(ModState state, boolean active) {
        state.active = active;
        if (!active) state.locked = false;
        for (Button b : state.buttons)
            b.setTextColor(active ? ACTIVE_TEXT_COLOR : TEXT_COLOR);
    }

    // ------- long press / repeat scheduling -------

    private Runnable mRepeatRunnable;
    private Runnable mLongHoldRunnable;
    private boolean mRepeatActive;
    private boolean mLongHoldFired;

    private void startScheduled(Button button, Key key) {
        stopScheduled();
        mRepeatActive = false;
        mLongHoldFired = false;

        if (key.repeatable) {
            mRepeatRunnable = new Runnable() {
                @Override public void run() {
                    mRepeatActive = true;
                    onKeyClick(key);
                    mHandler.postDelayed(this, REPEAT_DELAY_MS);
                }
            };
            mHandler.postDelayed(mRepeatRunnable, mLongPressTimeout);
        } else if (key.type == TYPE_MODIFIER) {
            mLongHoldRunnable = () -> {
                ModState state = mModifiers.get(key.display);
                if (state == null) return;
                // Lock and toggle active (Termux semantics).
                setModifierActive(state, !state.active);
                state.locked = state.active;
                mLongHoldFired = true;
            };
            mHandler.postDelayed(mLongHoldRunnable, mLongPressTimeout);
        }
    }

    private void stopScheduled() {
        if (mRepeatRunnable != null) {
            mHandler.removeCallbacks(mRepeatRunnable);
            mRepeatRunnable = null;
        }
        if (mLongHoldRunnable != null) {
            mHandler.removeCallbacks(mLongHoldRunnable);
            mLongHoldRunnable = null;
        }
    }

    // ------- popup -------

    private void showPopup(View anchor, Key popupKey) {
        TextView tv = new TextView(getContext());
        tv.setText(popupKey.display);
        tv.setTextColor(ACTIVE_TEXT_COLOR);
        tv.setTextSize(18);
        tv.setBackgroundColor(ACTIVE_BG_COLOR);
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setGravity(Gravity.CENTER);

        mPopupWindow = new PopupWindow(tv,
                anchor.getWidth(), anchor.getHeight(), false);
        mPopupWindow.setClippingEnabled(false);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        mPopupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0], loc[1] - anchor.getHeight());
    }

    private void dismissPopup() {
        if (mPopupWindow != null) {
            mPopupWindow.dismiss();
            mPopupWindow = null;
        }
    }

    /** True if any modifier (CTRL/ALT/SHIFT) is currently active. */
    public boolean hasActiveModifier() {
        for (ModState m : mModifiers.values())
            if (m.active) return true;
        return false;
    }

    /**
     * Send an evdev key wrapped by the currently-active modifiers, then clear the
     * unlocked ones. Used to combine a soft-keyboard character with a held bar
     * modifier (e.g. bar CTRL + IME 'c' -> Ctrl+C).
     */
    public void sendKeyComboFromExternal(int evdev) {
        sendWithModifiers(() -> {
            mSender.key(0, evdev);
            mSender.key(1, evdev);
        });
    }

    /** Release any held modifiers (e.g. when the bar is hidden). */
    public void reset() {
        stopScheduled();
        dismissPopup();
        for (ModState m : mModifiers.values())
            setModifierActive(m, false);
    }
}
