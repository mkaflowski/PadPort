package pl.padport.app;

import android.view.KeyEvent;
import java.util.*;

/** Logical gamepad buttons -> Android keyboard codes, shared by WebView and RGSS. */
final class KeyboardMapping {
    record Key(int code,String label) {}
    interface State {float button(int index);float axis(int index);}
    static final int[] DEFAULT={
        KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_Q,KeyEvent.KEYCODE_W,KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_D,
        KeyEvent.KEYCODE_TAB,KeyEvent.KEYCODE_ESCAPE,0,0,
        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,0
    };
    static final List<Key> KEYS=keys();
    private static List<Key> keys(){
        List<Key> keys=new ArrayList<>(List.of(
            new Key(KeyEvent.KEYCODE_ENTER,"Enter"),new Key(KeyEvent.KEYCODE_ESCAPE,"Esc"),new Key(KeyEvent.KEYCODE_SPACE,"Space"),
            new Key(KeyEvent.KEYCODE_SHIFT_LEFT,"Shift"),new Key(KeyEvent.KEYCODE_CTRL_LEFT,"Ctrl"),new Key(KeyEvent.KEYCODE_ALT_LEFT,"Alt"),
            new Key(KeyEvent.KEYCODE_TAB,"Tab"),new Key(KeyEvent.KEYCODE_DEL,"Backspace"),new Key(KeyEvent.KEYCODE_FORWARD_DEL,"Delete"),
            new Key(KeyEvent.KEYCODE_MOVE_HOME,"Home"),new Key(KeyEvent.KEYCODE_MOVE_END,"End"),new Key(KeyEvent.KEYCODE_INSERT,"Insert"),
            new Key(KeyEvent.KEYCODE_PAGE_UP,"Page Up"),new Key(KeyEvent.KEYCODE_PAGE_DOWN,"Page Down"),
            new Key(KeyEvent.KEYCODE_DPAD_UP,"↑"),new Key(KeyEvent.KEYCODE_DPAD_DOWN,"↓"),
            new Key(KeyEvent.KEYCODE_DPAD_LEFT,"←"),new Key(KeyEvent.KEYCODE_DPAD_RIGHT,"→")));
        for(int i=0;i<26;i++)keys.add(new Key(KeyEvent.KEYCODE_A+i,String.valueOf((char)('A'+i))));
        for(int i=0;i<10;i++)keys.add(new Key(KeyEvent.KEYCODE_0+i,Integer.toString(i)));
        for(int i=0;i<12;i++)keys.add(new Key(KeyEvent.KEYCODE_F1+i,"F"+(i+1)));
        keys.add(new Key(KeyEvent.KEYCODE_SHIFT_RIGHT,"Right Shift"));keys.add(new Key(KeyEvent.KEYCODE_CTRL_RIGHT,"Right Ctrl"));
        keys.add(new Key(KeyEvent.KEYCODE_ALT_RIGHT,"Right Alt"));
        return Collections.unmodifiableList(keys);
    }
    static boolean allowed(int code){return code==0||KEYS.stream().anyMatch(k->k.code()==code);}
    static String label(int code){return KEYS.stream().filter(k->k.code()==code).map(Key::label).findFirst().orElse("—");}
    static boolean modifier(int code){return code==KeyEvent.KEYCODE_SHIFT_LEFT||code==KeyEvent.KEYCODE_SHIFT_RIGHT
        ||code==KeyEvent.KEYCODE_CTRL_LEFT||code==KeyEvent.KEYCODE_CTRL_RIGHT||code==KeyEvent.KEYCODE_ALT_LEFT||code==KeyEvent.KEYCODE_ALT_RIGHT;}
    static List<Integer> ordered(Collection<Integer> keys,boolean down){
        List<Integer> list=new ArrayList<>(keys);
        list.sort(Comparator.<Integer>comparingInt(k->modifier(k)==down?0:1).thenComparingInt(k->k));return list;
    }
    static int modifiers(Set<Integer> held){
        int meta=0;
        if(held.contains(KeyEvent.KEYCODE_SHIFT_LEFT))meta|=KeyEvent.META_SHIFT_ON|KeyEvent.META_SHIFT_LEFT_ON;
        if(held.contains(KeyEvent.KEYCODE_SHIFT_RIGHT))meta|=KeyEvent.META_SHIFT_ON|KeyEvent.META_SHIFT_RIGHT_ON;
        if(held.contains(KeyEvent.KEYCODE_CTRL_LEFT))meta|=KeyEvent.META_CTRL_ON|KeyEvent.META_CTRL_LEFT_ON;
        if(held.contains(KeyEvent.KEYCODE_CTRL_RIGHT))meta|=KeyEvent.META_CTRL_ON|KeyEvent.META_CTRL_RIGHT_ON;
        if(held.contains(KeyEvent.KEYCODE_ALT_LEFT))meta|=KeyEvent.META_ALT_ON|KeyEvent.META_ALT_LEFT_ON;
        if(held.contains(KeyEvent.KEYCODE_ALT_RIGHT))meta|=KeyEvent.META_ALT_ON|KeyEvent.META_ALT_RIGHT_ON;
        return meta;
    }
    static Set<Integer> pressed(State state,int[] bindings){
        Set<Integer> result=new HashSet<>();
        for(int b=0;b<17;b++){
            boolean pressed=state.button(b)>.5f;
            if(b==12)pressed|=state.axis(1)<-.5f;if(b==13)pressed|=state.axis(1)>.5f;
            if(b==14)pressed|=state.axis(0)<-.5f;if(b==15)pressed|=state.axis(0)>.5f;
            if(pressed&&bindings[b]!=0)result.add(bindings[b]);
        }
        return result;
    }
}
