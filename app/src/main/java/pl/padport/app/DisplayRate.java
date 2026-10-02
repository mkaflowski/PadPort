package pl.padport.app;

import android.app.Activity;
import android.content.Context;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.Toast;
import java.io.File;

/**
 * Optional 60 Hz screen limit (off by default, set in the in-game menu).
 * RPG Maker MV/MZ run their game logic at a fixed 60 steps per second. On 90/120 Hz screens
 * (e.g. AYN Thor) a higher refresh rate only renders duplicate frames and costs battery, and
 * a game's "unlimited" frame-rate option (PIXI ticker maxFPS = 0) follows the screen. When the
 * option is on, the game window asks for the screen's 60 Hz mode; this is a hint the system may
 * ignore, and nothing changes on screens without such a mode.
 */
final class DisplayRate {
    private DisplayRate(){}

    private static File flag(Context context){return new File(context.getFilesDir(),"limit-60hz.enabled");}
    static boolean enabled(Context context){return flag(context).isFile();}

    static void apply(Activity activity){
        apply(activity.getWindow(),activity.getWindowManager().getDefaultDisplay(),enabled(activity));
    }
    static void apply(Window window,Display display,boolean limit){
        if(window==null||display==null)return;
        int mode=limit?mode60(display):0;
        WindowManager.LayoutParams params=window.getAttributes();
        if(params.preferredDisplayModeId==mode)return;
        params.preferredDisplayModeId=mode;   // 0 = no preference, the system default
        window.setAttributes(params);
    }
    private static int mode60(Display display){
        Display.Mode current=display.getMode();
        Display.Mode[] supported=display.getSupportedModes();
        int[][] modes=new int[supported.length][];
        float[] rates=new float[supported.length];
        for(int i=0;i<supported.length;i++){
            modes[i]=new int[]{supported[i].getModeId(),supported[i].getPhysicalWidth(),supported[i].getPhysicalHeight()};
            rates[i]=supported[i].getRefreshRate();
        }
        return pick(current.getPhysicalWidth(),current.getPhysicalHeight(),modes,rates);
    }

    /**
     * Mode id {@code {id,width,height}} of a ~60 Hz mode with the current resolution, or 0.
     * Also kept on screens already at 60 Hz: adaptive refresh may switch them up later.
     */
    static int pick(int width,int height,int[][] modes,float[] rates){
        for(int i=0;i<modes.length;i++)
            if(modes[i][1]==width&&modes[i][2]==height&&Math.abs(rates[i]-60f)<=1f)return modes[i][0];
        return 0;
    }

    static CheckBox checkbox(Activity activity){
        CheckBox box=new CheckBox(activity);box.setText(R.string.limit_60hz);
        box.setChecked(enabled(activity));
        box.setOnCheckedChangeListener((button,checked)->{
            File file=flag(activity);
            boolean saved;
            try{saved=checked?(file.isFile()||file.createNewFile()):(!file.exists()||file.delete());}
            catch(java.io.IOException e){saved=false;}
            if(!saved){
                button.setChecked(enabled(activity));
                Toast.makeText(activity,R.string.controller_setting_error,Toast.LENGTH_LONG).show();
                return;
            }
            apply(activity);
        });
        return box;
    }
}
