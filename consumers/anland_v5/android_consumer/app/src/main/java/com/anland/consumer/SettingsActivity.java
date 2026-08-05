package com.anland.consumer;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.util.Log;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.json.JSONObject;


public class SettingsActivity extends Activity {
    private static final String TAG = "AnlandSettings";
    private static final int FCL_ACCENT = 0xFF3478F6;
    private static final int FCL_ACCENT_SOFT = 0xFFEAF1FF;
    private static final int FCL_PAGE = 0xFFF2F5FA;
    private static final int FCL_CARD = 0xFFFFFFFF;
    private static final int FCL_BORDER = 0xFFE2E7F0;
    private static final int FCL_TEXT = 0xFF172033;
    private static final int FCL_MUTED = 0xFF687386;
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
    // FCL controller overlay (FCL-Controllers JSON files bundled in assets).
    private static final String KEY_FCL_CONTROLLER = "fcl_controller_id";
    private static final String KEY_FCL_CONTROLLER_PORTRAIT = "fcl_controller_id_portrait";
    private static final String DEFAULT_FCL_CONTROLLER_PORTRAIT = "00000001";
    private static final String KEY_FCL_EDIT_REQUESTED = "fcl_edit_requested";
    private static final String KEY_FCL_EDIT_TARGET = "fcl_edit_target";
    private static final String[] BUNDLED_CONTROLLER_IDS = {"00000000", "00000001", "899a1e2b"};
    // Bottom overlay mode: original extra-keys bar vs FCL controller (二选一).
    private static final String KEY_BOTTOM_MODE = "bottom_overlay_mode";
    private static final String[] BOTTOM_MODES = {"extra_keys", "fcl"};
    // FCL overlay lock / Back-toggle / one-shot editor request.
    private static final String KEY_FCL_ALWAYS = "fcl_always_foreground";
    private static final String KEY_NOTIFICATION_ENABLED = "settings_notification";
    private static final String KEY_ORIENTATION = "screen_orientation";
    private static final String[] ORIENTATION_VALUES = {"default", "landscape", "portrait"};
    private static final String DEFAULT_SOCKET_PATH = "/data/local/tmp/display_daemon.sock";
    private static final int UNBOUND = -1;
    private static final int REQ_IMPORT_CONTROLLER = 3001;
    private static final int REQ_EXPORT_CONTROLLER = 3002;

