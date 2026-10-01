package pl.padport.app;

import android.app.Activity;
import android.widget.*;
import org.json.JSONObject;

final class DualScreenOptions {
    static String description(Activity activity,JSONObject game){
        String profile=DualScreenProfile.identify(game.optString("title"),game.optString("engine"));
        if(DualScreenProfile.TO_THE_MOON.equals(profile))return activity.getString(R.string.ttm_dual_hint);
        if(DualScreenProfile.FEAR_AND_HUNGER.equals(profile))return activity.getString(R.string.fh_dual_hint);
        return activity.getString(R.string.dual_screen_hint);
    }
    static LinearLayout controls(Activity activity,JSONObject game,Runnable changed){
        LinearLayout panel=Ui.column(activity);
        if(!DualScreenProfile.supports(game))return panel;
        CheckBox box=new CheckBox(activity);box.setText(activity.getString(R.string.dual_screen_option,game.optString("title")));box.setChecked(DualScreenProfile.enabled(game));
        panel.addView(box);
        panel.addView(Ui.text(activity,SecondaryDisplays.find(activity)==null?activity.getString(R.string.dual_screen_no_display):description(activity,game),14,Ui.MUTED));
        boolean[] updating={false};
        box.setOnCheckedChangeListener((button,enabled)->{
            if(updating[0])return;
            if(!Library.setDualScreen(activity,game.optString("id"),enabled)){
                updating[0]=true;box.setChecked(!enabled);updating[0]=false;
                Toast.makeText(activity,R.string.controller_setting_error,Toast.LENGTH_LONG).show();
            }
            changed.run();
        });
        return panel;
    }
}
