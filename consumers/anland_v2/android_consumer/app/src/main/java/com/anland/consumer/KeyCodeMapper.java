package com.anland.consumer;

import android.util.SparseIntArray;
import android.view.KeyEvent;

public class KeyCodeMapper {
    private static final SparseIntArray MAP = new SparseIntArray();

    static {
        // 字母 A-Z
        MAP.put(KeyEvent.KEYCODE_A, 30);
        MAP.put(KeyEvent.KEYCODE_B, 48);
        MAP.put(KeyEvent.KEYCODE_C, 46);
        MAP.put(KeyEvent.KEYCODE_D, 32);
        MAP.put(KeyEvent.KEYCODE_E, 18);
        MAP.put(KeyEvent.KEYCODE_F, 33);
        MAP.put(KeyEvent.KEYCODE_G, 34);
        MAP.put(KeyEvent.KEYCODE_H, 35);
        MAP.put(KeyEvent.KEYCODE_I, 23);
        MAP.put(KeyEvent.KEYCODE_J, 36);
        MAP.put(KeyEvent.KEYCODE_K, 37);
        MAP.put(KeyEvent.KEYCODE_L, 38);
        MAP.put(KeyEvent.KEYCODE_M, 50);
        MAP.put(KeyEvent.KEYCODE_N, 49);
        MAP.put(KeyEvent.KEYCODE_O, 24);
        MAP.put(KeyEvent.KEYCODE_P, 25);
        MAP.put(KeyEvent.KEYCODE_Q, 16);
        MAP.put(KeyEvent.KEYCODE_R, 19);
        MAP.put(KeyEvent.KEYCODE_S, 31);
        MAP.put(KeyEvent.KEYCODE_T, 20);
        MAP.put(KeyEvent.KEYCODE_U, 22);
        MAP.put(KeyEvent.KEYCODE_V, 47);
        MAP.put(KeyEvent.KEYCODE_W, 17);
        MAP.put(KeyEvent.KEYCODE_X, 45);
        MAP.put(KeyEvent.KEYCODE_Y, 21);
        MAP.put(KeyEvent.KEYCODE_Z, 44);

        // 数字 0-9
        MAP.put(KeyEvent.KEYCODE_0, 11);
        MAP.put(KeyEvent.KEYCODE_1, 2);
        MAP.put(KeyEvent.KEYCODE_2, 3);
        MAP.put(KeyEvent.KEYCODE_3, 4);
        MAP.put(KeyEvent.KEYCODE_4, 5);
        MAP.put(KeyEvent.KEYCODE_5, 6);
        MAP.put(KeyEvent.KEYCODE_6, 7);
        MAP.put(KeyEvent.KEYCODE_7, 8);
        MAP.put(KeyEvent.KEYCODE_8, 9);
        MAP.put(KeyEvent.KEYCODE_9, 10);

        // 符号
        MAP.put(KeyEvent.KEYCODE_MINUS, 12);
        MAP.put(KeyEvent.KEYCODE_EQUALS, 13);
        MAP.put(KeyEvent.KEYCODE_LEFT_BRACKET, 26);
        MAP.put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27);
        MAP.put(KeyEvent.KEYCODE_BACKSLASH, 43);
        MAP.put(KeyEvent.KEYCODE_SEMICOLON, 39);
        MAP.put(KeyEvent.KEYCODE_APOSTROPHE, 40);
        MAP.put(KeyEvent.KEYCODE_COMMA, 51);
        MAP.put(KeyEvent.KEYCODE_PERIOD, 52);
        MAP.put(KeyEvent.KEYCODE_SLASH, 53);
        MAP.put(KeyEvent.KEYCODE_GRAVE, 41); // `

        // 功能键
        MAP.put(KeyEvent.KEYCODE_SPACE, 57);
        MAP.put(KeyEvent.KEYCODE_ENTER, 28);
        MAP.put(KeyEvent.KEYCODE_DEL, 14);          // Backspace
        MAP.put(KeyEvent.KEYCODE_FORWARD_DEL, 111); // Delete
        MAP.put(KeyEvent.KEYCODE_TAB, 15);
        MAP.put(KeyEvent.KEYCODE_ESCAPE, 1);        // ESC
        MAP.put(KeyEvent.KEYCODE_SHIFT_LEFT, 42);
        MAP.put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54);
        MAP.put(KeyEvent.KEYCODE_CTRL_LEFT, 29);
        MAP.put(KeyEvent.KEYCODE_CTRL_RIGHT, 97);
        MAP.put(KeyEvent.KEYCODE_ALT_LEFT, 56);
        MAP.put(KeyEvent.KEYCODE_ALT_RIGHT, 100);
        MAP.put(KeyEvent.KEYCODE_CAPS_LOCK, 58);

        // 方向键
        MAP.put(KeyEvent.KEYCODE_DPAD_UP, 103);
        MAP.put(KeyEvent.KEYCODE_DPAD_DOWN, 108);
        MAP.put(KeyEvent.KEYCODE_DPAD_LEFT, 105);
        MAP.put(KeyEvent.KEYCODE_DPAD_RIGHT, 106);

        // F1-F12
        MAP.put(KeyEvent.KEYCODE_F1, 59);
        MAP.put(KeyEvent.KEYCODE_F2, 60);
        MAP.put(KeyEvent.KEYCODE_F3, 61);
        MAP.put(KeyEvent.KEYCODE_F4, 62);
        MAP.put(KeyEvent.KEYCODE_F5, 63);
        MAP.put(KeyEvent.KEYCODE_F6, 64);
        MAP.put(KeyEvent.KEYCODE_F7, 65);
        MAP.put(KeyEvent.KEYCODE_F8, 66);
        MAP.put(KeyEvent.KEYCODE_F9, 67);
        MAP.put(KeyEvent.KEYCODE_F10, 68);
        MAP.put(KeyEvent.KEYCODE_F11, 69);
        MAP.put(KeyEvent.KEYCODE_F12, 70);

        // ====== 新增 Home 和 End ======
        MAP.put(KeyEvent.KEYCODE_MOVE_HOME, 102);
        MAP.put(KeyEvent.KEYCODE_MOVE_END, 107);
    }

    public static int getScanCode(int keyCode) {
        return MAP.get(keyCode, -1);
    }
}