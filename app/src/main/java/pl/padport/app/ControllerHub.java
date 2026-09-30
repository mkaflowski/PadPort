package pl.padport.app;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Set;

/** Shared physical/touch state; output can be a standard gamepad or mapped keyboard. */
final class ControllerHub implements InputManager.InputDeviceListener {
    private final Context context;
    private final InputManager manager;
    private final Pad[] pads = new Pad[4];
    final VirtualPadState virtualPad=new VirtualPadState();
    private boolean paused;
    private ControllerOutputSettings.Config output;
    private long revision;
    private volatile String snapshot = "{\"revision\":0,\"paused\":false,\"pads\":[]}";
    Runnable onChange = () -> {};
    String lastEvent;

    private static final class Pad {
        InputDevice device;
        PadProfile profile;
        final PadMath.State state = new PadMath.State();
        Pad(Context c, InputDevice d) { device = d; profile = PadProfile.load(c, d); }
    }
    ControllerHub(Context c) {
        context = c; manager = (InputManager)c.getSystemService(Context.INPUT_SERVICE);
        output=ControllerOutputSettings.load(c);
        lastEvent=c.getString(R.string.controller_idle);
    }
    void start() {
        manager.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));
        refresh();
    }
    void close() { manager.unregisterInputDeviceListener(this); clear(true); }
    String snapshot() { return snapshot; }
    boolean keyboardMode(){return output.keyboard;}
    int keyboardBinding(int button){return output.keys[button];}
    Set<Integer> keyboardKeys(){
        if(paused)return java.util.Collections.emptySet();
        return KeyboardMapping.pressed(new KeyboardMapping.State(){
            public float button(int index){return ControllerHub.this.button(index);}
            public float axis(int index){return ControllerHub.this.axis(index);}
        },output.keys);
    }
    /** Combined state of all connected pads (used by the native RGSS player). */
    float button(int index) {
        float value = 0;
        for (Pad p : pads) if (p != null) value = Math.max(value, p.state.button(index));
        return virtualPad.mergeButton(index,value);
    }
    float axis(int index) {
        float value = 0;
        for (Pad p : pads) if (p != null && Math.abs(p.state.axes[index]) > Math.abs(value)) value = p.state.axes[index];
        return virtualPad.mergeAxis(index,value);
    }
    void refresh() {
        output=ControllerOutputSettings.load(context);
        for (int i = 0; i < pads.length; i++) {
            if (pads[i] == null) continue;
            InputDevice d = InputDevice.getDevice(pads[i].device.getId());
            if (d == null) pads[i] = null;
            else {
                pads[i].device = d; pads[i].profile = PadProfile.load(context, d); pads[i].state.clear();
                if(!PadProfile.nativePad(d) && pads[i].profile.custom.isEmpty()) pads[i]=null;
            }
        }
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (PadProfile.nativePad(d) || (d != null && !PadProfile.load(context,d).custom.isEmpty())) find(d, true);
        }
        publish();
    }
    void clear(boolean pause) {
        paused = pause;
        for (Pad p : pads) if (p != null) p.state.clear();
        virtualPad.paused(pause);
        publish();
    }
    void virtualChanged(){publish();}
    private Pad find(InputDevice d, boolean create) {
        if (d == null || d.isVirtual()) return null;
        for (Pad p : pads) if (p != null && p.device.getId() == d.getId()) return p;
        if (create) for (int i=0;i<pads.length;i++) if (pads[i]==null) return pads[i] = new Pad(context,d);
        return null;
    }
    boolean key(KeyEvent e) {
        InputDevice d=e.getDevice();
        if (d == null) return false;
        Pad existing=find(d,false);
        PadProfile profile=existing==null?PadProfile.load(context,d):existing.profile;
        boolean nativePad=PadProfile.nativePad(d) || KeyEvent.isGamepadButton(e.getKeyCode());
        if (!nativePad && !profile.custom.containsKey(e.getKeyCode())) return false;
        int b=profile.button(e.getKeyCode());
        if (b < 0) return profile.custom.containsKey(e.getKeyCode());
        if (e.getAction()!=KeyEvent.ACTION_DOWN && e.getAction()!=KeyEvent.ACTION_UP) return false;
        Pad pad=find(d,true);
        if (pad==null) return false;
        pad.state.keys[b]=!paused && e.getAction()==KeyEvent.ACTION_DOWN ? 1 : 0;
        lastEvent=d.getName()+" · "+KeyEvent.keyCodeToString(e.getKeyCode())+" → "+b+" · "+(e.getAction()==0?"DOWN":"UP");
        publish();
        return true;
    }
    boolean motion(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false;
        Pad p=find(e.getDevice(),true);
        if (p==null) return false;
        if (paused) return true;
        p.state.axes[0]=stick(e,p,MotionEvent.AXIS_X);
        p.state.axes[1]=stick(e,p,MotionEvent.AXIS_Y);
        boolean rx=p.profile.rightRxRy || (range(p.device,MotionEvent.AXIS_Z)==null && range(p.device,MotionEvent.AXIS_RX)!=null);
        p.state.axes[2]=stick(e,p,rx?MotionEvent.AXIS_RX:MotionEvent.AXIS_Z);
        p.state.axes[3]=stick(e,p,rx?MotionEvent.AXIS_RY:MotionEvent.AXIS_RZ)*(p.profile.invertRightY?-1:1);
        Arrays.fill(p.state.motion,0);
        Arrays.fill(p.state.analog,false);
        float hatX=e.getAxisValue(MotionEvent.AXIS_HAT_X),hatY=e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        motionButton(p,KeyEvent.KEYCODE_DPAD_UP,hatY<-.5f?1:0);
        motionButton(p,KeyEvent.KEYCODE_DPAD_DOWN,hatY>.5f?1:0);
        motionButton(p,KeyEvent.KEYCODE_DPAD_LEFT,hatX<-.5f?1:0);
        motionButton(p,KeyEvent.KEYCODE_DPAD_RIGHT,hatX>.5f?1:0);
        motionButton(p,KeyEvent.KEYCODE_BUTTON_L2,trigger(e,p,MotionEvent.AXIS_LTRIGGER,MotionEvent.AXIS_BRAKE));
        motionButton(p,KeyEvent.KEYCODE_BUTTON_R2,trigger(e,p,MotionEvent.AXIS_RTRIGGER,MotionEvent.AXIS_GAS));
        preferAnalog(p,KeyEvent.KEYCODE_BUTTON_L2,MotionEvent.AXIS_LTRIGGER,MotionEvent.AXIS_BRAKE);
        preferAnalog(p,KeyEvent.KEYCODE_BUTTON_R2,MotionEvent.AXIS_RTRIGGER,MotionEvent.AXIS_GAS);
        lastEvent=context.getString(R.string.axis_event,p.device.getName(),Arrays.toString(p.state.axes),String.valueOf(hatX),String.valueOf(hatY));
        publish();
        return true;
    }
    private static void motionButton(Pad p,int code,float value) {
        int b=p.profile.button(code);
        if (b>=0) p.state.motion[b]=Math.max(p.state.motion[b],value);
    }
    private static void preferAnalog(Pad p,int code,int axis,int alternate) {
        int b=p.profile.button(code);
        if(b>=0 && (range(p.device,axis)!=null || range(p.device,alternate)!=null)) p.state.analog[b]=true;
    }
    private static InputDevice.MotionRange range(InputDevice d,int axis) {
        InputDevice.MotionRange r=d.getMotionRange(axis,InputDevice.SOURCE_JOYSTICK);
        return r!=null?r:d.getMotionRange(axis);
    }
    private static float stick(MotionEvent e,Pad p,int axis) {
        InputDevice.MotionRange r=range(p.device,axis);
        return r==null?0:PadMath.stick(e.getAxisValue(axis),r.getMin(),r.getMax(),r.getFlat(),p.profile.deadzone);
    }
    private static float trigger(MotionEvent e,Pad p,int axis,int alternate) {
        InputDevice.MotionRange r=range(p.device,axis);
        if (r==null) { axis=alternate;r=range(p.device,axis); }
        return r==null?0:PadMath.trigger(e.getAxisValue(axis),r.getMin(),r.getMax(),r.getFlat());
    }
    private void publish() {
        try {
            JSONArray list=new JSONArray();
            int virtualSlot=0;
            for(int i=0;i<pads.length;i++)if(pads[i]!=null){virtualSlot=i;break;}
            for(int i=0;i<pads.length;i++) {
                Pad p=pads[i];
                if(p==null && !(i==virtualSlot&&virtualPad.enabled())) {list.put(JSONObject.NULL);continue;}
                JSONArray axes=new JSONArray(),buttons=new JSONArray();
                for(int a=0;a<4;a++){
                    float value=p==null?0:p.state.axes[a];
                    axes.put((double)(i==virtualSlot?virtualPad.mergeAxis(a,value):value));
                }
                for(int b=0;b<17;b++){
                    float value=p==null?0:p.state.button(b);
                    buttons.put((double)(i==virtualSlot?virtualPad.mergeButton(b,value):value));
                }
                list.put(new JSONObject().put("index",i).put("id",p==null?context.getString(R.string.virtual_controller_name):p.device.getName())
                    .put("nativeId",p==null?-1:p.device.getId()).put("axes",axes).put("buttons",buttons));
            }
            snapshot=new JSONObject().put("revision",++revision).put("paused",paused)
                .put("inputMode",output.keyboard?"keyboard":"gamepad").put("pads",list).toString();
        } catch(Exception e) {throw new IllegalStateException(e);}
        onChange.run();
    }
    public void onInputDeviceAdded(int id) {refresh();}
    public void onInputDeviceChanged(int id) {refresh();}
    public void onInputDeviceRemoved(int id) {refresh();}
}
