package com.anland.consumer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.LinkedHashSet;

public class KeyInterceptor extends AccessibilityService {
    private static final String PREFS_NAME = "anland_settings";
    private static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_key_intercept";

    LinkedHashSet<Integer> pressedKeys = new LinkedHashSet<>();

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static KeyInterceptor self;
    private static boolean launchedAutomatically = false;
    private boolean enabled = false;

    public KeyInterceptor() {
        self = this;
    }

    public static void launch(Context ctx) {
        try {
            String service = "com.anland.consumer/.KeyInterceptor";
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (enabled == null || enabled.isEmpty())
                enabled = service;
            else if (!enabled.contains(service))
                enabled += ":" + service;

            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            launchedAutomatically = true;
        } catch (SecurityException e) {
            android.util.Log.w("KeyInterceptor", "No WRITE_SECURE_SETTINGS permission", e);
            // User must enable via system Settings > Accessibility manually
        }
    }

    public static void shutdown(boolean onlyIfEnabledAutomatically) {
        if (onlyIfEnabledAutomatically && !launchedAutomatically)
            return;

        if (self != null) {
            self.disableSelf();
            self.pressedKeys.clear();
            self = null;
        }
    }

    public static boolean isLaunched() {
        AccessibilityServiceInfo info = self == null ? null : self.getServiceInfo();
        return info != null && info.getId() != null;
    }

    private static final Runnable disableImmediatelyCallback = KeyInterceptor::disableImmediately;
    private static void disableImmediately() {
        if (self == null) return;
        android.util.Log.d("KeyInterceptor", "disabling interception service");
        AccessibilityServiceInfo info = self.getServiceInfo();
        info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        self.setServiceInfo(info);
        self.enabled = false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        self = this;
        recheck();
    }

    public static void recheck() {
        MainActivity a = getMainActivity();
        boolean shouldBeEnabled = (a != null && self != null) && a.isAccessibilityInterceptEnabled();
        if (self != null && shouldBeEnabled != self.enabled) {
            if (shouldBeEnabled) {
                handler.removeCallbacks(disableImmediatelyCallback);
                android.util.Log.d("KeyInterceptor", "enabling interception service");
                AccessibilityServiceInfo info = self.getServiceInfo();
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                self.setServiceInfo(info);
                self.enabled = true;
            } else {
                handler.postDelayed(disableImmediatelyCallback, 120000);
            }
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        MainActivity instance = getMainActivity();

        if (instance == null)
            return false;

        // Only intercept keys when the activity is in foreground and has focus
        if (!instance.hasWindowFocus())
            return false;

        int keyCode = event.getKeyCode();
        boolean releaseTrackedKey = event.getAction() == KeyEvent.ACTION_UP
                && pressedKeys.contains(keyCode);
        boolean intercept = instance.isAccessibilityInterceptEnabled();

        boolean ret = false;
        if (intercept || releaseTrackedKey)
            ret = instance.handleAccessibilityKey(event);

        if (intercept && ret && event.getAction() == KeyEvent.ACTION_DOWN)
            pressedKeys.add(keyCode);
        else if (event.getAction() == KeyEvent.ACTION_UP)
            pressedKeys.remove(keyCode);

        recheck();

        return ret;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {}

    private static MainActivity getMainActivity() {
        // MainActivity is the only activity; we can locate it via the global
        // reference set in onCreate. Since we don't have a static getInstance()
        // on MainActivity, we use the singleton from the launcher's assumption.
        return MainActivity.sInstance;
    }
}
