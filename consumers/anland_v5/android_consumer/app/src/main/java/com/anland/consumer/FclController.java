package com.anland.consumer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data model for FoldCraftLauncher controller files (the JSON files published in
 * the FCL-Controllers repository). The schema matches what FCL itself parses:
 * styles are either a style name or an inline object, positions are stored in
 * thousandths of the free screen area, and output keycodes use Linux evdev
 * scancodes (FCLKeycodes is literally the evdev KEY_* table).
 *
 * Only the fields needed to render and operate a controller are modelled; the
 * controller *editor* features of FCL are intentionally left out.
 */
public final class FclController {

    private static final String TAG = "FclController";
    private static final String ASSET_DIR = "fcl_controllers";

    public final String id;
    public final String name;
    public final String version;
    public final int versionCode;
    public final String author;
    public final String description;
    public final int controllerVersion;

    public final List<ButtonStyle> buttonStyles = new ArrayList<>();
    public final Map<String, ButtonStyle> buttonStylesByName = new HashMap<>();
    public final List<DirectionStyle> directionStyles = new ArrayList<>();
    public final Map<String, DirectionStyle> directionStylesByName = new HashMap<>();
    public final List<ViewGroup> viewGroups = new ArrayList<>();
    /** The raw JSON this controller was parsed from; the editor mutates it in place. */
    private final JSONObject root;

