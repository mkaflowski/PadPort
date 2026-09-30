package pl.padport.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

final class Library {
    private static final String LAST_PLAYED="lastPlayed";
    static List<JSONObject> all(Context c) {
        List<JSONObject> list=new ArrayList<>();
        try {
            JSONArray array=new JSONArray(c.getSharedPreferences("library",0).getString("games","[]"));
            for(int i=0;i<array.length();i++){
                JSONObject game=array.getJSONObject(i);
                try{
                    Boolean enabled=DualScreenPreference.read(DualScreenPreference.file(c,game.optString("id")));
                    if(enabled!=null)game.put("dualScreen",enabled);
                }catch(IllegalArgumentException ignored){}
                list.add(game);
            }
        } catch(Exception ignored) { }
        sortByLastPlayed(list);
        return list;
    }
    static JSONObject get(Context c,String id) {
        for(JSONObject obj:all(c)) if(obj.optString("id").equals(id)) return obj;
        return null;
    }
    static void put(Context c,JSONObject record) {
        List<JSONObject> list=all(c);
        replaceRecord(list,record);save(c,list);
    }
    static void markPlayed(Context c,String id){
        List<JSONObject> list=all(c);
        if(markPlayed(list,id,System.currentTimeMillis()))save(c,list);
    }
    static void sortByLastPlayed(List<JSONObject> list){
        // Stable ordering keeps legacy/unplayed games and tied timestamps predictable.
        list.sort((a,b)->Long.compare(lastPlayed(b),lastPlayed(a)));
    }
    private static long lastPlayed(JSONObject game){return Math.max(0,game.optLong(LAST_PLAYED,0));}
    static void replaceRecord(List<JSONObject> list,JSONObject record){
        try{
            JSONObject replacement=new JSONObject(record.toString());
            for(int i=0;i<list.size();i++){
                JSONObject previous=list.get(i);
                if(!previous.optString("id").equals(record.optString("id")))continue;
                if(previous.has(LAST_PLAYED))replacement.put(LAST_PLAYED,lastPlayed(previous));
                if(previous.has("dualScreen"))replacement.put("dualScreen",previous.optBoolean("dualScreen",false));
                list.set(i,replacement);sortByLastPlayed(list);return;
            }
            list.add(replacement);sortByLastPlayed(list);
        }catch(JSONException e){throw new IllegalArgumentException("Invalid library record",e);}
    }
    static boolean markPlayed(List<JSONObject> list,String id,long timestamp){
        for(JSONObject game:list)if(game.optString("id").equals(id)){
            try{game.put(LAST_PLAYED,Math.max(0,timestamp));}
            catch(JSONException e){throw new IllegalArgumentException("Invalid last-played timestamp",e);}
            sortByLastPlayed(list);return true;
        }
        return false;
    }
    static void remove(Context c,String id) {
        List<JSONObject> list=all(c);list.removeIf(x->x.optString("id").equals(id));save(c,list);
        try{java.nio.file.Files.deleteIfExists(DualScreenPreference.file(c,id).toPath());}catch(Exception ignored){}
        GameEngine.forget(c,id);
    }
    static boolean setDualScreen(Context c,String id,boolean enabled){
        if(!DualScreenProfile.supports(get(c,id)))return false;
        try{DualScreenPreference.write(DualScreenPreference.file(c,id),enabled);return true;}
        catch(java.io.IOException e){android.util.Log.w("PadPort","Cannot save dual-screen option",e);return false;}
    }
    private static void save(Context c,List<JSONObject> list) {
        JSONArray array=new JSONArray();for(JSONObject obj:list) array.put(obj);
        c.getSharedPreferences("library",0).edit().putString("games",array.toString()).apply();
    }
}
