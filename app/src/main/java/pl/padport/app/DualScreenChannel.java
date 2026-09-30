package pl.padport.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.Set;

/** One game instance, bounded commands, and generations to reject old display/scene input. */
final class DualScreenChannel {
    private static final java.util.concurrent.atomic.AtomicLong EPOCHS=new java.util.concurrent.atomic.AtomicLong();
    private static final Set<String> ACTIONS=Set.of("inventory","category","select","use","actor","back");
    private final ArrayDeque<JSONObject> commands=new ArrayDeque<>();
    private boolean active;
    private long epoch;
    synchronized void active(boolean value){if(active!=value){active=value;epoch=EPOCHS.incrementAndGet();commands.clear();}}
    synchronized boolean active(){return active;}
    synchronized void enqueue(String payload){
        if(!active||payload.length()>4096||commands.size()>=16)return;
        try{
            JSONObject command=new JSONObject(payload);
            if(command.optLong("epoch",-1)==epoch&&ACTIONS.contains(command.optString("action")))commands.add(command);
        }catch(Exception ignored){}
    }
    synchronized String poll(){
        try{
            JSONArray queue=new JSONArray();while(!commands.isEmpty())queue.put(commands.remove());
            return new JSONObject().put("active",active).put("epoch",epoch).put("commands",queue).toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    synchronized boolean accepts(JSONObject state){return active&&state.optLong("epoch",-1)==epoch;}
}
