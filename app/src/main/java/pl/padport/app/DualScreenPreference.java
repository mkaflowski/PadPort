package pl.padport.app;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Per-game opt-in shared with :rgss, without cross-process SharedPreferences caching. */
final class DualScreenPreference {
    static File file(Context context,String id){
        if(!id.matches("[0-9a-f]{24}"))throw new IllegalArgumentException("Invalid game id");
        return new File(context.getFilesDir(),"dual-screen-"+id+".enabled");
    }
    static Boolean read(File file){
        try{
            String value=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).trim();
            return "1".equals(value)?Boolean.TRUE:"0".equals(value)?Boolean.FALSE:null;
        }catch(IOException e){return null;}
    }
    static void write(File file,boolean enabled) throws IOException{
        Path tmp=new File(file+".tmp").toPath();
        try{
            Files.write(tmp,(enabled?"1":"0").getBytes(StandardCharsets.UTF_8));
            try{Files.move(tmp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
}
