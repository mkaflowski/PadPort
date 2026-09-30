package pl.padport.app;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class PadProfile {
    final Map<Integer, Integer> custom = new HashMap<>();
    float deadzone = .15f;
    boolean invertRightY;
    boolean rightRxRy;

    static int defaultButton(int code) {
        return switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A -> 0;
            case KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> 1;
            case KeyEvent.KEYCODE_BUTTON_X -> 2;
            case KeyEvent.KEYCODE_BUTTON_Y -> 3;
            case KeyEvent.KEYCODE_BUTTON_L1 -> 4;
            case KeyEvent.KEYCODE_BUTTON_R1 -> 5;
            case KeyEvent.KEYCODE_BUTTON_L2 -> 6;
            case KeyEvent.KEYCODE_BUTTON_R2 -> 7;
            case KeyEvent.KEYCODE_BUTTON_SELECT -> 8;
            case KeyEvent.KEYCODE_BUTTON_START -> 9;
            case KeyEvent.KEYCODE_BUTTON_THUMBL -> 10;
            case KeyEvent.KEYCODE_BUTTON_THUMBR -> 11;
            case KeyEvent.KEYCODE_DPAD_UP -> 12;
            case KeyEvent.KEYCODE_DPAD_DOWN -> 13;
            case KeyEvent.KEYCODE_DPAD_LEFT -> 14;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> 15;
            case KeyEvent.KEYCODE_BUTTON_MODE -> 16;
            default -> -1;
        };
    }
    int button(int code) { return custom.getOrDefault(code, defaultButton(code)); }
    static boolean nativePad(InputDevice d) {
        return d != null && (d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }
    static PadProfile load(Context context, InputDevice device) {
        PadProfile p = new PadProfile();
        try {
            JSONObject obj = new JSONObject(context.getSharedPreferences("pads", 0).getString(device.getDescriptor(), "{}"));
            p.deadzone = PadMath.clamp((float)obj.optDouble("deadzone", .15), 0, .5f);
            p.invertRightY = obj.optBoolean("invertRightY");
            p.rightRxRy = obj.optBoolean("rightRxRy");
            JSONObject keys = obj.optJSONObject("keys");
            if (keys != null) for (Iterator<String> it = keys.keys(); it.hasNext();) {
                String k = it.next(); int value = keys.getInt(k);
                if (value >= -1 && value < 17) p.custom.put(Integer.parseInt(k), value);
            }
        } catch (Exception ignored) { }
        return p;
    }
    void save(Context c, InputDevice d) {
        try {
            JSONObject obj = new JSONObject(), keys = new JSONObject();
            for (var e : custom.entrySet()) keys.put(e.getKey().toString(), e.getValue());
            obj.put("deadzone", deadzone).put("invertRightY", invertRightY).put("rightRxRy", rightRxRy).put("keys", keys);
            c.getSharedPreferences("pads", 0).edit().putString(d.getDescriptor(), obj.toString()).apply();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    void learn(int code, int button) {
        // Disable the old default key too, so swapping A/B does not duplicate a button.
        for (int c = 0; c < 320; c++) if (button(c) == button) custom.put(c, -1);
        custom.put(code, button);
    }
}
