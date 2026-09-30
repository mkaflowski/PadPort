package pl.padport.app;

/** Pure input normalization: separately testable without an Android device. */
public final class PadMath {
    private PadMath() {}
    public static float clamp(float value, float low, float high) {
        return Float.isFinite(value) ? Math.max(low, Math.min(high, value)) : 0;
    }
    public static float stick(float raw, float min, float max, float flat, float deadzone) {
        if (max <= min) return 0;
        float center = (min + max) / 2;
        float half = (max - min) / 2;
        float value = clamp((raw - center) / half, -1, 1);
        float zone = clamp(Math.max(flat / half, deadzone), 0, .9f);
        if (Math.abs(value) <= zone) return 0;
        return Math.copySign((Math.abs(value) - zone) / (1 - zone), value);
    }
    public static float trigger(float raw, float min, float max, float flat) {
        if (max <= min) return 0;
        float value = clamp((raw - min) / (max - min), 0, 1);
        return value <= flat / (max - min) ? 0 : value;
    }
    public static final class State {
        public final float[] keys = new float[17];
        public final float[] motion = new float[17];
        public final float[] axes = new float[4];
        public final boolean[] analog = new boolean[17];
        public float button(int index) { return analog[index] ? motion[index] : Math.max(keys[index], motion[index]); }
        public void clear() {
            java.util.Arrays.fill(keys, 0);
            java.util.Arrays.fill(motion, 0);
            java.util.Arrays.fill(axes, 0);
            java.util.Arrays.fill(analog, false);
        }
    }
}
