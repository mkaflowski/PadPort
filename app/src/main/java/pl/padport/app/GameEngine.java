package pl.padport.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Browser engine for MV/MZ games, chosen per game: GeckoView (default) or the
 * Android System WebView.
 *
 * GeckoView is the default because its content process belongs to the app and is
 * always 64-bit; the system WebView renderer is 32-bit on some devices (AYN Thor:
 * WebView 109, armeabi-v7a) and big games crash there. The two engines have
 * different origins and browser profiles, so their saves are NOT shared - the
 * checkbox warns about that before switching.
 *
 * Stored as a small private file (like the dual-screen opt-in), so a library
 * refresh cannot drop it. No file = default.
 */
final class GameEngine {
    static final String GECKO="gecko",WEBVIEW="webview";

    /** GeckoView is packaged for arm64-v8a only. */
    static boolean geckoSupported(){
        return android.os.Process.is64Bit()&&Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");
    }
    static File file(Context context,String id){
        if(id==null||!id.matches("[0-9a-f]{24}"))throw new IllegalArgumentException("Invalid game id");
        return new File(context.getFilesDir(),"engine-"+id+".txt");
    }
    /** Anything but an explicit "webview" means GeckoView. */
    static String parse(String stored){return stored!=null&&WEBVIEW.equals(stored.trim())?WEBVIEW:GECKO;}
    static String read(File file){
        try{return parse(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
        catch(IOException e){return GECKO;}
    }
    static void write(File file,String engine) throws IOException{
        Path tmp=new File(file+".tmp").toPath();
        try{
            Files.write(tmp,parse(engine).getBytes(StandardCharsets.UTF_8));
            try{Files.move(tmp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
    static boolean isWebGame(JSONObject game){return game!=null&&!game.has("exec");}
    static boolean useGecko(Context context,JSONObject game){
        if(!isWebGame(game)||!geckoSupported())return false;
        try{return GECKO.equals(read(file(context,game.optString("id"))));}
        catch(IllegalArgumentException e){return true;}
    }
    /** Activity that runs this library entry. */
    static Class<?> player(Context context,JSONObject game){
        if(!isWebGame(game))return RgssActivity.class;
        return useGecko(context,game)?GeckoPlayerActivity.class:PlayerActivity.class;
    }
    static void forget(Context context,String id){
        try{Files.deleteIfExists(file(context,id).toPath());}catch(Exception ignored){}
    }

    /** Checkbox for the game card ⋮ menu. Empty panel for XP/VX/VX Ace or without arm64. */
    static LinearLayout controls(Activity activity,JSONObject game,Runnable changed){
        LinearLayout panel=Ui.column(activity);
        if(!isWebGame(game)||!geckoSupported())return panel;
        CheckBox box=new CheckBox(activity);box.setText(R.string.engine_gecko_option);
        box.setChecked(useGecko(activity,game));
        panel.addView(box);
        panel.addView(Ui.text(activity,R.string.engine_gecko_hint,14,Ui.MUTED));
        box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){
            @Override public void onCheckedChanged(CompoundButton button,boolean gecko){
                // Saves live per engine: say so before switching, and undo on "Cancel".
                new AlertDialog.Builder(activity).setTitle(R.string.engine_switch_title)
                    .setMessage(activity.getString(R.string.engine_switch_message,game.optString("title")))
                    .setPositiveButton(R.string.engine_switch_confirm,(d,n)->{
                        try{write(file(activity,game.optString("id")),gecko?GECKO:WEBVIEW);changed.run();}
                        catch(IOException|IllegalArgumentException e){
                            revert(button,!gecko);
                            Toast.makeText(activity,R.string.controller_setting_error,Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton(R.string.cancel,(d,n)->revert(button,!gecko))
                    .setOnCancelListener(d->revert(button,!gecko)).show();
            }
            private void revert(CompoundButton button,boolean value){
                button.setOnCheckedChangeListener(null);button.setChecked(value);button.setOnCheckedChangeListener(this);
            }
        });
        return panel;
    }
}