    // Which profile the six FCL action buttons operate on.
    private String manageTarget = "landscape";   // "landscape" / "portrait"

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
        applySettingsWindowChrome();
        showHome();
    }

    private void applySettingsWindowChrome() {
        getWindow().setStatusBarColor(FCL_PAGE);
        getWindow().setNavigationBarColor(FCL_PAGE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    // ============================================================
    // Navigation: a home list of categories, each opening a page.
    // Every page is a fresh LinearLayout wrapped by setContent().
    // ============================================================

    // Wrap content in the shared FCL-inspired page surface, apply edge-to-edge
    // insets, and install it. Reused by the home list and every secondary page.
    private void setContent(final LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(FCL_PAGE);
        content.setBackgroundColor(FCL_PAGE);
        scroll.addView(content);
        setContentView(scroll);

        // Edge-to-edge is enforced on Android 15+ (targetSdk 36): the system no
        // longer auto-resizes the window for the IME, so a manifest "adjustResize"
        // is ignored and the soft keyboard overlaps the bottom EditTexts. Take over
        // inset handling and pad the scrollable content by the system-bar + IME
        // insets ourselves, so the ScrollView can scroll the focused field above
        // the keyboard. Base padding is preserved on all edges.
        getWindow().setDecorFitsSystemWindows(false);
        final int base = dp(18);
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
        title.setTextSize(28);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(FCL_TEXT);
        title.setGravity(Gravity.START);
        title.setPadding(0, dp(4), 0, dp(3));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("显示、输入和连接设置");
        subtitle.setTextSize(13);
        subtitle.setTextColor(FCL_MUTED);
        subtitle.setPadding(0, 0, 0, dp(16));
        root.addView(subtitle);

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
        version.setTextColor(FCL_MUTED);
        version.setGravity(Gravity.START);
        version.setPadding(dp(4), dp(8), 0, dp(4));
        version.setAlpha(0.75f);
        root.addView(version);

        setContent(root);
    }

    // A tappable home card. It shares the same surface, border and accent rhythm
    // as the FCL controller settings instead of the old platform-preference look.
    private void addCategoryRow(LinearLayout parent, int titleRes, int subtitleRes,
                                final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(fclRipple(FCL_CARD, 16, FCL_BORDER, 1));
        row.setOnClickListener(v -> onClick.run());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(titleRes);
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(FCL_TEXT);
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitleRes);
        s.setTextSize(12);
        s.setTextColor(FCL_MUTED);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);

        row.addView(texts);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(26);
        chevron.setTextColor(FCL_ACCENT);
        row.addView(chevron);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(10));
        parent.addView(row, rowParams);
    }

    // A fresh page root with a back link and a bold page title.
    private LinearLayout newPage(int titleRes) {
        stopListening();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView back = new TextView(this);
        back.setText("‹ " + getString(R.string.nav_back));
        back.setTextSize(14);
        back.setTypeface(null, Typeface.BOLD);
        back.setTextColor(FCL_ACCENT);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(10), 0, dp(10), 0);
        back.setClickable(true);
        back.setFocusable(true);
        back.setBackground(fclRipple(FCL_ACCENT_SOFT, 10, FCL_ACCENT_SOFT, 0));
        back.setOnClickListener(v -> showHome());
        root.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(26);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(FCL_TEXT);
        title.setGravity(Gravity.START);
        title.setPadding(0, dp(14), 0, dp(16));
        root.addView(title);

        return root;
    }

    private void showKeyboardPage() {
        currentPage = Page.KEYBOARD;
        LinearLayout root = newPage(R.string.cat_keyboard_title);
        buildVirtualKeyboardSection(root);
        buildFclSection(root);
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

    // ============================================================
    // FCL controller overlay (FoldCraftLauncher keyboard controls)
    // ============================================================
    private GradientDrawable fclRounded(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0) {
            drawable.setStroke(dp(strokeWidth), strokeColor);
        }
        return drawable;
    }

    private Drawable fclRipple(int color, int radius, int strokeColor, int strokeWidth) {
        return new RippleDrawable(ColorStateList.valueOf(0x1F3478F6),
                fclRounded(color, radius, strokeColor, strokeWidth),
                fclRounded(Color.WHITE, radius, Color.TRANSPARENT, 0));
    }

    private LinearLayout fclCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(fclRounded(FCL_CARD, 16, FCL_BORDER, 1));
        return card;
    }

    private void addFclCard(LinearLayout parent, LinearLayout card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        parent.addView(card, params);
    }

    private TextView fclCardTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(FCL_TEXT);
        return title;
    }

    private LinearLayout addSettingsCard(LinearLayout parent, String title, String hint) {
        LinearLayout card = fclCard();
        if (title != null && !title.isEmpty()) {
            card.addView(fclCardTitle(title));
        }
        if (hint != null && !hint.isEmpty()) {
            card.addView(fclCardHint(hint));
        }
        addFclCard(parent, card);
        return card;
    }

    private TextView fclSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setTypeface(null, Typeface.BOLD);
        label.setTextColor(FCL_MUTED);
        label.setLetterSpacing(0.04f);
        label.setPadding(dp(2), dp(8), 0, dp(8));
        return label;
    }

    private TextView fclFieldLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(FCL_MUTED);
        label.setPadding(0, dp(8), 0, dp(5));
        return label;
    }

    private EditText fclSettingsInput() {
        EditText input = new EditText(this);
        input.setTextSize(14);
        input.setTextColor(FCL_TEXT);
        input.setHintTextColor(FCL_MUTED);
        input.setPadding(dp(12), dp(7), dp(12), dp(7));
        input.setMinimumHeight(dp(46));
        input.setBackground(fclRounded(FCL_CARD, 11, FCL_BORDER, 1));
        return input;
    }

    private void styleFclSwitch(Switch control) {
        control.setShowText(false);
        control.setMinWidth(dp(52));
        control.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{FCL_ACCENT, 0xFFF5F7FB}));
        control.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0x663478F6, 0xFFB9C2D0}));
    }

    private void styleFclSeekBar(SeekBar seekBar) {
        seekBar.setProgressTintList(ColorStateList.valueOf(FCL_ACCENT));
        seekBar.setThumbTintList(ColorStateList.valueOf(FCL_ACCENT));
    }

    private TextView fclCardHint(String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextSize(12);
        hint.setTextColor(FCL_MUTED);
        hint.setLineSpacing(0, 1.15f);
        hint.setPadding(0, dp(3), 0, dp(10));
        return hint;
    }

    private void addFclDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(FCL_BORDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.setMargins(0, dp(10), 0, dp(10));
        parent.addView(divider, params);
    }

    private LinearLayout fclSwitchRow(String titleText, String hintText, Switch control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(15);
        title.setTextColor(FCL_TEXT);
        texts.addView(title);
        TextView hint = new TextView(this);
        hint.setText(hintText);
        hint.setTextSize(12);
        hint.setTextColor(FCL_MUTED);
        hint.setPadding(0, dp(3), dp(12), 0);
        texts.addView(hint);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        styleFclSwitch(control);
        row.addView(control);
        row.setClickable(true);
        row.setOnClickListener(v -> control.setChecked(!control.isChecked()));
        return row;
    }

    private Button fclActionButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(null, primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(primary ? Color.WHITE : FCL_ACCENT);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setBackground(fclRipple(primary ? FCL_ACCENT : Color.WHITE, 11,
                primary ? FCL_ACCENT : FCL_BORDER, 1));
        return button;
    }

    private Button fclSegmentButton(String text, boolean selected) {
        Button button = fclActionButton(text, false);
        button.setTextSize(13);
        setFclSegmentSelected(button, selected);
        return button;
    }

    private void setFclSegmentSelected(Button button, boolean selected) {
        button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(selected ? Color.WHITE : FCL_MUTED);
        button.setBackground(fclRipple(selected ? FCL_ACCENT : Color.WHITE, 11,
                selected ? FCL_ACCENT : FCL_BORDER, 1));
    }

    private Spinner fclSettingsSpinner(List<String> values) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, values) {
            private TextView style(View view, boolean dropdown) {
                TextView text = (TextView) view;
                text.setTextSize(14);
                text.setTextColor(FCL_TEXT);
                text.setGravity(Gravity.CENTER_VERTICAL);
                text.setMinHeight(dp(dropdown ? 46 : 44));
                text.setPadding(dp(12), dp(8), dp(12), dp(8));
                if (dropdown) {
                    text.setBackgroundColor(FCL_CARD);
                }
                return text;
            }

            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return style(super.getView(position, convertView, parent), false);
            }

            @Override
            public View getDropDownView(int position, View convertView,
                                        android.view.ViewGroup parent) {
                return style(super.getDropDownView(position, convertView, parent), true);
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(dp(46));
        spinner.setBackground(fclRipple(FCL_CARD, 11, FCL_BORDER, 1));
        spinner.setPopupBackgroundDrawable(fclRounded(FCL_CARD, 11, FCL_BORDER, 1));
        return spinner;
    }

    private Spinner fclSettingsSpinner(String[] values) {
        return fclSettingsSpinner(Arrays.asList(values));
    }

    private void buildFclSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        root.addView(fclSectionLabel(getString(R.string.section_fcl_controller)));

        // Display behaviour: a switch is clearer than the old two-item spinner.
        LinearLayout displayCard = fclCard();
        displayCard.addView(fclCardTitle("显示方式"));
        displayCard.addView(fclCardHint("启用后，FCL 控制器会替代底部扩展按键栏。"));
        Switch enableSwitch = new Switch(this);
        enableSwitch.setChecked("fcl".equals(prefs.getString(
                KEY_BOTTOM_MODE, BOTTOM_MODES[0])));
        LinearLayout enableRow = fclSwitchRow("启用 FCL 覆盖层",
                "关闭后恢复原来的扩展按键栏", enableSwitch);
        displayCard.addView(enableRow);
        addFclDivider(displayCard);

        Switch alwaysSwitch = new Switch(this);
        alwaysSwitch.setChecked(prefs.getBoolean(KEY_FCL_ALWAYS, false));
        LinearLayout alwaysRow = fclSwitchRow("始终显示", "开启后返回键不会隐藏控制器", alwaysSwitch);
        displayCard.addView(alwaysRow);
        Runnable updateDisplayState = () -> {
            boolean enabled = enableSwitch.isChecked();
            alwaysSwitch.setEnabled(enabled);
            alwaysRow.setClickable(enabled);
            alwaysRow.setAlpha(enabled ? 1f : 0.45f);
        };
        enableSwitch.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putString(KEY_BOTTOM_MODE, checked ? "fcl" : "extra_keys").apply();
            updateDisplayState.run();
        });
        alwaysSwitch.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(KEY_FCL_ALWAYS, checked).apply());
        updateDisplayState.run();
        addFclCard(root, displayCard);

        // One selected target and one profile picker replace the old landscape /
        // portrait / management-target trio.  Every action below follows this target.
        final List<String> controllerIds = availableControllerIds();
        List<String> names = new ArrayList<>();
        for (String cid : controllerIds) {
            names.add(controllerDisplayName(cid));
        }
        LinearLayout layoutCard = fclCard();
        layoutCard.addView(fclCardTitle("布局配置"));
        layoutCard.addView(fclCardHint("先选择横屏或竖屏；后面的布局与操作只作用于该方向。"));

        LinearLayout targetTabs = new LinearLayout(this);
        targetTabs.setOrientation(LinearLayout.HORIZONTAL);
        Button landscapeTab = fclSegmentButton("横屏", !"portrait".equals(manageTarget));
        Button portraitTab = fclSegmentButton("竖屏", "portrait".equals(manageTarget));
        targetTabs.addView(landscapeTab, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams portraitTabParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        portraitTabParams.setMarginStart(dp(8));
        targetTabs.addView(portraitTab, portraitTabParams);
        layoutCard.addView(targetTabs);

        TextView profileLabel = new TextView(this);
        profileLabel.setTextSize(13);
        profileLabel.setTextColor(FCL_MUTED);
        profileLabel.setPadding(0, dp(14), 0, dp(4));
        layoutCard.addView(profileLabel);
        Spinner profileSpinner = fclSettingsSpinner(names);
        layoutCard.addView(profileSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button editBtn = fclActionButton("编辑当前布局", true);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        editParams.setMargins(0, dp(14), 0, 0);
        layoutCard.addView(editBtn, editParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        actionsParams.setMargins(0, dp(8), 0, 0);
        layoutCard.addView(actions, actionsParams);
        Button addBtn = fclActionButton("新建", false);
        Button importBtn = fclActionButton("导入", false);
        Button moreBtn = fclActionButton("更多", false);
        actions.addView(addBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        importParams.setMarginStart(dp(8));
        actions.addView(importBtn, importParams);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        moreParams.setMarginStart(dp(8));
        actions.addView(moreBtn, moreParams);

        final Runnable refreshTargetUi = () -> {
            boolean portrait = "portrait".equals(manageTarget);
            setFclSegmentSelected(landscapeTab, !portrait);
            setFclSegmentSelected(portraitTab, portrait);
            profileLabel.setText(portrait ? "当前竖屏布局" : "当前横屏布局");
            editBtn.setText(portrait ? "编辑竖屏布局" : "编辑横屏布局");
            String currentId = manageTargetControllerId();
            int selection = 0;
            for (int i = 0; i < controllerIds.size(); i++) {
                if (controllerIds.get(i).equals(currentId)) {
                    selection = i;
                    break;
                }
            }
            profileSpinner.setSelection(selection);
        };
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos >= 0 && pos < controllerIds.size()) {
                    prefs.edit().putString(manageTargetKey(), controllerIds.get(pos)).apply();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        landscapeTab.setOnClickListener(v -> {
            manageTarget = "landscape";
            refreshTargetUi.run();
        });
        portraitTab.setOnClickListener(v -> {
            manageTarget = "portrait";
            refreshTargetUi.run();
        });
        editBtn.setOnClickListener(v -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_FCL_EDIT_REQUESTED, true)
                    .putString(KEY_FCL_EDIT_TARGET, manageTarget).apply();
            finish();
        });
        addBtn.setOnClickListener(v -> promptAddController());
        importBtn.setOnClickListener(v -> launchFclImport());
        moreBtn.setOnClickListener(v -> showFclMoreActions());
        refreshTargetUi.run();
        addFclCard(root, layoutCard);
    }

    // ============================================================
    private String manageTargetKey() {
        return "portrait".equals(manageTarget) ? KEY_FCL_CONTROLLER_PORTRAIT : KEY_FCL_CONTROLLER;
    }

    private String manageTargetDefault() {
        return "portrait".equals(manageTarget)
                ? DEFAULT_FCL_CONTROLLER_PORTRAIT : BUNDLED_CONTROLLER_IDS[0];
    }

    private String manageTargetControllerId() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(manageTargetKey(), manageTargetDefault());
    }

    private void launchFclImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQ_IMPORT_CONTROLLER);
    }

    private void launchFclExport() {
        String controllerId = manageTargetControllerId();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, controllerId + ".json");
        startActivityForResult(intent, REQ_EXPORT_CONTROLLER);
    }

    private void resetManagedFclLayout() {
        String controllerId = manageTargetControllerId();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove("fcl_pos_" + controllerId).apply();
        Toast.makeText(this, R.string.fcl_reset_done, Toast.LENGTH_SHORT).show();
    }

    private void showFclMoreActions() {
        String direction = "portrait".equals(manageTarget) ? "竖屏" : "横屏";
        new AlertDialog.Builder(this)
                .setTitle(direction + "布局操作")
                .setItems(new String[]{"导出当前布局", "重置布局位置", "删除当前布局"},
                        (dialog, which) -> {
                            if (which == 0) {
                                launchFclExport();
                            } else if (which == 1) {
                                resetManagedFclLayout();
                            } else {
                                promptDeleteController();
                            }
                        })
                .show();
    }

    private void promptAddController() {
        EditText nameInput = fclSettingsInput();
        nameInput.setHint("控制器名称");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), dp(4));
        wrap.addView(nameInput);
        new AlertDialog.Builder(this)
                .setTitle("新增控制器")
                .setView(wrap)
                .setPositiveButton("创建", (d, w) -> {
                    FclController src = FclController.load(this, manageTargetControllerId());
                    if (src == null) {
                        Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String newId = FclController.createCopy(this, src,
                            nameInput.getText().toString());
                    if (newId != null) {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putString(manageTargetKey(), newId).apply();
                        Toast.makeText(this, "已创建并切换", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptDeleteController() {
        String id = manageTargetControllerId();
        FclController ctrl = FclController.load(this, id);
        String name = ctrl != null ? ctrl.name : id;
        new AlertDialog.Builder(this)
                .setTitle("删除控制器")
                .setMessage("删除 “" + name + "”？")
                .setPositiveButton("删除", (d, w) -> {
                    if (ctrl != null && ctrl.isBundled(this)) {
                        Toast.makeText(this, "内置控制器不能删除", Toast.LENGTH_SHORT).show();
                    } else {
                        FclController.deleteFromDisk(this, id);
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putString(manageTargetKey(), manageTargetDefault())
                                .apply();
                        Toast.makeText(this, "已删除，恢复默认", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // FCL controller import / export
    // ============================================================

    private List<String> availableControllerIds() {
        LinkedHashSet<String> ids =
                new LinkedHashSet<>(Arrays.asList(BUNDLED_CONTROLLER_IDS));
        File dir = new File(getFilesDir(), "fcl_controllers");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (name.endsWith(".json")) {
                    ids.add(name.substring(0, name.length() - 5));
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private String controllerDisplayName(String id) {
        try {
            JSONObject obj = new JSONObject(
                    new String(readControllerBytes(id), StandardCharsets.UTF_8));
            String name = obj.optString("name", "");
            return name.isEmpty() || name.equals(id) ? id : name + " (" + id + ")";
        } catch (Exception e) {
            return id;
        }
    }

    private byte[] readControllerBytes(String id) throws IOException {
        File f = new File(getFilesDir(), "fcl_controllers/" + id + ".json");
        if (f.isFile()) {
            return readAll(new FileInputStream(f));
        }
        return readAll(getAssets().open("fcl_controllers/" + id + ".json"));
    }

    private byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void importController(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                Toast.makeText(this, R.string.fcl_import_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            byte[] bytes = readAll(in);
            JSONObject obj = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String id = obj.optString("id", "");
            if (id.isEmpty() || !id.matches("[A-Za-z0-9_-]+")) {
                Toast.makeText(this, R.string.fcl_import_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            File dir = new File(getFilesDir(), "fcl_controllers");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Toast.makeText(this, R.string.fcl_import_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            File out = new File(dir, id + ".json");
            FileOutputStream fos = new FileOutputStream(out);
            try {
                fos.write(bytes);
            } finally {
                fos.close();
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(manageTargetKey(), id).apply();
            Toast.makeText(this, R.string.fcl_import_done, Toast.LENGTH_SHORT).show();
            showKeyboardPage();   // refresh the controller list
        } catch (Exception e) {
            Log.e(TAG, "import controller failed", e);
            Toast.makeText(this, R.string.fcl_import_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void exportController(Uri uri) {
        try {
            String id = manageTargetControllerId();
            byte[] bytes = readControllerBytes(id);
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) {
                try {
                    os.write(bytes);
                } finally {
                    os.close();
                }
            }
            Toast.makeText(this, R.string.fcl_export_done, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "export controller failed", e);
            Toast.makeText(this, R.string.fcl_export_failed, Toast.LENGTH_SHORT).show();
        }
    }

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

        LinearLayout card = addSettingsCard(root, "按键兼容", "为 Fn、功能键等物理按键提供稳定的拦截路径。");
        Switch accessibilitySwitch = new Switch(this);
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
        card.addView(fclSwitchRow(getString(R.string.accessibility_switch),
                getString(R.string.accessibility_hint), accessibilitySwitch));
    }

    private void buildExtraKeysSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        LinearLayout card = addSettingsCard(root, getString(R.string.section_extra_keys),
                "FCL 覆盖层关闭时使用的传统扩展按键栏。");

        card.addView(fclFieldLabel(getString(R.string.extra_keys_mode_label)));

        Spinner modeSpinner = fclSettingsSpinner(
                getResources().getStringArray(R.array.extra_keys_mode_options));

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
        card.addView(modeSpinner);

        TextView modeHint = fclCardHint(getString(R.string.extra_keys_mode_hint));
        modeHint.setPadding(0, dp(5), 0, 0);
        card.addView(modeHint);
        addFclDivider(card);

        Switch backOpensExtraKeysSwitch = new Switch(this);
        backOpensExtraKeysSwitch.setChecked(prefs.getBoolean(KEY_BACK_OPENS_EXTRA_KEYS, true));
        backOpensExtraKeysSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_BACK_OPENS_EXTRA_KEYS, checked).apply());
        card.addView(fclSwitchRow(getString(R.string.back_opens_switch),
                getString(R.string.back_opens_hint), backOpensExtraKeysSwitch));
        addFclDivider(card);

        Switch keyboardFloatingSwitch = new Switch(this);
        keyboardFloatingSwitch.setChecked(prefs.getBoolean(KEY_KEYBOARD_FLOATING, true));
        keyboardFloatingSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_KEYBOARD_FLOATING, checked).apply());
        card.addView(fclSwitchRow(getString(R.string.keyboard_floating_switch),
                getString(R.string.keyboard_floating_hint), keyboardFloatingSwitch));
    }

    private void buildCustomLayoutSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        LinearLayout card = addSettingsCard(root, getString(R.string.section_custom_layout),
                "直接编辑传统扩展按键栏的 JSON 布局；保存会立即写入设置。");

        layoutInput = fclSettingsInput();
        layoutInput.setTypeface(Typeface.MONOSPACE);
        layoutInput.setTextSize(12);
        layoutInput.setGravity(Gravity.TOP | Gravity.START);
        layoutInput.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        layoutInput.setHorizontallyScrolling(false);
        layoutInput.setMinLines(6);
        layoutInput.setMinHeight(dp(168));
        String savedLayout = prefs.getString(KEY_EXTRA_KEYS_LAYOUT, "");
        if (savedLayout.isEmpty()) savedLayout = ExtraKeysBar.defaultLayoutJson();
        layoutInput.setText(savedLayout);
        card.addView(layoutInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView layoutStatus = new TextView(this);
        layoutStatus.setTextSize(12);
        layoutStatus.setPadding(0, dp(7), 0, dp(5));
        card.addView(layoutStatus);
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
        LinearLayout.LayoutParams layoutButtonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        layoutButtonsParams.setMargins(0, dp(5), 0, 0);

        Button loadDefaultBtn = fclActionButton(getString(R.string.btn_load_default), false);
        loadDefaultBtn.setText(R.string.btn_load_default);
        loadDefaultBtn.setOnClickListener(v ->
            layoutInput.setText(ExtraKeysBar.defaultLayoutJson()));
        layoutButtons.addView(loadDefaultBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        Button loadFileBtn = fclActionButton(getString(R.string.btn_load_file), true);
        loadFileBtn.setText(R.string.btn_load_file);
        loadFileBtn.setOnClickListener(v -> pickLayoutFile());
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        fileParams.setMarginStart(dp(8));
        layoutButtons.addView(loadFileBtn, fileParams);

        card.addView(layoutButtons, layoutButtonsParams);

        TextView layoutHint = fclCardHint(getString(R.string.layout_hint));
        layoutHint.setPadding(0, dp(9), 0, 0);
        card.addView(layoutHint);
    }

    // ============================================================
    // General page sections
    // ============================================================
    private void buildOrientationSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        LinearLayout card = addSettingsCard(root, getString(R.string.section_orientation),
                "设定 Anland 的默认显示方向。临时横屏按钮不会修改这里的选项。");
        card.addView(fclFieldLabel(getString(R.string.orientation_label)));

        Spinner spinner = fclSettingsSpinner(
                getResources().getStringArray(R.array.orientation_options));

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
        card.addView(spinner);
    }

    private void buildNotificationSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        LinearLayout card = addSettingsCard(root, getString(R.string.section_notification),
                "在系统通知栏保留一个快捷入口，以便随时打开设置。");
        Switch notificationSwitch = new Switch(this);
        notificationSwitch.setChecked(prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true));
        notificationSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_NOTIFICATION_ENABLED, checked).apply());
        card.addView(fclSwitchRow(getString(R.string.notification_switch),
                getString(R.string.notification_hint), notificationSwitch));
    }

    // ============================================================
    // ===== 触摸板设置区域 =====
    // ============================================================
    private void buildTouchpadSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        LinearLayout inputCard = addSettingsCard(root, "触控与鼠标",
                "选择触屏和外接鼠标在桌面中的输入方式。");
        Switch touchpadModeSwitch = new Switch(this);
        touchpadModeSwitch.setChecked(prefs.getBoolean(KEY_TOUCHPAD_MODE, false));
        touchpadModeSwitch.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(KEY_TOUCHPAD_MODE, checked).apply());
        inputCard.addView(fclSwitchRow(getString(R.string.touchpad_mode_switch),
                getString(R.string.touchpad_hint), touchpadModeSwitch));
        addFclDivider(inputCard);

        Switch pointerCaptureSwitch = new Switch(this);
        pointerCaptureSwitch.setChecked(prefs.getBoolean(KEY_POINTER_CAPTURE, false));
        pointerCaptureSwitch.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(KEY_POINTER_CAPTURE, checked).apply());
        inputCard.addView(fclSwitchRow(getString(R.string.pointer_capture_switch),
                getString(R.string.pointer_capture_hint), pointerCaptureSwitch));

        LinearLayout tuningCard = addSettingsCard(root, "手势与灵敏度",
                "这些数值会即时保存，并在返回桌面后生效。");
        LinearLayout accelLayout = new LinearLayout(this);
        accelLayout.setOrientation(LinearLayout.VERTICAL);
        accelLayout.setPadding(0, 0, 0, dp(8));

        LinearLayout accelHeader = new LinearLayout(this);
        accelHeader.setOrientation(LinearLayout.HORIZONTAL);
        accelHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView accelLabel = new TextView(this);
        accelLabel.setText(R.string.mouse_sensitivity_label);
        accelLabel.setTextSize(15);
        accelLabel.setTextColor(FCL_TEXT);
        accelHeader.addView(accelLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView accelValue = new TextView(this);
        accelValue.setTextSize(13);
        accelValue.setTypeface(null, Typeface.BOLD);
        accelValue.setTextColor(FCL_ACCENT);
        accelHeader.addView(accelValue);
        accelLayout.addView(accelHeader);

        SeekBar accelSeek = new SeekBar(this);
        styleFclSeekBar(accelSeek);
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
        tuningCard.addView(accelLayout);
        addFclDivider(tuningCard);

        Switch reverseScrollSwitch = new Switch(this);
        reverseScrollSwitch.setChecked(prefs.getBoolean(KEY_SCROLL_REVERSE, false));
        reverseScrollSwitch.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(KEY_SCROLL_REVERSE, checked).apply());
        tuningCard.addView(fclSwitchRow(getString(R.string.scroll_reverse_switch),
                getString(R.string.scroll_reverse_hint), reverseScrollSwitch));
        addFclDivider(tuningCard);

        addFloatSlider(tuningCard, R.string.scroll_speed_label, R.string.scroll_speed_value,
                null, KEY_SCROLL_SPEED, 0.05f, 3.0f, 0.05f, 0.5f);
        addFloatSlider(tuningCard, R.string.scroll_threshold_label,
                R.string.threshold_factor_value, R.string.scroll_threshold_hint,
                KEY_SCROLL_THRESHOLD, 0.05f, 3.0f, 0.05f, 0.5f);
        addFloatSlider(tuningCard, R.string.move_threshold_label,
                R.string.threshold_factor_value, R.string.move_threshold_hint,
                KEY_MOVE_THRESHOLD, 0.1f, 8.0f, 0.05f, 2.35f);
        addFloatSlider(tuningCard, R.string.gesture_scale_label, R.string.gesture_scale_value,
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
        layout.setPadding(0, dp(6), 0, hintRes == null ? dp(10) : 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(15);
        label.setTextColor(FCL_TEXT);
        header.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(this);
        value.setTextSize(13);
        value.setTypeface(null, Typeface.BOLD);
        value.setTextColor(FCL_ACCENT);
        header.addView(value);
        layout.addView(header);

        SeekBar seek = new SeekBar(this);
        styleFclSeekBar(seek);
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
            TextView hint = fclCardHint(getString(hintRes));
            hint.setPadding(0, 0, 0, dp(7));
            root.addView(hint);
        }
    }

    // Connection settings: a custom daemon socket path and a "connect with root"
    // toggle. In root mode the app launches the bundled helper via `su -c`, which
    // connects to the socket and passes the fd back (see MainActivity).
    private void addConnectionSection(LinearLayout root) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        LinearLayout socketCard = addSettingsCard(root, "连接", "配置当前桌面实例连接的 Unix Socket。");

        TextView sockLabel = fclFieldLabel(getString(R.string.socket_path_label));
        socketCard.addView(sockLabel);

        EditText socketInput = fclSettingsInput();
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
        socketCard.addView(socketInput);

        // Open a second window: an independent pipeline in the same process, targeting
        // its own daemon socket and shown with its own title. Launched as a new task
        // (freeform / split-screen) via SecondaryActivity.
        LinearLayout secondCard = addSettingsCard(root, getString(R.string.second_window_label),
                "使用独立连接在分屏或自由窗口中打开另一个桌面。");
        TextView secLabel = fclFieldLabel(getString(R.string.second_window_label));
        secondCard.addView(secLabel);

        EditText secName = fclSettingsInput();
        secName.setSingleLine(true);
        secName.setHint(R.string.second_window_name_hint);
        secondCard.addView(secName);

        EditText secSocket = fclSettingsInput();
        secSocket.setSingleLine(true);
        secSocket.setHint(DEFAULT_SOCKET_PATH);
        LinearLayout.LayoutParams secondSocketParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        secondSocketParams.setMargins(0, dp(8), 0, 0);
        secondCard.addView(secSocket, secondSocketParams);

        Button secOpen = fclActionButton(getString(R.string.second_window_open), true);
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
        LinearLayout.LayoutParams secOpenParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        secOpenParams.setMargins(0, dp(12), 0, 0);
        secondCard.addView(secOpen, secOpenParams);

        LinearLayout runtimeCard = addSettingsCard(root, "权限与设备", "连接方式与桌面可使用的本机设备。");
        Switch rootSwitch = new Switch(this);
        rootSwitch.setChecked(prefs.getBoolean(KEY_USE_ROOT, true));
        rootSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_USE_ROOT, checked).apply());
        runtimeCard.addView(fclSwitchRow(getString(R.string.root_switch),
                getString(R.string.root_hint), rootSwitch));
        addFclDivider(runtimeCard);

        Switch micSwitch = new Switch(this);
        micSwitch.setChecked(prefs.getBoolean(KEY_MIC_ENABLED, false));
        micSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_MIC_ENABLED, checked).apply());
        runtimeCard.addView(fclSwitchRow(getString(R.string.mic_switch),
                getString(R.string.mic_hint), micSwitch));
        addFclDivider(runtimeCard);

        Switch cameraSwitch = new Switch(this);
        cameraSwitch.setChecked(prefs.getBoolean(KEY_CAMERA_ENABLED, false));
        cameraSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_CAMERA_ENABLED, checked).apply());
        runtimeCard.addView(fclSwitchRow(getString(R.string.camera_switch),
                getString(R.string.camera_hint), cameraSwitch));
        addFclDivider(runtimeCard);

        Switch keepaliveSwitch = new Switch(this);
        keepaliveSwitch.setChecked(prefs.getBoolean(KEY_AUDIO_KEEPALIVE, false));
        keepaliveSwitch.setOnCheckedChangeListener((v, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUDIO_KEEPALIVE, checked).apply());
        runtimeCard.addView(fclSwitchRow(getString(R.string.audio_keepalive_switch),
                getString(R.string.audio_keepalive_hint), keepaliveSwitch));

        LinearLayout latencyCard = addSettingsCard(root, getString(R.string.audio_latency_title),
                getString(R.string.latency_hint));

        latencyCard.addView(makeLatencySpinner(getString(R.string.latency_speaker_label),
                                        KEY_SPEAKER_LATENCY_MS, prefs));
        latencyCard.addView(makeLatencySpinner(getString(R.string.latency_mic_label),
                                        KEY_MIC_LATENCY_MS, prefs));
    }

    private void addResolutionSection(LinearLayout root) {
    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    LinearLayout resolutionCard = addSettingsCard(root, "画面尺寸",
            "选择常用分辨率，或直接填写桌面宽度和高度。");

    // Width / height fields. Created first (but added below the preset picker) so
    // the picker can populate them; their TextWatchers are the single source of
    // truth that persists custom_width/custom_height.
    final EditText widthInput = fclSettingsInput();
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

    final EditText heightInput = fclSettingsInput();
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
    Spinner presetSpinner = fclSettingsSpinner(
        getResources().getStringArray(R.array.res_preset_labels));
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
    resolutionCard.addView(fclFieldLabel("分辨率预设"));
    resolutionCard.addView(presetSpinner);
    resolutionCard.addView(fclFieldLabel("自定义尺寸"));
    LinearLayout sizeRow = new LinearLayout(this);
    sizeRow.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout widthBox = new LinearLayout(this);
    widthBox.setOrientation(LinearLayout.VERTICAL);
    widthBox.addView(fclFieldLabel(getString(R.string.width_hint)));
    widthBox.addView(widthInput);
    sizeRow.addView(widthBox, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    LinearLayout heightBox = new LinearLayout(this);
    heightBox.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams heightBoxParams = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    heightBoxParams.setMarginStart(dp(8));
    heightBox.addView(fclFieldLabel(getString(R.string.height_hint)));
    heightBox.addView(heightInput);
    sizeRow.addView(heightBox, heightBoxParams);
    resolutionCard.addView(sizeRow);

    TextView hint = fclCardHint(getString(R.string.resolution_hint));
    hint.setPadding(0, dp(9), 0, 0);
    resolutionCard.addView(hint);

    LinearLayout scalingCard = addSettingsCard(root, "画面缩放", "控制桌面如何填充当前窗口。");
    Switch autoStretchSwitch = new Switch(this);
    autoStretchSwitch.setChecked(prefs.getBoolean("auto_stretch", true));
    autoStretchSwitch.setOnCheckedChangeListener((v, checked) ->
        prefs.edit().putBoolean("auto_stretch", checked).apply());
    scalingCard.addView(fclSwitchRow(getString(R.string.auto_stretch_switch),
            getString(R.string.auto_stretch_hint), autoStretchSwitch));
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
        box.setPadding(0, dp(5), 0, 0);

        TextView tv = fclFieldLabel(label);
        box.addView(tv);

        Spinner sp = fclSettingsSpinner(getResources().getStringArray(R.array.latency_labels));

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
        if (requestCode == REQ_IMPORT_CONTROLLER || requestCode == REQ_EXPORT_CONTROLLER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                if (requestCode == REQ_IMPORT_CONTROLLER) {
                    importController(uri);
                } else {
                    exportController(uri);
                }
            }
            return;
        }
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
