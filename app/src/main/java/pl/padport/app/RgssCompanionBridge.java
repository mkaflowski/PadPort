package pl.padport.app;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Atomic, private IPC with the Ruby thread. Lives outside the game and save directories. */
final class RgssCompanionBridge implements AutoCloseable {
    private final File directory;
    private final DualScreenChannel channel;
    private final Consumer<String> receive;
    private final String session=UUID.randomUUID().toString();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private boolean closed;
    private String previous="";
    RgssCompanionBridge(File directory,DualScreenChannel channel,Consumer<String> receive) throws IOException{
        this.directory=directory;this.channel=channel;this.receive=receive;
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create companion directory");
        // The new run must never accept output left by a previous Ruby VM.
        Files.deleteIfExists(new File(directory,"state.json").toPath());
        pump();
        worker.scheduleWithFixedDelay(this::pump,250,250,TimeUnit.MILLISECONDS);
    }
    private synchronized void pump(){
        if(closed)return;
        try{
            JSONObject control=new JSONObject(channel.poll()).put("session",session).put("updated",System.currentTimeMillis());
            RgssRuntime.writeAtomic(new File(directory,"control.json"),control.toString().getBytes(StandardCharsets.UTF_8));
            File state=new File(directory,"state.json");
            if(!state.isFile()||state.length()>512*1024)return;
            String json=new String(Files.readAllBytes(state.toPath()),StandardCharsets.UTF_8);
            if(json.equals(previous))return;
            JSONObject data=new JSONObject(json);
            if(accepts(data,session,channel)){previous=json;receive.accept(json);}
        }catch(Exception e){android.util.Log.d("PadPort","Companion transport: "+e.getMessage());}
    }
    static boolean accepts(JSONObject data,String session,DualScreenChannel channel){
        return session.equals(data.optString("session"))&&channel.accepts(data);
    }
    @Override public synchronized void close(){
        closed=true;worker.shutdownNow();channel.active(false);
        try{RgssRuntime.writeAtomic(new File(directory,"control.json"),"{\"active\":false}".getBytes(StandardCharsets.UTF_8));}
        catch(IOException ignored){}
    }
}
