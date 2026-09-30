package pl.padport.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;
import java.io.IOException;

/** Global output mode and editable keyboard bindings, also available without hardware. */
final class ControllerOutputPanel extends LinearLayout {
    private final Activity activity;
    private final Runnable changed;
    private ControllerOutputSettings.Config config;
    private final RadioGroup modes;
    private final RadioButton gamepad,keyboard;
    private final TextView hint;
    private final Button edit;
    private boolean updating;

    ControllerOutputPanel(Activity activity,Runnable changed){
        super(activity);this.activity=activity;this.changed=changed;setOrientation(VERTICAL);
        config=ControllerOutputSettings.load(activity);
        addView(Ui.text(activity,R.string.control_mode,18,Ui.TEXT));
        modes=new RadioGroup(activity);
        gamepad=new RadioButton(activity);gamepad.setId(View.generateViewId());gamepad.setText(R.string.control_mode_gamepad);
        keyboard=new RadioButton(activity);keyboard.setId(View.generateViewId());keyboard.setText(R.string.control_mode_keyboard);
        modes.addView(gamepad);modes.addView(keyboard);addView(modes);
        hint=Ui.text(activity,R.string.keyboard_output_hint,14,Ui.MUTED);addView(hint);
        edit=Ui.button(activity,R.string.keyboard_bindings,this::showBindings);addView(edit);
        render();
        modes.setOnCheckedChangeListener((group,id)->{
            if(updating)return;config.keyboard=id==keyboard.getId();save();render();
        });
    }
    private void render(){
        updating=true;modes.check(config.keyboard?keyboard.getId():gamepad.getId());updating=false;
        hint.setVisibility(config.keyboard?VISIBLE:GONE);edit.setVisibility(config.keyboard?VISIBLE:GONE);
    }
    private void save(){
        try{ControllerOutputSettings.save(activity,config);changed.run();}
        catch(IOException e){config=ControllerOutputSettings.load(activity);Toast.makeText(activity,R.string.controller_setting_error,Toast.LENGTH_LONG).show();}
    }
    private String keyName(int key){return key==0?activity.getString(R.string.key_unassigned):KeyboardMapping.label(key);}
    private void showBindings(){
        if(activity.isFinishing()||activity.isDestroyed())return;
        String[] buttons=getResources().getStringArray(R.array.gamepad_buttons),items=new String[18];
        for(int i=0;i<17;i++)items[i]=buttons[i]+" → "+keyName(config.keys[i]);
        items[17]=activity.getString(R.string.keyboard_reset_bindings);
        new AlertDialog.Builder(activity).setTitle(R.string.keyboard_bindings).setItems(items,(dialog,index)->{
            if(index==17){config.keys=KeyboardMapping.DEFAULT.clone();save();showBindings();}
            else chooseKey(index,buttons[index]);
        }).setNegativeButton(R.string.close,null).show();
    }
    private void chooseKey(int button,String label){
        String[] names=new String[KeyboardMapping.KEYS.size()+1];names[0]=activity.getString(R.string.key_unassigned);
        int selected=config.keys[button]==0?0:-1;
        for(int i=0;i<KeyboardMapping.KEYS.size();i++){
            KeyboardMapping.Key key=KeyboardMapping.KEYS.get(i);names[i+1]=key.label();if(key.code()==config.keys[button])selected=i+1;
        }
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.keyboard_key_for,label))
            .setSingleChoiceItems(names,selected,(dialog,index)->{
                config.keys[button]=index==0?0:KeyboardMapping.KEYS.get(index-1).code();save();dialog.dismiss();showBindings();
            }).setNeutralButton(R.string.keyboard_capture_key,(dialog,which)->captureKey(button,label))
            .setNegativeButton(R.string.cancel,(dialog,which)->showBindings()).setOnCancelListener(dialog->showBindings()).show();
    }
    private void captureKey(int button,String label){
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.keyboard_key_for,label))
            .setMessage(R.string.keyboard_capture_hint).setNegativeButton(R.string.cancel,(d,n)->showBindings())
            .setOnCancelListener(d->showBindings()).create();
        dialog.setOnKeyListener((d,code,event)->{
            if(!KeyboardMapping.allowed(code)||code==0)return false;
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
                config.keys[button]=code;save();dialog.dismiss();showBindings();
            }
            return true;
        });
        dialog.show();
    }
}
