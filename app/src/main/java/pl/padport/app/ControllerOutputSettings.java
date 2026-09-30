package pl.padport.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Shared main/RGSS process settings. Classic gamepad remains the default. */
final class ControllerOutputSettings {
    static final class Config {
        boolean keyboard;
        int[] keys=KeyboardMapping.DEFAULT.clone();
    }
    private static File file(Context context){return new File(context.getFilesDir(),"controller-output.json");}
    static Config load(Context context){return read(file(context));}
    static Config read(File file){
        Config config=new Config();
        try{
            JSONObject json=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            config.keyboard="keyboard".equals(json.optString("mode"));
            JSONArray keys=json.optJSONArray("keys");
            if(keys!=null&&keys.length()==17)for(int i=0;i<17;i++){
                int key=keys.optInt(i,-1);if(KeyboardMapping.allowed(key))config.keys[i]=key;
            }
        }catch(Exception ignored){}
        return config;
    }
    static void save(Context context,Config config) throws IOException{write(file(context),config);}
    static void write(File file,Config config) throws IOException{
        File tmp=new File(file+".tmp");
        try{
            JSONArray keys=new JSONArray();for(int key:config.keys)keys.put(key);
            JSONObject json=new JSONObject().put("mode",config.keyboard?"keyboard":"gamepad").put("keys",keys);
            Files.write(tmp.toPath(),json.toString().getBytes(StandardCharsets.UTF_8));
            try{Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }catch(org.json.JSONException e){throw new IOException(e);}
        finally{Files.deleteIfExists(tmp.toPath());}
    }
}
