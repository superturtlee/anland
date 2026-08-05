package com.anland.consumer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar; // ===== 新增导入
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


public class SettingsActivity extends Activity {
    private static final String TAG = "AnlandSettings";
    private static final String PREFS_NAME = "anland_settings";
    private static final String KEY_BOUND_KEYCODE = "bound_keycode";
    private static final String KEY_SOCKET_PATH = "socket_path";
    private static final String KEY_USE_ROOT = "use_root";
    private static final String KEY_MIC_ENABLED = "mic_enabled";
    private static final String KEY_CAMERA_ENABLED = "camera_enabled";
    private static final String KEY_AUDIO_KEEPALIVE = "audio_keepalive";
    private static final String KEY_SPEAKER_LATENCY_MS = "speaker_latency_ms";
    private static final String KEY_MIC_LATENCY_MS = "mic_latency_ms";
    private static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_key_intercept";
    private static final String KEY_IMMERSIVE_ENABLED = ImmersiveMode.KEY_ENABLED;
    private static final String KEY_IMMERSIVE_KEYCODE = ImmersiveMode.KEY_KEYCODE;
    private static final String KEY_IMMERSIVE_SCANCODE = ImmersiveMode.KEY_SCANCODE;
    private static final String KEY_EXTRA_KEYS_MODE = "extra_keys_mode";
    // Mapped to R.array.extra_keys_mode_options positions
    private static final String MODE_ALWAYS = "always";
    private static final String MODE_NEVER = "never";
    private static final String MODE_WITH_KEYBOARD = "with_keyboard";
    private static final String[] EXTRA_KEYS_MODES = {MODE_ALWAYS, MODE_NEVER, MODE_WITH_KEYBOARD};
    private static final String KEY_BACK_OPENS_EXTRA_KEYS = "back_opens_extra_keys";
    private static final String KEY_EXTRA_KEYS_LAYOUT = "extra_keys_layout";
    private static final String KEY_KEYBOARD_FLOATING = "keyboard_floating";
    private static final String KEY_NOTIFICATION_ENABLED = "settings_notification";
    private static final String KEY_ORIENTATION = "screen_orientation";
    private static final String[] ORIENTATION_VALUES = {"default", "landscape", "portrait"};
    private static final String DEFAULT_SOCKET_PATH = "/data/local/tmp/display_daemon.sock";
    private static final int UNBOUND = -1;

    // ===== 新增：触摸板 Key =====
    private static final String KEY_TOUCHPAD_MODE = "touchpad_mode";
    private static final String KEY_MOUSE_ACCEL = "mouse_speed";
    private static final String KEY_POINTER_CAPTURE = "pointer_capture";
    private static final String KEY_SCROLL_SPEED = "scroll_speed";
    private static final String KEY_SCROLL_REVERSE = "scroll_reverse";
    private static final String KEY_SCROLL_THRESHOLD = "touchpad_scroll_threshold";
    private static final String KEY_MOVE_THRESHOLD = "touchpad_move_threshold";
    private static final String KEY_GESTURE_SCALE = "touchpad_gesture_scale";

    // Latency presets: target buffer in ms (0 = auto). The user-visible labels live
    // in the R.array.latency_labels string-array, parallel to this array.
    private static final int[] LATENCY_MS = {0, 1, 3, 5, 10, 20};

    // Which secondary page is on screen. Back returns HOME -> exits the activity.
    private enum Page { HOME, KEYBOARD, TOUCHPAD, CONNECTION, RESOLUTION, GENERAL }
    private Page currentPage = Page.HOME;

    // The key-binding row currently counting down, if any: it gets the next key
    // press. The rows themselves live in the page's view hierarchy.
    private KeyBinding listeningBinding;

