package pl.padport.app;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.util.*;
import java.util.function.Consumer;

/** Sends controller-generated keyboard events directly to a WebView, never back to the hub. */
final class KeyboardDispatcher {
    private final Consumer<KeyEvent> sink;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final KeyboardTransitions state=new KeyboardTransitions();
    private final Map<Integer,Long> downTimes=new HashMap<>(),repeatAt=new HashMap<>();
    private final Map<Integer,Integer> repeats=new HashMap<>();
    KeyboardDispatcher(Consumer<KeyEvent> sink){this.sink=sink;}
    void update(Set<Integer> wanted){
        long now=SystemClock.uptimeMillis();
        for(KeyboardTransitions.Change change:state.update(wanted)){
            int key=change.key();
            if(change.down()){
                downTimes.put(key,now);repeats.put(key,0);
                if(!KeyboardMapping.modifier(key))repeatAt.put(key,now+400);
            }
            send(key,change.down(),change.modifiers(),0,now);
            if(!change.down()){downTimes.remove(key);repeats.remove(key);repeatAt.remove(key);}
        }
        schedule();
    }
    void clear(){update(Collections.emptySet());}
    private void send(int code,boolean down,int modifiers,int repeat,long now){
        sink.accept(new KeyEvent(downTimes.getOrDefault(code,now),now,down?KeyEvent.ACTION_DOWN:KeyEvent.ACTION_UP,
            code,repeat,modifiers,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));
    }
    private void schedule(){
        handler.removeCallbacks(repeat);
        if(!repeatAt.isEmpty())handler.postDelayed(repeat,Math.max(1,Collections.min(repeatAt.values())-SystemClock.uptimeMillis()));
    }
    private final Runnable repeat=()->{
        long now=SystemClock.uptimeMillis();
        for(int key:KeyboardMapping.ordered(state.held(),true)){
            Long next=repeatAt.get(key);if(next==null||next>now)continue;
            int count=repeats.getOrDefault(key,0)+1;repeats.put(key,count);repeatAt.put(key,now+50);
            send(key,true,state.modifiers(),count,now);
        }
        schedule();
    };
}
