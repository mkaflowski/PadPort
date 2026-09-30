package pl.padport.app;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.util.*;

final class SecondaryDisplays {
    record Candidate(int id,boolean valid,boolean on,boolean privateDisplay,boolean presentation) {}
    static int choose(List<Candidate> displays,int primary){
        return displays.stream().filter(d->d.id()!=primary&&d.valid()&&d.on()&&!d.privateDisplay())
            .sorted(Comparator.<Candidate>comparingInt(d->d.presentation()?0:1).thenComparingInt(Candidate::id))
            .mapToInt(Candidate::id).findFirst().orElse(-1);
    }
    static Display find(Activity activity){
        try{
            DisplayManager manager=(DisplayManager)activity.getSystemService(Activity.DISPLAY_SERVICE);
            List<Candidate> candidates=new ArrayList<>();
            for(Display d:manager.getDisplays())candidates.add(new Candidate(d.getDisplayId(),d.isValid(),
                d.getState()!=Display.STATE_OFF,(d.getFlags()&Display.FLAG_PRIVATE)!=0,(d.getFlags()&Display.FLAG_PRESENTATION)!=0));
            int id=choose(candidates,activity.getWindowManager().getDefaultDisplay().getDisplayId());
            return id<0?null:manager.getDisplay(id);
        }catch(RuntimeException e){return null;}
    }
}
