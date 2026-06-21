package com.anland.consumer;

import android.graphics.Rect;
import android.view.KeyEvent;

public class KeyData {
    public final Rect rect = new Rect();
    public final String label;
    public final int keyCode;
    public boolean pressed = false;

    public KeyData(String label, int keyCode) {
        this.label = label;
        this.keyCode = keyCode;
    }
}