    // Custom extra-keys layout editor (JSON), and the SAF file-picker request code.
    private EditText layoutInput;
    private static final int REQ_PICK_LAYOUT = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showHome();
    }

    // ============================================================
    // Navigation: a home list of categories, each opening a page.
    // Every page is a fresh LinearLayout wrapped by setContent().
    // ============================================================

    // Wrap `content` in the standard white ScrollView, apply edge-to-edge insets,
    // and install it. Reused by the home list and every secondary page.
    private void setContent(final LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(content);
        setContentView(scroll);

        // Edge-to-edge is enforced on Android 15+ (targetSdk 36): the system no
        // longer auto-resizes the window for the IME, so a manifest "adjustResize"
        // is ignored and the soft keyboard overlaps the bottom EditTexts. Take over
        // inset handling and pad the scrollable content by the system-bar + IME
        // insets ourselves, so the ScrollView can scroll the focused field above
        // the keyboard. Base padding (dp(24)) is preserved on all edges.
        getWindow().setDecorFitsSystemWindows(false);
        final int base = dp(24);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets in = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(base + in.left, base + in.top,
                         base + in.right, base + in.bottom);
            return insets;
        });
    }

    private void showHome() {
        stopListening();
        currentPage = Page.HOME;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(24));
        root.addView(title);

        addCategoryRow(root, R.string.cat_keyboard_title,
            R.string.cat_keyboard_subtitle, this::showKeyboardPage);
        addCategoryRow(root, R.string.cat_touchpad_title,
            R.string.cat_touchpad_subtitle, this::showTouchpadPage);
        addCategoryRow(root, R.string.section_connection,
            R.string.cat_connection_subtitle, this::showConnectionPage);
        addCategoryRow(root, R.string.section_resolution,
            R.string.cat_resolution_subtitle, this::showResolutionPage);
        addCategoryRow(root, R.string.cat_general_title,
            R.string.cat_general_subtitle, this::showGeneralPage);

        // Build version, injected from git at build time (see app/build.gradle).
        TextView version = new TextView(this);
        version.setText(BuildConfig.VERSION_NAME
            + " (" + BuildConfig.VERSION_CODE + ")");
        version.setTextSize(12);
        version.setGravity(Gravity.START);
        version.setPadding(0, dp(24), 0, 0);
        version.setAlpha(0.5f);
        root.addView(version);

        setContent(root);
    }

    // A tappable "title / subtitle ›" row plus a hairline divider.
    private void addCategoryRow(LinearLayout parent, int titleRes, int subtitleRes,
                                final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, dp(16));
        row.setClickable(true);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }
        row.setOnClickListener(v -> onClick.run());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(titleRes);
        t.setTextSize(18);
        t.setTextColor(Color.BLACK);
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitleRes);
        s.setTextSize(13);
        s.setTextColor(Color.GRAY);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);

        row.addView(texts);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(22);
        chevron.setTextColor(Color.GRAY);
        row.addView(chevron);

        parent.addView(row);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        divider.setBackgroundColor(0xFFE0E0E0);
        parent.addView(divider);
    }

    // A fresh page root with a back link and a bold page title.
    private LinearLayout newPage(int titleRes) {
        stopListening();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView back = new TextView(this);
        back.setText(R.string.nav_back);
        back.setTextSize(16);
        back.setTextColor(0xFF1565C0);
        back.setPadding(0, 0, 0, dp(12));
        back.setClickable(true);
        back.setOnClickListener(v -> showHome());
        root.addView(back);

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(24));
        root.addView(title);

        return root;
    }

    private void showKeyboardPage() {
        currentPage = Page.KEYBOARD;
        LinearLayout root = newPage(R.string.cat_keyboard_title);
        buildVirtualKeyboardSection(root);
        buildImmersiveSection(root);
        buildAccessibilitySection(root);
        buildExtraKeysSection(root);
        buildCustomLayoutSection(root);
        setContent(root);
    }

    private void showTouchpadPage() {
        currentPage = Page.TOUCHPAD;
        LinearLayout root = newPage(R.string.cat_touchpad_title);
        buildTouchpadSection(root);
        setContent(root);
    }

    private void showConnectionPage() {
        currentPage = Page.CONNECTION;
        LinearLayout root = newPage(R.string.section_connection);
        addConnectionSection(root);
        setContent(root);
    }

    private void showResolutionPage() {
        currentPage = Page.RESOLUTION;
        LinearLayout root = newPage(R.string.section_resolution);
        addResolutionSection(root);
        setContent(root);
    }

    private void showGeneralPage() {
        currentPage = Page.GENERAL;
        LinearLayout root = newPage(R.string.cat_general_title);
        buildOrientationSection(root);
        buildNotificationSection(root);
        setContent(root);
    }

    @Override
    public void onBackPressed() {
        // While listening for a key binding, let onKeyDown capture the Back key
        // instead of navigating back.
        if (listeningBinding != null) return;
        if (currentPage != Page.HOME) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    // ============================================================
    // Keyboard & Keys page sections
    // ============================================================

    private void buildVirtualKeyboardSection(LinearLayout root) {
        addSectionHeader(root, R.string.section_virtual_keyboard, 0);
        // Constructing the row appends it to `root`.
        new KeyBinding(root, KEY_BOUND_KEYCODE, null, R.string.bind_key_button);
    }

    /**
     * Immersive mode: a root helper takes the touchscreen, keyboard and pointer
     * away from Android for as long as the session lasts, so every input goes to
     * the Linux desktop instead. The switch is a safety gate rather than the
     * feature itself — with it off the bound key does nothing — and the binding
     * below records the key's raw scan code, which is the only thing the root
     * helper can compare while Android is no longer in the loop.
     */
    private void buildImmersiveSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        addSectionHeader(root, R.string.section_immersive, dp(24));

        Switch immersiveSwitch = new Switch(this);
        immersiveSwitch.setText(R.string.immersive_switch);
        immersiveSwitch.setTextSize(14);
        immersiveSwitch.setPadding(0, 0, 0, 0);
        immersiveSwitch.setChecked(prefs.getBoolean(KEY_IMMERSIVE_ENABLED, false));
        immersiveSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_IMMERSIVE_ENABLED, checked).apply());
        root.addView(immersiveSwitch);

        TextView immersiveHint = new TextView(this);
        immersiveHint.setText(R.string.immersive_hint);
        immersiveHint.setTextSize(12);
        immersiveHint.setTextColor(Color.GRAY);
        immersiveHint.setPadding(0, dp(4), 0, dp(12));
        root.addView(immersiveHint);

        // Constructing the row appends it to `root`.
        new KeyBinding(root, KEY_IMMERSIVE_KEYCODE, KEY_IMMERSIVE_SCANCODE,
                R.string.bind_immersive_key_button);
    }

    private void addSectionHeader(LinearLayout root, int titleRes, int topPadding) {
        TextView header = new TextView(this);
        header.setText(titleRes);
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, topPadding, 0, dp(8));
        root.addView(header);
    }

    /**
     * One "bind a key" row: a status line plus a button that listens for the next
     * key press for five seconds. Both bindings on this page use it, so the
     * listening state lives per row instead of on the activity.
     */
    private final class KeyBinding {
        private final String keyPref;
        /**
         * Where to store the raw evdev scan code, or null when only the Android
         * key code matters. Immersive mode needs it: {@link KeyCodeMapper} has no
         * entry for the volume keys, and its root helper only ever sees evdev
         * codes.
         */
        private final String scanPref;
        private final int buttonLabelRes;
        private final Button button;
        private final TextView status;
        private CountDownTimer timer;

        KeyBinding(LinearLayout root, String keyPref, String scanPref,
                   int buttonLabelRes) {
            this.keyPref = keyPref;
            this.scanPref = scanPref;
            this.buttonLabelRes = buttonLabelRes;

            status = new TextView(SettingsActivity.this);
            status.setTextSize(14);
            status.setTextColor(Color.GRAY);
            status.setPadding(0, 0, 0, dp(16));
            root.addView(status);

            button = new Button(SettingsActivity.this);
            button.setText(buttonLabelRes);
            button.setOnClickListener(v -> startListening());
            root.addView(button);

            updateStatus();
        }

        private void startListening() {
            if (listeningBinding == this)
                return;
            stopListening();
            listeningBinding = this;
            button.setText(getString(R.string.listening_countdown, 5));
            timer = new CountDownTimer(5000, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    button.setText(getString(R.string.listening_countdown,
                        (int) (millisUntilFinished / 1000)));
                }

                @Override
                public void onFinish() {
                    // Timed out with no key: clear the binding, matching the
                    // original behaviour of "listen, then store whatever came".
                    bind(UNBOUND, UNBOUND);
                }
            }.start();
        }

        /** Stop listening without changing what is bound. */
        void cancel() {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            button.setText(buttonLabelRes);
        }

        void bind(int keycode, int scancode) {
            cancel();
            listeningBinding = null;
            SharedPreferences.Editor edit =
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            edit.putInt(keyPref, keycode);
            if (scanPref != null)
                edit.putInt(scanPref, scancode);
            edit.apply();
            updateStatus();
        }

        void updateStatus() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int bound = prefs.getInt(keyPref, UNBOUND);
            int scan = scanPref == null ? UNBOUND : prefs.getInt(scanPref, UNBOUND);
            if (bound == UNBOUND && scan <= 0) {
                status.setText(R.string.status_current_none);
                status.setTextColor(Color.GRAY);
                return;
            }
            String name = KeyCodeMapper.keyName(SettingsActivity.this, bound, scan);
            // A binding that resolves to no evdev code is useless to the root
            // helper, so say so here rather than let the key quietly do nothing.
            if (scanPref != null && resolveEvdev(bound, scan) <= 0) {
                status.setText(getString(R.string.status_current_no_scancode, name));
                status.setTextColor(0xFFC62828);  // red
                return;
            }
            status.setText(getString(R.string.status_current, name));
            status.setTextColor(Color.GRAY);
        }

        private int resolveEvdev(int keycode, int scancode) {
            return scancode > 0 ? scancode
                    : (keycode == UNBOUND ? -1 : KeyCodeMapper.getScanCode(keycode));
        }
    }

    private void buildAccessibilitySection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Switch accessibilitySwitch = new Switch(this);
        accessibilitySwitch.setText(R.string.accessibility_switch);
        accessibilitySwitch.setTextSize(14);
        accessibilitySwitch.setPadding(0, dp(16), 0, 0);
        accessibilitySwitch.setChecked(prefs.getBoolean(KEY_ACCESSIBILITY_ENABLED, false));
        accessibilitySwitch.setOnCheckedChangeListener((v, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACCESSIBILITY_ENABLED, checked).apply();
            if (checked) {
                KeyInterceptor.launch(SettingsActivity.this);
            } else {
                KeyInterceptor.shutdown(false);
            }
        });
        root.addView(accessibilitySwitch);

        TextView accessibilityHint = new TextView(this);
        accessibilityHint.setText(R.string.accessibility_hint);
        accessibilityHint.setTextSize(12);
        accessibilityHint.setTextColor(Color.GRAY);
        accessibilityHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(accessibilityHint);
    }

    private void buildExtraKeysSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView header = new TextView(this);
        header.setText(R.string.section_extra_keys);
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, dp(24), 0, dp(8));
        root.addView(header);

        // === Extra keys bar mode selector ===
        TextView modeLabel = new TextView(this);
        modeLabel.setText(R.string.extra_keys_mode_label);
        modeLabel.setTextSize(14);
        modeLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(modeLabel);

        Spinner modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            getResources().getStringArray(R.array.extra_keys_mode_options)));

        String curMode = getExtraKeysMode(prefs);
        int modeIdx = 0; // default: always
        for (int i = 0; i < EXTRA_KEYS_MODES.length; i++) {
            if (EXTRA_KEYS_MODES[i].equals(curMode)) { modeIdx = i; break; }
        }
        modeSpinner.setSelection(modeIdx);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_EXTRA_KEYS_MODE, EXTRA_KEYS_MODES[pos]).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(modeSpinner);

        TextView modeHint = new TextView(this);
        modeHint.setText(R.string.extra_keys_mode_hint);
        modeHint.setTextSize(12);
        modeHint.setTextColor(Color.GRAY);
        modeHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(modeHint);

        // === Back key opens extra keys bar ===
        Switch backOpensExtraKeysSwitch = new Switch(this);
        backOpensExtraKeysSwitch.setText(R.string.back_opens_switch);
        backOpensExtraKeysSwitch.setTextSize(14);
        backOpensExtraKeysSwitch.setPadding(0, dp(16), 0, 0);
        backOpensExtraKeysSwitch.setChecked(prefs.getBoolean(KEY_BACK_OPENS_EXTRA_KEYS, true));
        backOpensExtraKeysSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_BACK_OPENS_EXTRA_KEYS, checked).apply());
        root.addView(backOpensExtraKeysSwitch);

        TextView backOpensExtraKeysHint = new TextView(this);
        backOpensExtraKeysHint.setText(R.string.back_opens_hint);
        backOpensExtraKeysHint.setTextSize(12);
        backOpensExtraKeysHint.setTextColor(Color.GRAY);
        backOpensExtraKeysHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(backOpensExtraKeysHint);

        // === Keyboard floating ===
        Switch keyboardFloatingSwitch = new Switch(this);
        keyboardFloatingSwitch.setText(R.string.keyboard_floating_switch);
        keyboardFloatingSwitch.setTextSize(14);
        keyboardFloatingSwitch.setPadding(0, dp(16), 0, 0);
        keyboardFloatingSwitch.setChecked(prefs.getBoolean(KEY_KEYBOARD_FLOATING, true));
        keyboardFloatingSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_KEYBOARD_FLOATING, checked).apply());
        root.addView(keyboardFloatingSwitch);

        TextView keyboardFloatingHint = new TextView(this);
        keyboardFloatingHint.setText(R.string.keyboard_floating_hint);
        keyboardFloatingHint.setTextSize(12);
        keyboardFloatingHint.setTextColor(Color.GRAY);
        keyboardFloatingHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(keyboardFloatingHint);
    }

    private void buildCustomLayoutSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView layoutHeader = new TextView(this);
        layoutHeader.setText(R.string.section_custom_layout);
        layoutHeader.setTextSize(16);
        layoutHeader.setTypeface(null, Typeface.BOLD);
        layoutHeader.setPadding(0, dp(24), 0, dp(8));
        root.addView(layoutHeader);

        layoutInput = new EditText(this);
        layoutInput.setTypeface(Typeface.MONOSPACE);
        layoutInput.setTextSize(12);
        layoutInput.setGravity(Gravity.TOP | Gravity.START);
        layoutInput.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        layoutInput.setHorizontallyScrolling(false);
        layoutInput.setMinLines(6);
        String savedLayout = prefs.getString(KEY_EXTRA_KEYS_LAYOUT, "");
        if (savedLayout.isEmpty()) savedLayout = ExtraKeysBar.defaultLayoutJson();
        layoutInput.setText(savedLayout);
        root.addView(layoutInput);

        final TextView layoutStatus = new TextView(this);
        layoutStatus.setTextSize(12);
        layoutStatus.setPadding(0, dp(4), 0, dp(4));
        root.addView(layoutStatus);
        updateLayoutStatus(layoutStatus, layoutInput.getText().toString());

        layoutInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_EXTRA_KEYS_LAYOUT, s.toString()).apply();
                updateLayoutStatus(layoutStatus, s.toString());
            }
        });

        LinearLayout layoutButtons = new LinearLayout(this);
        layoutButtons.setOrientation(LinearLayout.HORIZONTAL);

        Button loadDefaultBtn = new Button(this);
        loadDefaultBtn.setText(R.string.btn_load_default);
        loadDefaultBtn.setOnClickListener(v ->
            layoutInput.setText(ExtraKeysBar.defaultLayoutJson()));
        layoutButtons.addView(loadDefaultBtn);

        Button loadFileBtn = new Button(this);
        loadFileBtn.setText(R.string.btn_load_file);
        loadFileBtn.setOnClickListener(v -> pickLayoutFile());
        layoutButtons.addView(loadFileBtn);

        root.addView(layoutButtons);

        TextView layoutHint = new TextView(this);
        layoutHint.setText(R.string.layout_hint);
        layoutHint.setTextSize(12);
        layoutHint.setTextColor(Color.GRAY);
        layoutHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(layoutHint);
    }

    // ============================================================
    // General page sections
    // ============================================================
    private void buildOrientationSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView header = new TextView(this);
        header.setText(R.string.section_orientation);
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, dp(8));
        root.addView(header);

        TextView label = new TextView(this);
        label.setText(R.string.orientation_label);
        label.setTextSize(14);
        label.setPadding(0, dp(8), 0, dp(4));
        root.addView(label);

        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            getResources().getStringArray(R.array.orientation_options)));

        String cur = prefs.getString(KEY_ORIENTATION, "default");
        int idx = 0;
        for (int i = 0; i < ORIENTATION_VALUES.length; i++) {
            if (ORIENTATION_VALUES[i].equals(cur)) { idx = i; break; }
        }
        spinner.setSelection(idx);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_ORIENTATION, ORIENTATION_VALUES[pos]).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(spinner);
    }

    private void buildNotificationSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView header = new TextView(this);
        header.setText(R.string.section_notification);
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, dp(8));
        root.addView(header);

        Switch notificationSwitch = new Switch(this);
        notificationSwitch.setText(R.string.notification_switch);
        notificationSwitch.setTextSize(14);
        notificationSwitch.setPadding(0, dp(8), 0, 0);
        notificationSwitch.setChecked(prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true));
        notificationSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_NOTIFICATION_ENABLED, checked).apply());
        root.addView(notificationSwitch);

        TextView notificationHint = new TextView(this);
        notificationHint.setText(R.string.notification_hint);
        notificationHint.setTextSize(12);
        notificationHint.setTextColor(Color.GRAY);
        notificationHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(notificationHint);
    }

    // ============================================================
    // ===== 触摸板设置区域 =====
    // ============================================================
    private void buildTouchpadSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 触摸板模式开关
        Switch touchpadModeSwitch = new Switch(this);
        touchpadModeSwitch.setText(R.string.touchpad_mode_switch);
        touchpadModeSwitch.setTextSize(14);
        touchpadModeSwitch.setPadding(0, dp(8), 0, 0);
        touchpadModeSwitch.setChecked(prefs.getBoolean(KEY_TOUCHPAD_MODE, false));
        touchpadModeSwitch.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(KEY_TOUCHPAD_MODE, checked).apply());
        root.addView(touchpadModeSwitch);

        TextView touchpadHint = new TextView(this);
        touchpadHint.setText(R.string.touchpad_hint);
        touchpadHint.setTextSize(12);
        touchpadHint.setTextColor(Color.GRAY);
        touchpadHint.setPadding(0, dp(4), 0, dp(12));
        root.addView(touchpadHint);

        // External mouse pointer capture.  This is opt-in because it changes
        // Android's mouse event mode from absolute coordinates to relative motion.
        Switch pointerCaptureSwitch = new Switch(this);
        pointerCaptureSwitch.setText(R.string.pointer_capture_switch);
        pointerCaptureSwitch.setTextSize(14);
        pointerCaptureSwitch.setPadding(0, dp(8), 0, 0);
        pointerCaptureSwitch.setChecked(prefs.getBoolean(KEY_POINTER_CAPTURE, false));
        pointerCaptureSwitch.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(KEY_POINTER_CAPTURE, checked).apply());
        root.addView(pointerCaptureSwitch);

        TextView pointerCaptureHint = new TextView(this);
        pointerCaptureHint.setText(R.string.pointer_capture_hint);
        pointerCaptureHint.setTextSize(12);
        pointerCaptureHint.setTextColor(Color.GRAY);
        pointerCaptureHint.setPadding(0, dp(4), 0, dp(12));
        root.addView(pointerCaptureHint);

        // 鼠标加速度（灵敏度）—— 范围 0.5 ~ 10.0
        LinearLayout accelLayout = new LinearLayout(this);
        accelLayout.setOrientation(LinearLayout.VERTICAL);
        accelLayout.setPadding(0, dp(8), 0, dp(16));

        TextView accelLabel = new TextView(this);
        accelLabel.setText(R.string.mouse_sensitivity_label);
        accelLabel.setTextSize(14);
        accelLayout.addView(accelLabel);

        final TextView accelValue = new TextView(this);
        accelValue.setTextSize(14);
        accelValue.setTextColor(Color.BLUE);
        accelLayout.addView(accelValue);

        SeekBar accelSeek = new SeekBar(this);
        accelSeek.setMax(190); // 0.5 ~ 10.0 step 0.05
        float curAccel = prefs.getFloat(KEY_MOUSE_ACCEL, 1.0f);
        curAccel = Math.max(0.5f, Math.min(10.0f, curAccel));
        accelSeek.setProgress((int)((curAccel - 0.5f) / 0.05f));
        accelValue.setText(getString(R.string.mouse_accel_value, curAccel));
        accelSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = 0.5f + progress * 0.05f;
                accelValue.setText(getString(R.string.mouse_accel_value, val));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putFloat(KEY_MOUSE_ACCEL, val).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        accelLayout.addView(accelSeek);
        root.addView(accelLayout);

        // ===== 双指滚动 =====
        Switch reverseScrollSwitch = new Switch(this);
        reverseScrollSwitch.setText(R.string.scroll_reverse_switch);
        reverseScrollSwitch.setTextSize(14);
        reverseScrollSwitch.setPadding(0, dp(8), 0, 0);
        reverseScrollSwitch.setChecked(prefs.getBoolean(KEY_SCROLL_REVERSE, false));
        reverseScrollSwitch.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(KEY_SCROLL_REVERSE, checked).apply());
        root.addView(reverseScrollSwitch);

        TextView reverseScrollHint = new TextView(this);
        reverseScrollHint.setText(R.string.scroll_reverse_hint);
        reverseScrollHint.setTextSize(12);
        reverseScrollHint.setTextColor(Color.GRAY);
        reverseScrollHint.setPadding(0, dp(4), 0, dp(12));
        root.addView(reverseScrollHint);

        addFloatSlider(root, R.string.scroll_speed_label, R.string.scroll_speed_value,
                null, KEY_SCROLL_SPEED, 0.05f, 3.0f, 0.05f, 0.5f);
        addFloatSlider(root, R.string.scroll_threshold_label,
                R.string.threshold_factor_value, R.string.scroll_threshold_hint,
                KEY_SCROLL_THRESHOLD, 0.05f, 3.0f, 0.05f, 0.5f);
        addFloatSlider(root, R.string.move_threshold_label,
                R.string.threshold_factor_value, R.string.move_threshold_hint,
                KEY_MOVE_THRESHOLD, 0.1f, 8.0f, 0.05f, 2.35f);
        addFloatSlider(root, R.string.gesture_scale_label, R.string.gesture_scale_value,
                R.string.gesture_scale_hint,
                KEY_GESTURE_SCALE, 100f, 3000f, 20f, 800f);
    }

    /**
     * A labelled slider over a float preference, with the live value beside the label
     * and an optional grey hint underneath.
     */
    private void addFloatSlider(LinearLayout root, int labelRes, int valueFormatRes,
                                Integer hintRes, final String key,
                                final float min, float max, final float step,
                                float defValue) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, dp(8), 0, hintRes == null ? dp(16) : 0);

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(14);
        layout.addView(label);

        final TextView value = new TextView(this);
        value.setTextSize(14);
        value.setTextColor(Color.BLUE);
        layout.addView(value);

        SeekBar seek = new SeekBar(this);
        seek.setMax(Math.round((max - min) / step));
        float cur = Math.max(min, Math.min(max, prefs.getFloat(key, defValue)));
        seek.setProgress(Math.round((cur - min) / step));
        value.setText(getString(valueFormatRes, cur));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = min + progress * step;
                value.setText(getString(valueFormatRes, val));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putFloat(key, val).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(seek);
        root.addView(layout);

        if (hintRes != null) {
            TextView hint = new TextView(this);
            hint.setText(hintRes);
            hint.setTextSize(12);
            hint.setTextColor(Color.GRAY);
            hint.setPadding(0, dp(2), 0, dp(12));
            root.addView(hint);
        }
    }

    // Connection settings: a custom daemon socket path and a "connect with root"
    // toggle. In root mode the app launches the bundled helper via `su -c`, which
    // connects to the socket and passes the fd back (see MainActivity).
    private void addConnectionSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Socket path
        TextView sockLabel = new TextView(this);
        sockLabel.setText(R.string.socket_path_label);
        sockLabel.setTextSize(14);
        sockLabel.setTextColor(Color.GRAY);
        sockLabel.setPadding(0, 0, 0, dp(4));
        root.addView(sockLabel);

        EditText socketInput = new EditText(this);
        socketInput.setSingleLine(true);
        socketInput.setText(prefs.getString(KEY_SOCKET_PATH, DEFAULT_SOCKET_PATH));
        socketInput.setHint(DEFAULT_SOCKET_PATH);
        socketInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_SOCKET_PATH, s.toString().trim()).apply();
            }
        });
        root.addView(socketInput);

        // Open a second window: an independent pipeline in the same process, targeting
        // its own daemon socket and shown with its own title. Launched as a new task
        // (freeform / split-screen) via SecondaryActivity.
        TextView secLabel = new TextView(this);
        secLabel.setText(R.string.second_window_label);
        secLabel.setTextSize(14);
        secLabel.setTextColor(Color.GRAY);
        secLabel.setPadding(0, dp(16), 0, dp(4));
        root.addView(secLabel);

        EditText secName = new EditText(this);
        secName.setSingleLine(true);
        secName.setHint(R.string.second_window_name_hint);
        root.addView(secName);

        EditText secSocket = new EditText(this);
        secSocket.setSingleLine(true);
        secSocket.setHint(DEFAULT_SOCKET_PATH);
        root.addView(secSocket);

        Button secOpen = new Button(this);
        secOpen.setText(R.string.second_window_open);
        secOpen.setOnClickListener(v -> {
            Intent i = new Intent(this, SecondaryActivity.class);
            String sp = secSocket.getText().toString().trim();
            String wn = secName.getText().toString().trim();
            if (!sp.isEmpty()) i.putExtra(MainActivity.EXTRA_SOCKET_PATH, sp);
            if (!wn.isEmpty()) i.putExtra(MainActivity.EXTRA_WINDOW_NAME, wn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(i);
        });
        root.addView(secOpen);

        // Connect with root
        Switch rootSwitch = new Switch(this);
        rootSwitch.setText(R.string.root_switch);
        rootSwitch.setTextSize(14);
        rootSwitch.setPadding(0, dp(16), 0, 0);
        rootSwitch.setChecked(prefs.getBoolean(KEY_USE_ROOT, true));
        rootSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_USE_ROOT, checked).apply());
        root.addView(rootSwitch);

        TextView rootHint = new TextView(this);
        rootHint.setText(R.string.root_hint);
        rootHint.setTextSize(12);
        rootHint.setTextColor(Color.GRAY);
        rootHint.setPadding(0, dp(4), 0, 0);
        root.addView(rootHint);

        // Forward microphone: capture the device mic and expose it to the Linux
        // desktop as a recording source. Requires the RECORD_AUDIO permission, which
        // MainActivity requests when this is on.
        Switch micSwitch = new Switch(this);
        micSwitch.setText(R.string.mic_switch);
        micSwitch.setTextSize(14);
        micSwitch.setPadding(0, dp(16), 0, 0);
        micSwitch.setChecked(prefs.getBoolean(KEY_MIC_ENABLED, false));
        micSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_MIC_ENABLED, checked).apply());
        root.addView(micSwitch);

        TextView micHint = new TextView(this);
        micHint.setText(R.string.mic_hint);
        micHint.setTextSize(12);
        micHint.setTextColor(Color.GRAY);
        micHint.setPadding(0, dp(4), 0, 0);
        root.addView(micHint);

        // Forward camera: expose the device camera(s) to the Linux desktop. When on,
        // the app pre-creates the camera service resources at startup (CameraX is only
        // opened once the desktop actually requests a recording). Requires the CAMERA
        // permission, which MainActivity requests when this is enabled.
        Switch cameraSwitch = new Switch(this);
        cameraSwitch.setText(R.string.camera_switch);
        cameraSwitch.setTextSize(14);
        cameraSwitch.setPadding(0, dp(16), 0, 0);
        cameraSwitch.setChecked(prefs.getBoolean(KEY_CAMERA_ENABLED, false));
        cameraSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_CAMERA_ENABLED, checked).apply());
        root.addView(cameraSwitch);

        TextView cameraHint = new TextView(this);
        cameraHint.setText(R.string.camera_hint);
        cameraHint.setTextSize(12);
        cameraHint.setTextColor(Color.GRAY);
        cameraHint.setPadding(0, dp(4), 0, 0);
        root.addView(cameraHint);

        // Audio keep-alive: keep the AAudio output stream running (fed near-silent
        // keepalive) so short Linux UI sounds (volume ticks, key clicks) always play
        // immediately. Off by default so the audio path can sleep when the desktop is
        // silent and save standby power.
        Switch keepaliveSwitch = new Switch(this);
        keepaliveSwitch.setText(R.string.audio_keepalive_switch);
        keepaliveSwitch.setTextSize(14);
        keepaliveSwitch.setPadding(0, dp(16), 0, 0);
        keepaliveSwitch.setChecked(prefs.getBoolean(KEY_AUDIO_KEEPALIVE, false));
        keepaliveSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUDIO_KEEPALIVE, checked).apply());
        root.addView(keepaliveSwitch);

        TextView keepaliveHint = new TextView(this);
        keepaliveHint.setText(R.string.audio_keepalive_hint);
        keepaliveHint.setTextSize(12);
        keepaliveHint.setTextColor(Color.GRAY);
        keepaliveHint.setPadding(0, dp(4), 0, 0);
        root.addView(keepaliveHint);

        // Audio latency presets, separately for the speaker (playback) and microphone
        // (capture) paths. The chosen buffer is forwarded to the producer's PipeWire
        // nodes; smaller = lower latency but more risk of audio glitches.
        TextView latTitle = new TextView(this);
        latTitle.setText(R.string.audio_latency_title);
        latTitle.setTextSize(15);
        latTitle.setTypeface(Typeface.DEFAULT_BOLD);
        latTitle.setPadding(0, dp(20), 0, 0);
        root.addView(latTitle);

        root.addView(makeLatencySpinner(getString(R.string.latency_speaker_label),
                                        KEY_SPEAKER_LATENCY_MS, prefs));
        root.addView(makeLatencySpinner(getString(R.string.latency_mic_label),
                                        KEY_MIC_LATENCY_MS, prefs));

        TextView latHint = new TextView(this);
        latHint.setText(R.string.latency_hint);
        latHint.setTextSize(12);
        latHint.setTextColor(Color.GRAY);
        latHint.setPadding(0, dp(4), 0, 0);
        root.addView(latHint);
    }

    private void addResolutionSection(LinearLayout root) {
    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

    // Width / height fields. Created first (but added below the preset picker) so
    // the picker can populate them; their TextWatchers are the single source of
    // truth that persists custom_width/custom_height.
    final EditText widthInput = new EditText(this);
    widthInput.setSingleLine(true);
    widthInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    widthInput.setHint(R.string.width_hint);
    widthInput.setText(String.valueOf(prefs.getInt("custom_width", 0)));
    widthInput.addTextChangedListener(new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(Editable s) {
            try {
                int w = Integer.parseInt(s.toString().trim());
                prefs.edit().putInt("custom_width", w).apply();
            } catch (NumberFormatException e) {}
        }
    });

    final EditText heightInput = new EditText(this);
    heightInput.setSingleLine(true);
    heightInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    heightInput.setHint(R.string.height_hint);
    heightInput.setText(String.valueOf(prefs.getInt("custom_height", 0)));
    heightInput.addTextChangedListener(new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(Editable s) {
            try {
                int h = Integer.parseInt(s.toString().trim());
                prefs.edit().putInt("custom_height", h).apply();
            } catch (NumberFormatException e) {}
        }
    });

    // Preset picker: fills width/height (which persist via their watchers). Index
    // 0 is a no-op placeholder so the Spinner's initial auto-selection and manual
    // edits leave the fields untouched.
    Spinner presetSpinner = new Spinner(this);
    presetSpinner.setAdapter(new ArrayAdapter<>(this,
        android.R.layout.simple_spinner_dropdown_item,
        getResources().getStringArray(R.array.res_preset_labels)));
    presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
            int[] wh = resolvePreset(pos);
            if (wh == null) return;
            widthInput.setText(String.valueOf(wh[0]));
            heightInput.setText(String.valueOf(wh[1]));
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    });
    root.addView(presetSpinner);

    root.addView(widthInput);
    root.addView(heightInput);

    TextView hint = new TextView(this);
    hint.setText(R.string.resolution_hint);
    hint.setTextSize(12);
    hint.setTextColor(Color.GRAY);
    hint.setPadding(0, dp(4), 0, 0);
    root.addView(hint);

    Switch autoStretchSwitch = new Switch(this);
    autoStretchSwitch.setText(R.string.auto_stretch_switch);
    autoStretchSwitch.setTextSize(14);
    autoStretchSwitch.setPadding(0, dp(16), 0, 0);
    autoStretchSwitch.setChecked(prefs.getBoolean("auto_stretch", true));
    autoStretchSwitch.setOnCheckedChangeListener((v, checked) ->
        prefs.edit().putBoolean("auto_stretch", checked).apply());
    root.addView(autoStretchSwitch);

    TextView autoStretchHint = new TextView(this);
    autoStretchHint.setText(R.string.auto_stretch_hint);
    autoStretchHint.setTextSize(12);
    autoStretchHint.setTextColor(Color.GRAY);
    autoStretchHint.setPadding(0, dp(4), 0, 0);
    root.addView(autoStretchHint);
    }

    // Maps a res_preset_labels index to {width, height}, or null for the index-0
    // placeholder. "Screen ×" presets are derived from the live panel size.
    private int[] resolvePreset(int pos) {
        switch (pos) {
            case 1: return new int[]{0, 0};
            case 2: return new int[]{3840, 2160};
            case 3: return new int[]{2560, 1440};
            case 4: return new int[]{1920, 1080};
            case 5: return new int[]{1280, 720};
            case 6: return new int[]{854, 480};
            case 7: return scaleScreen(1.0f);
            case 8: return scaleScreen(0.8f);
            case 9: return scaleScreen(0.75f);
            case 10: return scaleScreen(0.5f);
            case 11: return scaleScreen(0.25f);
            default: return null;
        }
    }

    // Scales the device panel by `f`, normalised to landscape (long side = width)
    // and rounded down to even dimensions, which compositors/encoders expect.
    private int[] scaleScreen(float f) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Rect b = wm.getMaximumWindowMetrics().getBounds();
        int longSide = Math.max(b.width(), b.height());
        int shortSide = Math.min(b.width(), b.height());
        int w = Math.round(longSide * f) & ~1;
        int h = Math.round(shortSide * f) & ~1;
        return new int[]{w, h};
    }

    /* A labelled latency picker that persists the selected preset (ms) under `key`. */
    private View makeLatencySpinner(String label, final String key, SharedPreferences prefs) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        box.addView(tv);

        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            getResources().getStringArray(R.array.latency_labels)));

        int cur = prefs.getInt(key, 0);
        int idx = 0;
        for (int i = 0; i < LATENCY_MS.length; i++) {
            if (LATENCY_MS[i] == cur) { idx = i; break; }
        }
        sp.setSelection(idx);

        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putInt(key, LATENCY_MS[pos]).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        box.addView(sp);
        return box;
    }

    /** Stop whichever row is counting down, leaving its binding untouched. */
    private void stopListening() {
        if (listeningBinding != null) {
            listeningBinding.cancel();
            listeningBinding = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (listeningBinding == null) return super.onKeyDown(keyCode, event);

        // Ignore generic Virtual Keyboard keycode (it's a placeholder)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return true;

        // The scan code is recorded alongside the key code: it is what the
        // immersive-mode root helper matches on, and it is the only identity a
        // key like Volume Up has once Android is out of the picture.
        listeningBinding.bind(keyCode, event.getScanCode());
        Log.i(TAG, "Bound keycode: " + keyCode + " scancode: " + event.getScanCode());
        return true;
    }

    // Launch the system document picker to load a layout JSON from any provider
    // (Downloads, Drive, etc.). Uses SAF, so no storage permission is required.
    private void pickLayoutFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
            new String[]{"application/json", "text/plain"});
        try {
            startActivityForResult(intent, REQ_PICK_LAYOUT);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.toast_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_LAYOUT || resultCode != RESULT_OK || data == null)
            return;
        Uri uri = data.getData();
        if (uri == null) return;
        String text = readTextFromUri(uri);
        if (text == null) {
            Toast.makeText(this, R.string.toast_read_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        // setText flows through the editor's TextWatcher, which persists + validates.
        if (layoutInput != null) layoutInput.setText(text);
    }

    private String readTextFromUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "readTextFromUri failed", e);
            return null;
        }
    }

    // Reflect the validity of the custom layout JSON inline under the editor.
    private void updateLayoutStatus(TextView status, String json) {
        if (json == null || json.trim().isEmpty()) {
            status.setText(R.string.layout_status_default);
            status.setTextColor(Color.GRAY);
            return;
        }
        String err = ExtraKeysBar.validateLayout(json);
        if (err == null) {
            status.setText(R.string.layout_status_valid);
            status.setTextColor(0xFF2E7D32);  // green
        } else {
            status.setText(getString(R.string.layout_status_invalid, err));
            status.setTextColor(0xFFC62828);  // red
        }
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // Read the extra-keys mode, migrating from the old two-switch prefs if needed.
    private String getExtraKeysMode(SharedPreferences prefs) {
        String mode = prefs.getString(KEY_EXTRA_KEYS_MODE, null);
        if (mode != null) return mode;
        // Migrate from legacy boolean keys
        boolean autoShow = prefs.getBoolean("auto_show_extra_keys", true);
        boolean enabled = prefs.getBoolean("extra_keys_bar", false);
        mode = autoShow ? MODE_WITH_KEYBOARD : (enabled ? MODE_ALWAYS : MODE_NEVER);
        prefs.edit().putString(KEY_EXTRA_KEYS_MODE, mode)
              .remove("auto_show_extra_keys").remove("extra_keys_bar").apply();
        return mode;
    }
}
