package pl.padport.app;

import java.util.*;

/** Modifier ordering and deduplicated key transitions; no Android event dispatch here. */
final class KeyboardTransitions {
    record Change(int key,boolean down,int modifiers) {}
    private final Set<Integer> held=new HashSet<>();
    List<Change> update(Set<Integer> wanted){
        Set<Integer> removed=new HashSet<>(held);removed.removeAll(wanted);
        Set<Integer> added=new HashSet<>(wanted);added.removeAll(held);
        List<Change> result=new ArrayList<>();
        for(int key:KeyboardMapping.ordered(removed,false)){
            held.remove(key);result.add(new Change(key,false,KeyboardMapping.modifiers(held)));
        }
        for(int key:KeyboardMapping.ordered(added,true)){
            held.add(key);result.add(new Change(key,true,KeyboardMapping.modifiers(held)));
        }
        return result;
    }
    Set<Integer> held(){return new HashSet<>(held);}
    int modifiers(){return KeyboardMapping.modifiers(held);}
}