    private FclController(JSONObject root) throws JSONException {
        this.root = root;
        id = root.optString("id", "");
        name = root.optString("name", id);
        version = root.optString("version", "");
        versionCode = root.optInt("versionCode", 0);
        author = root.optString("author", "");
        description = root.optString("description", "");
        controllerVersion = root.optInt("controllerVersion", 0);

        JSONArray bs = root.optJSONArray("buttonStyles");
        if (bs != null) {
            for (int i = 0; i < bs.length(); i++) {
                ButtonStyle style = ButtonStyle.fromJson(bs.getJSONObject(i));
                buttonStyles.add(style);
                buttonStylesByName.put(style.name, style);
            }
        }
        JSONArray ds = root.optJSONArray("directionStyles");
        if (ds != null) {
            for (int i = 0; i < ds.length(); i++) {
                DirectionStyle style = DirectionStyle.fromJson(ds.getJSONObject(i));
                directionStyles.add(style);
                directionStylesByName.put(style.name, style);
            }
        }

        JSONArray groups = root.optJSONArray("viewGroups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                viewGroups.add(ViewGroup.fromJson(groups.getJSONObject(i), this));
            }
        }
    }

    public static FclController parse(JSONObject root) throws JSONException {
        return new FclController(root);
    }

    /** Load a bundled controller from assets/fcl_controllers/<id>.json. */
    public static FclController loadFromAssets(Context context, String id) {
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_DIR + "/" + id + ".json");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return parse(new JSONObject(json));
        } catch (IOException | JSONException e) {
            Log.e(TAG, "load controller " + id + " failed", e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Load a controller by id: an imported copy in the app's files dir takes
     * precedence over the bundled asset with the same id.
     */
    public static FclController load(Context context, String id) {
        java.io.File imported = new java.io.File(context.getFilesDir(),
                ASSET_DIR + "/" + id + ".json");
        if (imported.isFile()) {
            InputStream in = null;
            try {
                in = new java.io.FileInputStream(imported);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                return parse(new JSONObject(
                        new String(out.toByteArray(), StandardCharsets.UTF_8)));
            } catch (IOException | JSONException e) {
                Log.e(TAG, "load imported controller " + id + " failed", e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return loadFromAssets(context, id);
    }

    /** The raw controller JSON (the editor mutates this object). */
    public JSONObject rootJson() {
        return root;
    }

    /** Find a control (button or direction) JSON object by id, for the editor. */
    public JSONObject findControlJson(String controlId) {
        JSONArray groups = root.optJSONArray("viewGroups");
        if (groups == null) {
            return null;
        }
        for (int g = 0; g < groups.length(); g++) {
            JSONObject group = groups.optJSONObject(g);
            JSONObject vd = group != null ? group.optJSONObject("viewData") : null;
            if (vd == null) {
                continue;
            }
            JSONArray bl = vd.optJSONArray("buttonList");
            if (bl != null) {
                for (int i = 0; i < bl.length(); i++) {
                    JSONObject b = bl.optJSONObject(i);
                    if (b != null && controlId.equals(b.optString("id"))) {
                        return b;
                    }
                }
            }
            JSONArray dl = vd.optJSONArray("directionList");
            if (dl != null) {
                for (int i = 0; i < dl.length(); i++) {
                    JSONObject d = dl.optJSONObject(i);
                    if (d != null && controlId.equals(d.optString("id"))) {
                        return d;
                    }
                }
            }
        }
        return null;
    }

    /** Find a view-group JSON object by id, for the editor. */
    public JSONObject findGroupJson(String groupId) {
        JSONArray groups = root.optJSONArray("viewGroups");
        if (groups == null) {
            return null;
        }
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && groupId.equals(g.optString("id"))) {
                return g;
            }
        }
        return null;
    }

    /** The first visible view group (or the first group if none is marked visible). */
    public JSONObject firstEditableGroupJson() {
        JSONArray groups = root.optJSONArray("viewGroups");
        if (groups == null || groups.length() == 0) {
            return null;
        }
        JSONObject fallback = groups.optJSONObject(0);
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && "VISIBLE".equals(g.optString("visibility"))) {
                return g;
            }
        }
        return fallback;
    }

    /** Build a fresh default button JSON for the editor's "add key". */
    public static JSONObject newButtonJson(String text) throws JSONException {
        String id = String.format(java.util.Locale.US, "%08x",
                new java.util.Random().nextInt(0x10000000));
        JSONObject btn = new JSONObject();
        btn.put("id", id);
        btn.put("text", text == null ? "" : text);
        btn.put("style", "Default");
        JSONObject base = new JSONObject();
        base.put("visibilityType", "ALWAYS");
        base.put("xPosition", 500);
        base.put("yPosition", 500);
        base.put("sizeType", "PERCENTAGE");
        base.put("absoluteWidth", 50);
        base.put("absoluteHeight", 50);
        base.put("percentageWidth", new JSONObject()
                .put("reference", "SCREEN_HEIGHT").put("size", 120));
        base.put("percentageHeight", new JSONObject()
                .put("reference", "SCREEN_HEIGHT").put("size", 120));
        btn.put("baseInfo", base);
        JSONObject ev = new JSONObject();
        ev.put("pointerFollow", false);
        ev.put("Movable", false);
        ev.put("pressEvent", new JSONObject()
                .put("autoKeep", false).put("autoClick", false)
                .put("outputKeycodes", new JSONArray()));
        btn.put("event", ev);
        return btn;
    }

    /** Persist the (possibly edited) controller JSON to files/fcl_controllers/<id>.json. */
    public boolean saveToFile(Context context) {
        try {
            java.io.File dir = new java.io.File(context.getFilesDir(), ASSET_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            java.io.File out = new java.io.File(dir, id + ".json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "save controller " + id + " failed", e);
            return false;
        }
    }

    /** True when this controller is a bundled asset (no editable copy on disk). */
    public boolean isBundled(Context context) {
        return !new java.io.File(context.getFilesDir(), ASSET_DIR + "/" + id + ".json").isFile();
    }

    /** Create a new controller profile as a copy of an existing one. Returns the new id. */
    public static String createCopy(Context context, FclController source, String newName) {
        try {
            JSONObject copy = new JSONObject(source.root.toString());
            String newId = String.format(java.util.Locale.US, "%08x",
                    new java.util.Random().nextInt(0x10000000));
            copy.put("id", newId);
            copy.put("name", newName == null || newName.trim().isEmpty()
                    ? source.name : newName.trim());
            return parse(copy).saveToFile(context) ? newId : null;
        } catch (JSONException e) {
            Log.e(TAG, "create controller copy failed", e);
            return null;
        }
    }

    /** Delete an imported controller profile. Bundled assets cannot be deleted. */
    public static boolean deleteFromDisk(Context context, String id) {
        return new java.io.File(context.getFilesDir(), ASSET_DIR + "/" + id + ".json").delete();
    }

    // ---------------------------------------------------------------- styles

    /** Visual style of a control button (FCL "buttonStyles" entry). */
    public static final class ButtonStyle {
        public final String name;
        public final int textColor;
        public final float textSize;          // sp, as authored by FCL
        public final int strokeColor;
        public final float strokeWidth;       // 10x dp, as authored by FCL
        public final float cornerRadius;      // 10x dp, as authored by FCL
        public final int fillColor;
        public final int textColorPressed;
        public final float textSizePressed;
        public final int strokeColorPressed;
        public final float strokeWidthPressed;
        public final float cornerRadiusPressed;
        public final int fillColorPressed;

        private ButtonStyle(String name, int textColor, float textSize,
                            int strokeColor, float strokeWidth, float cornerRadius, int fillColor,
                            int textColorPressed, float textSizePressed,
                            int strokeColorPressed, float strokeWidthPressed,
                            float cornerRadiusPressed, int fillColorPressed) {
            this.name = name;
            this.textColor = textColor;
            this.textSize = textSize;
            this.strokeColor = strokeColor;
            this.strokeWidth = strokeWidth;
            this.cornerRadius = cornerRadius;
            this.fillColor = fillColor;
            this.textColorPressed = textColorPressed;
            this.textSizePressed = textSizePressed;
            this.strokeColorPressed = strokeColorPressed;
            this.strokeWidthPressed = strokeWidthPressed;
            this.cornerRadiusPressed = cornerRadiusPressed;
            this.fillColorPressed = fillColorPressed;
        }

        static ButtonStyle fromJson(JSONObject o) {
            return new ButtonStyle(
                    o.optString("name", "Default"),
                    o.optInt("textColor", 0xFFFFFFFF),
                    (float) o.optDouble("textSize", 12),
                    o.optInt("strokeColor", 0xFF444444),
                    (float) o.optDouble("strokeWidth", 10),
                    (float) o.optDouble("cornerRadius", 100),
                    o.optInt("fillColor", 0x00000000),
                    o.optInt("textColorPressed", 0xFFFFFFFF),
                    (float) o.optDouble("textSizePressed", 12),
                    o.optInt("strokeColorPressed", 0xFF444444),
                    (float) o.optDouble("strokeWidthPressed", 10),
                    (float) o.optDouble("cornerRadiusPressed", 100),
                    o.optInt("fillColorPressed", 0xFFCCCCCC));
        }
    }

    /** Style of a direction pad; either a 9-key pad or a rocker. */
    public static final class DirectionStyle {
        public final String name;
        public final String styleType;   // "BUTTON" or "ROCKER"
        public final ButtonStyle buttonStyle;
        public final RockerStyle rockerStyle;

        private DirectionStyle(String name, String styleType,
                               ButtonStyle buttonStyle, RockerStyle rockerStyle) {
            this.name = name;
            this.styleType = styleType;
            this.buttonStyle = buttonStyle;
            this.rockerStyle = rockerStyle;
        }

        static DirectionStyle fromJson(JSONObject o) {
            String type = o.optString("styleType", "ROCKER");
            ButtonStyle button = null;
            RockerStyle rocker = null;
            JSONObject bo = o.optJSONObject("buttonStyle");
            if (bo != null) {
                button = ButtonStyle.fromJson(bo);
            }
            JSONObject ro = o.optJSONObject("rockerStyle");
            if (ro != null) {
                rocker = RockerStyle.fromJson(ro);
            }
            return new DirectionStyle(o.optString("name", "Default"), type, button, rocker);
        }
    }

    public static final class RockerStyle {
        public final int rockerSize;          // 0..1000, fraction of pad
        public final int bgCornerRadius;      // 0..1000, fraction of pad
        public final float bgStrokeWidth;     // 10x dp
        public final int bgStrokeColor;
        public final int bgFillColor;
        public final int rockerCornerRadius;  // 0..1000, fraction of rocker
        public final float rockerStrokeWidth; // 10x dp
        public final int rockerStrokeColor;
        public final int rockerFillColor;

        private RockerStyle(int rockerSize, int bgCornerRadius, float bgStrokeWidth,
                            int bgStrokeColor, int bgFillColor, int rockerCornerRadius,
                            float rockerStrokeWidth, int rockerStrokeColor, int rockerFillColor) {
            this.rockerSize = rockerSize;
            this.bgCornerRadius = bgCornerRadius;
            this.bgStrokeWidth = bgStrokeWidth;
            this.bgStrokeColor = bgStrokeColor;
            this.bgFillColor = bgFillColor;
            this.rockerCornerRadius = rockerCornerRadius;
            this.rockerStrokeWidth = rockerStrokeWidth;
            this.rockerStrokeColor = rockerStrokeColor;
            this.rockerFillColor = rockerFillColor;
        }

        static RockerStyle fromJson(JSONObject o) {
            return new RockerStyle(
                    o.optInt("rockerSize", 400),
                    o.optInt("bgCornerRadius", 500),
                    (float) o.optDouble("bgStrokeWidth", 20),
                    o.optInt("bgStrokeColor", 0xFF444444),
                    o.optInt("bgFillColor", 0x00000000),
                    o.optInt("rockerCornerRadius", 500),
                    (float) o.optDouble("rockerStrokeWidth", 10),
                    o.optInt("rockerStrokeColor", 0xFF444444),
                    o.optInt("rockerFillColor", 0xFF8A8A8A));
        }
    }

    // -------------------------------------------------------------- geometry

    public static final class PercentageSize {
        public final String reference;   // "SCREEN_WIDTH" / "SCREEN_HEIGHT"
        public final int size;           // thousandths of the reference

        PercentageSize(String reference, int size) {
            this.reference = reference;
            this.size = size;
        }

        int px(int screenW, int screenH) {
            int base = "SCREEN_WIDTH".equals(reference) ? screenW : screenH;
            return base * size / 1000;
        }
    }

    /** Position/size block shared by buttons and directions. */
    public static final class BaseInfo {
        public final String visibilityType;  // "ALWAYS"/"IN_GAME"/"MENU"/"EDIT"
        public final int xPosition;          // thousandths of free area
        public final int yPosition;
        public final String sizeType;        // "ABSOLUTE"/"PERCENTAGE"
        public final int absoluteWidth;      // dp
        public final int absoluteHeight;     // dp
        public final PercentageSize percentageWidth;
        public final PercentageSize percentageHeight;

        private BaseInfo(String visibilityType, int xPosition, int yPosition,
                         String sizeType, int absoluteWidth, int absoluteHeight,
                         PercentageSize percentageWidth, PercentageSize percentageHeight) {
            this.visibilityType = visibilityType;
            this.xPosition = xPosition;
            this.yPosition = yPosition;
            this.sizeType = sizeType;
            this.absoluteWidth = absoluteWidth;
            this.absoluteHeight = absoluteHeight;
            this.percentageWidth = percentageWidth;
            this.percentageHeight = percentageHeight;
        }

        static BaseInfo fromJson(JSONObject o) {
            JSONObject pw = o.optJSONObject("percentageWidth");
            JSONObject ph = o.optJSONObject("percentageHeight");
            return new BaseInfo(
                    o.optString("visibilityType", "ALWAYS"),
                    o.optInt("xPosition", 0),
                    o.optInt("yPosition", 0),
                    o.optString("sizeType", "PERCENTAGE"),
                    o.optInt("absoluteWidth", 50),
                    o.optInt("absoluteHeight", 50),
                    pw != null ? new PercentageSize(pw.optString("reference", "SCREEN_WIDTH"),
                            pw.optInt("size", 0)) : new PercentageSize("SCREEN_WIDTH", 0),
                    ph != null ? new PercentageSize(ph.optString("reference", "SCREEN_HEIGHT"),
                            ph.optInt("size", 0)) : new PercentageSize("SCREEN_HEIGHT", 0));
        }

        int widthPx(int screenW, int screenH, float density) {
            if ("ABSOLUTE".equals(sizeType)) {
                return Math.round(absoluteWidth * density);
            }
            return percentageWidth.px(screenW, screenH);
        }

        int heightPx(int screenW, int screenH, float density) {
            if ("ABSOLUTE".equals(sizeType)) {
                return Math.round(absoluteHeight * density);
            }
            return percentageHeight.px(screenW, screenH);
        }

        int xPx(int screenW, int width) {
            int free = screenW - width;
            return free <= 0 ? 0 : (int) (free * (xPosition / 1000f));
        }

        int yPx(int screenH, int height) {
            int free = screenH - height;
            return free <= 0 ? 0 : (int) (free * (yPosition / 1000f));
        }
    }

    // ---------------------------------------------------------------- events

    /** One press/long-press/click/double-click event of a button. */
    public static final class Event {
        public final boolean autoKeep;
        public final boolean autoClick;
        public final boolean openMenu;
        public final boolean switchTouchMode;
        public final boolean switchMouseMode;
        public final boolean input;
        public final boolean quickInput;
        public final String outputText;
        public final int[] outputKeycodes;
        public final List<String> bindViewGroup;

        private Event(boolean autoKeep, boolean autoClick, boolean openMenu,
                      boolean switchTouchMode, boolean switchMouseMode, boolean input,
                      boolean quickInput, String outputText, int[] outputKeycodes,
                      List<String> bindViewGroup) {
            this.autoKeep = autoKeep;
            this.autoClick = autoClick;
            this.openMenu = openMenu;
            this.switchTouchMode = switchTouchMode;
            this.switchMouseMode = switchMouseMode;
            this.input = input;
            this.quickInput = quickInput;
            this.outputText = outputText;
            this.outputKeycodes = outputKeycodes;
            this.bindViewGroup = bindViewGroup;
        }

        static Event fromJson(JSONObject o) {
            if (o == null) {
                return new Event(false, false, false, false, false, false, false,
                        "", new int[0], new ArrayList<String>());
            }
            JSONArray codes = o.optJSONArray("outputKeycodes");
            int[] keycodes = new int[0];
            if (codes != null) {
                keycodes = new int[codes.length()];
                for (int i = 0; i < codes.length(); i++) {
                    keycodes[i] = codes.optInt(i, 0);
                }
            }
            JSONArray binds = o.optJSONArray("bindViewGroup");
            List<String> groups = new ArrayList<>();
            if (binds != null) {
                for (int i = 0; i < binds.length(); i++) {
                    String g = binds.optString(i, "");
                    if (!g.isEmpty()) {
                        groups.add(g);
                    }
                }
            }
            return new Event(
                    o.optBoolean("autoKeep", false),
                    o.optBoolean("autoClick", false),
                    o.optBoolean("openMenu", false),
                    o.optBoolean("switchTouchMode", false),
                    o.optBoolean("switchMouseMode", false),
                    o.optBoolean("input", false),
                    o.optBoolean("quickInput", false),
                    o.optString("outputText", ""),
                    keycodes,
                    groups);
        }
    }

    // -------------------------------------------------------------- controls

    public static final class Button {
        public final String id;
        public final String text;
        public final ButtonStyle style;
        public final BaseInfo baseInfo;
        public final boolean pointerFollow;
        /** Anland extension: holding this key and dragging moves the mouse.
         *  Default off; absent means off, so FCL imports stay fully compatible. */
        public final boolean dragMoveMouse;
        public final boolean movable;
        public final Event pressEvent;
        public final Event longPressEvent;
        public final Event clickEvent;
        public final Event doubleClickEvent;

        private Button(String id, String text, ButtonStyle style, BaseInfo baseInfo,
                       boolean pointerFollow, boolean dragMoveMouse, boolean movable,
                       Event pressEvent, Event longPressEvent, Event clickEvent,
                       Event doubleClickEvent) {
            this.id = id;
            this.text = text;
            this.style = style;
            this.baseInfo = baseInfo;
            this.pointerFollow = pointerFollow;
            this.dragMoveMouse = dragMoveMouse;
            this.movable = movable;
            this.pressEvent = pressEvent;
            this.longPressEvent = longPressEvent;
            this.clickEvent = clickEvent;
            this.doubleClickEvent = doubleClickEvent;
        }

        static Button fromJson(JSONObject o, FclController owner) throws JSONException {
            JSONObject base = o.optJSONObject("baseInfo");
            JSONObject ev = o.optJSONObject("event");
            ButtonStyle style = resolveButtonStyle(o.opt("style"), owner);
            return new Button(
                    o.optString("id", ""),
                    o.optString("text", ""),
                    style,
                    base != null ? BaseInfo.fromJson(base) : new BaseInfo(
                            "ALWAYS", 0, 0, "PERCENTAGE", 50, 50,
                            new PercentageSize("SCREEN_HEIGHT", 120),
                            new PercentageSize("SCREEN_HEIGHT", 120)),
                    ev != null && ev.optBoolean("pointerFollow", false),
                    ev != null && ev.optBoolean("dragMoveMouse", false),
                    ev != null && ev.optBoolean("Movable", false),
                    Event.fromJson(ev != null ? ev.optJSONObject("pressEvent") : null),
                    Event.fromJson(ev != null ? ev.optJSONObject("longPressEvent") : null),
                    Event.fromJson(ev != null ? ev.optJSONObject("clickEvent") : null),
                    Event.fromJson(ev != null ? ev.optJSONObject("doubleClickEvent") : null));
        }
    }

    public static final class Direction {
        public final String id;
        public final DirectionStyle style;
        public final BaseInfo baseInfo;
        public final int[] upKeycodes;
        public final int[] downKeycodes;
        public final int[] leftKeycodes;
        public final int[] rightKeycodes;
        public final String followOption;   // "FIXED"/"CENTER_FOLLOW"/"FOLLOW"
        public final boolean sneak;
        public final int sneakKeycode;

        private Direction(String id, DirectionStyle style, BaseInfo baseInfo,
                          int[] up, int[] down, int[] left, int[] right,
                          String followOption, boolean sneak, int sneakKeycode) {
            this.id = id;
            this.style = style;
            this.baseInfo = baseInfo;
            this.upKeycodes = up;
            this.downKeycodes = down;
            this.leftKeycodes = left;
            this.rightKeycodes = right;
            this.followOption = followOption;
            this.sneak = sneak;
            this.sneakKeycode = sneakKeycode;
        }

        static Direction fromJson(JSONObject o, FclController owner) throws JSONException {
            JSONObject base = o.optJSONObject("baseInfo");
            JSONObject ev = o.optJSONObject("event");
            DirectionStyle style = resolveDirectionStyle(o.opt("style"), owner);
            return new Direction(
                    o.optString("id", ""),
                    style,
                    base != null ? BaseInfo.fromJson(base) : new BaseInfo(
                            "ALWAYS", 0, 0, "PERCENTAGE", 50, 50,
                            new PercentageSize("SCREEN_HEIGHT", 450),
                            new PercentageSize("SCREEN_HEIGHT", 450)),
                    optKeycodeArray(ev, "upKeycode", 17),
                    optKeycodeArray(ev, "downKeycode", 31),
                    optKeycodeArray(ev, "leftKeycode", 30),
                    optKeycodeArray(ev, "rightKeycode", 32),
                    ev != null ? ev.optString("followOption", "CENTER_FOLLOW") : "CENTER_FOLLOW",
                    ev == null || ev.optBoolean("sneak", true),
                    ev != null ? ev.optInt("sneakKeycode", 42) : 42);
        }
    }

    public static final class ViewGroup {
        public final String id;
        public final String name;
        public final String visibility;    // "VISIBLE"/"INVISIBLE"/"GONE"
        public final List<Button> buttons = new ArrayList<>();
        public final List<Direction> directions = new ArrayList<>();

        private ViewGroup(String id, String name, String visibility) {
            this.id = id;
            this.name = name;
            this.visibility = visibility;
        }

        static ViewGroup fromJson(JSONObject o, FclController owner) throws JSONException {
            ViewGroup group = new ViewGroup(
                    o.optString("id", ""),
                    o.optString("name", ""),
                    o.optString("visibility", "VISIBLE"));
            JSONObject vd = o.optJSONObject("viewData");
            if (vd != null) {
                JSONArray bl = vd.optJSONArray("buttonList");
                if (bl != null) {
                    for (int i = 0; i < bl.length(); i++) {
                        group.buttons.add(Button.fromJson(bl.getJSONObject(i), owner));
                    }
                }
                JSONArray dl = vd.optJSONArray("directionList");
                if (dl != null) {
                    for (int i = 0; i < dl.length(); i++) {
                        group.directions.add(Direction.fromJson(dl.getJSONObject(i), owner));
                    }
                }
            }
            return group;
        }
    }

    // --------------------------------------------------------------- helpers

    private static ButtonStyle resolveButtonStyle(Object style, FclController owner) {
        if (style instanceof JSONObject) {
            ButtonStyle s = ButtonStyle.fromJson((JSONObject) style);
            if (!owner.buttonStylesByName.containsKey(s.name)) {
                owner.buttonStyles.add(s);
                owner.buttonStylesByName.put(s.name, s);
            }
            return owner.buttonStylesByName.get(s.name);
        }
        if (style instanceof String) {
            ButtonStyle s = owner.buttonStylesByName.get(style);
            if (s != null) {
                return s;
            }
        }
        return owner.buttonStyles.isEmpty()
                ? new ButtonStyle("Default", 0xFFFFFFFF, 12, 0xFF444444, 10, 100,
                        0, 0xFFFFFFFF, 12, 0xFF444444, 10, 100, 0xFFCCCCCC)
                : owner.buttonStyles.get(0);
    }

    private static DirectionStyle resolveDirectionStyle(Object style, FclController owner) {
        if (style instanceof JSONObject) {
            DirectionStyle s = DirectionStyle.fromJson((JSONObject) style);
            if (!owner.directionStylesByName.containsKey(s.name)) {
                owner.directionStyles.add(s);
                owner.directionStylesByName.put(s.name, s);
            }
            return owner.directionStylesByName.get(s.name);
        }
        if (style instanceof String) {
            DirectionStyle s = owner.directionStylesByName.get(style);
            if (s != null) {
                return s;
            }
        }
        if (!owner.directionStyles.isEmpty()) {
            return owner.directionStyles.get(0);
        }
        ButtonStyle bs = new ButtonStyle("Default", 0xFFFFFFFF, 12, 0xFF444444, 10, 100,
                0, 0xFFFFFFFF, 12, 0xFF444444, 10, 100, 0xFFCCCCCC);
        RockerStyle rs = new RockerStyle(400, 500, 20, 0xFF444444, 0,
                500, 10, 0xFF444444, 0xFF8A8A8A);
        return new DirectionStyle("Default", "ROCKER", bs, rs);
    }

    /** FCL stores a direction keycode either as an int or an int array. */
    private static int[] optKeycodeArray(JSONObject ev, String key, int fallback) {
        if (ev == null || ev.isNull(key)) {
            return new int[]{fallback};
        }
        JSONArray arr = ev.optJSONArray(key);
        if (arr != null) {
            int[] out = new int[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                out[i] = arr.optInt(i, fallback);
            }
            return out;
        }
        return new int[]{ev.optInt(key, fallback)};
    }
}
