package pl.padport.app;

import android.view.KeyEvent;
import java.util.Set;
import java.util.TreeSet;

/**
 * Standard gamepad state -> keys of the default RGSS keyboard layout (mkxp-z):
 * Enter = C (confirm), Esc = B (cancel/menu), Shift = A, A/S/D = X/Y/Z, Q/W = L/R.
 * Keyboard keys are used on purpose: RPG Maker XP scripts often read the keyboard
 * directly (Win32API GetAsyncKeyState), which mkxp-z emulates from key states.
 */
final class RgssKeys {
    static final int GUIDE = 16;
    private static final float PRESSED = .5f;
    private static final int[] BUTTON_KEYS = {
        KeyEvent.KEYCODE_ENTER,        // 0 A / cross       -> C
        KeyEvent.KEYCODE_ESCAPE,       // 1 B / circle      -> B
        KeyEvent.KEYCODE_SHIFT_LEFT,   // 2 X / square      -> A
        KeyEvent.KEYCODE_A,            // 3 Y / triangle    -> X
        KeyEvent.KEYCODE_Q,            // 4 L1              -> L
        KeyEvent.KEYCODE_W,            // 5 R1              -> R
        KeyEvent.KEYCODE_S,            // 6 L2              -> Y
        KeyEvent.KEYCODE_D,            // 7 R2              -> Z
        0,                             // 8 Select
        KeyEvent.KEYCODE_ESCAPE,       // 9 Start           -> B (menu)
        0, 0,                          // 10, 11 stick clicks
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        0,                             // 16 Guide: PadPort menu
    };

    interface State {
        float button(int index);
        float axis(int index);
    }

    static Set<Integer> pressed(State state) {
        Set<Integer> keys = new TreeSet<>();
        for (int b = 0; b < BUTTON_KEYS.length; b++) if (BUTTON_KEYS[b] != 0 && state.button(b) > PRESSED) keys.add(BUTTON_KEYS[b]);
        float x = state.axis(0), y = state.axis(1);
        if (x < -PRESSED) keys.add(KeyEvent.KEYCODE_DPAD_LEFT);
        if (x > PRESSED) keys.add(KeyEvent.KEYCODE_DPAD_RIGHT);
        if (y < -PRESSED) keys.add(KeyEvent.KEYCODE_DPAD_UP);
        if (y > PRESSED) keys.add(KeyEvent.KEYCODE_DPAD_DOWN);
        return keys;
    }
}
