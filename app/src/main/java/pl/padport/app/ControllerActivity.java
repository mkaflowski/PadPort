package pl.padport.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

public class ControllerActivity extends LocalizedActivity {
    private ControllerHub hub;
    private LinearLayout root,options;
    private TextView status;
    private InputDevice selected;
    private PadProfile profile;
    private int learning=-1;
    private AlertDialog learnDialog;
    private boolean rendering;
    private String[] buttonLabels;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);buttonLabels=getResources().getStringArray(R.array.gamepad_buttons);hub=new ControllerHub(this);
        hub.onChange=()->{if(status!=null)status.setText(hub.lastEvent+"\n\n"+hub.snapshot());};
        hub.start();show();
    }
    private void show(){
        root=Ui.screen(this);root.addView(Ui.title(this,R.string.controller_title));
        root.addView(Ui.text(this,R.string.controller_subtitle,15,Ui.ACCENT_TEXT));
        root.addView(new ControllerOutputPanel(this,()->hub.refresh()));
        root.addView(VirtualControllerSettings.checkbox(this,enabled->{}));
        root.addView(VirtualControllerSettings.transparencyControl(this));
        root.addView(Ui.text(this,R.string.virtual_controller_hint,14,Ui.MUTED));
        root.addView(Ui.text(this,R.string.keyboard_mapping_hint,14,Ui.MUTED));
        root.addView(Ui.button(this,R.string.open_tester,()->startActivity(new Intent(this,PlayerActivity.class))));
        List<InputDevice> devices=new ArrayList<>();List<String> labels=new ArrayList<>();
        for(int id:InputDevice.getDeviceIds()){
            InputDevice d=InputDevice.getDevice(id);
            if(d!=null&&!d.isVirtual()&&(PadProfile.nativePad(d)||(!d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
                    &&(d.supportsSource(InputDevice.SOURCE_KEYBOARD)||d.supportsSource(InputDevice.SOURCE_DPAD))))){
                boolean internal=android.os.Build.VERSION.SDK_INT>=29 && !d.isExternal();
                devices.add(d);labels.add(d.getName()+"  ["+getString(PadProfile.nativePad(d)?(internal?R.string.device_builtin:R.string.device_gamepad):R.string.device_keyboard)+"]");
            }
        }
        root.addView(Ui.button(this,R.string.refresh_devices,()->{hub.refresh();show();}));
        if(devices.isEmpty())root.addView(Ui.text(this,R.string.no_devices,16,Ui.TEXT));
        Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));root.addView(spinner);
        options=Ui.column(this);root.addView(options);
        status=Ui.text(this,hub.lastEvent,12,Ui.MUTED);status.setTextIsSelectable(true);root.addView(status);
        root.addView(Ui.button(this,R.string.back,this::finish));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){selected=devices.get(position);profile=PadProfile.load(ControllerActivity.this,selected);renderProfile();}
            public void onNothingSelected(AdapterView<?> parent){}
        });
    }
    private void renderProfile(){
        rendering=true;options.removeAllViews();
        TextView zone=Ui.text(this,getString(R.string.stick_deadzone,Math.round(profile.deadzone*100)),15,Ui.TEXT);options.addView(zone);
        SeekBar deadzone=new SeekBar(this);deadzone.setMax(50);deadzone.setProgress(Math.round(profile.deadzone*100));options.addView(deadzone);
        deadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){if(fromUser){profile.deadzone=value/100f;zone.setText(getString(R.string.stick_deadzone,value));save();}}
            public void onStartTrackingTouch(SeekBar bar){} public void onStopTrackingTouch(SeekBar bar){}
        });
        CheckBox invert=new CheckBox(this);invert.setText(R.string.invert_right_y);invert.setChecked(profile.invertRightY);options.addView(invert);
        invert.setOnCheckedChangeListener((b,value)->{profile.invertRightY=value;save();});
        CheckBox rx=new CheckBox(this);rx.setText(R.string.right_rx_ry);rx.setChecked(profile.rightRxRy);options.addView(rx);
        rx.setOnCheckedChangeListener((b,value)->{profile.rightRxRy=value;save();});
        for(int i=0;i<17;i++){
            final int button=i;
            List<String> keys=new ArrayList<>();
            for(var entry:profile.custom.entrySet())if(entry.getValue()==i)keys.add(KeyEvent.keyCodeToString(entry.getKey()));
            String mapped=keys.isEmpty()?getString(R.string.default_mapping):String.join(", ",keys);
            options.addView(Ui.button(this,getString(R.string.map_button,i,buttonLabels[i],mapped),()->learn(button)));
        }
        options.addView(Ui.button(this,R.string.reset_mapping,()->{
            profile=new PadProfile();profile.save(this,selected);hub.refresh();renderProfile();
        }));
        rendering=false;
    }
    private void save(){if(!rendering && selected!=null){profile.save(this,selected);hub.refresh();}}
    private void learn(int button){
        learning=button;hub.clear(false);
        learnDialog=new AlertDialog(this){
            @Override public boolean dispatchGenericMotionEvent(MotionEvent e){return captureMotion(e)||super.dispatchGenericMotionEvent(e);}
        };
        learnDialog.setTitle(getString(R.string.assign_title,buttonLabels[button]));
        learnDialog.setMessage(getString(R.string.assign_hint,selected.getName()));
        learnDialog.setButton(AlertDialog.BUTTON_NEGATIVE,getString(R.string.cancel),(d,n)->learning=-1);
        learnDialog.setOnKeyListener((d,code,e)->captureKey(e));
        learnDialog.setOnDismissListener(d->{learning=-1;learnDialog=null;});learnDialog.show();
    }
    private boolean captureKey(KeyEvent e){
        if(learning<0 || selected==null || e.getDeviceId()!=selected.getId())return false;
        if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0){finishLearning(e.getKeyCode());return true;}
        return true;
    }
    private void finishLearning(int code){
        profile.learn(code,learning);profile.save(this,selected);learning=-1;
        if(learnDialog!=null)learnDialog.dismiss();hub.refresh();renderProfile();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){return captureKey(e)||hub.key(e)||super.dispatchKeyEvent(e);}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){
        return captureMotion(e)||hub.motion(e)||super.dispatchGenericMotionEvent(e);
    }
    private boolean captureMotion(MotionEvent e){
        if(learning>=0 && selected!=null && e.getDeviceId()==selected.getId()){
            int code=-1;
            if(e.getAxisValue(MotionEvent.AXIS_HAT_Y)<-.5f)code=KeyEvent.KEYCODE_DPAD_UP;
            else if(e.getAxisValue(MotionEvent.AXIS_HAT_Y)>.5f)code=KeyEvent.KEYCODE_DPAD_DOWN;
            else if(e.getAxisValue(MotionEvent.AXIS_HAT_X)<-.5f)code=KeyEvent.KEYCODE_DPAD_LEFT;
            else if(e.getAxisValue(MotionEvent.AXIS_HAT_X)>.5f)code=KeyEvent.KEYCODE_DPAD_RIGHT;
            else if(e.getAxisValue(MotionEvent.AXIS_LTRIGGER)>.75f || e.getAxisValue(MotionEvent.AXIS_BRAKE)>.75f)code=KeyEvent.KEYCODE_BUTTON_L2;
            else if(e.getAxisValue(MotionEvent.AXIS_RTRIGGER)>.75f || e.getAxisValue(MotionEvent.AXIS_GAS)>.75f)code=KeyEvent.KEYCODE_BUTTON_R2;
            if(code>=0){finishLearning(code);return true;}
        }
        return false;
    }
    @Override protected void onResume(){super.onResume();if(hub!=null){hub.refresh();hub.clear(false);}}
    @Override protected void onPause(){hub.clear(true);super.onPause();}
    @Override protected void onDestroy(){hub.close();super.onDestroy();}
}
