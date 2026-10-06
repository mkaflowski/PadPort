package pl.padport.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.ViewTreeObserver;
import android.widget.*;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends LocalizedActivity {
    private static final int FOLDER=101,ARTWORK=102;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private boolean scanning;
    private boolean recentOrderChanged;
    private String artworkGame;
    private int generation;
    private final List<GameCard> cards=new ArrayList<>();
    private ViewTreeObserver artworkObserver;
    private final ViewTreeObserver.OnScrollChangedListener artworkScroll=this::loadVisibleArtwork;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent=getIntent();
        // Some launchers resend MAIN above the existing task (including a running game).
        // Drop that duplicate rather than clearing the game activity underneath it.
        if(!isTaskRoot()&&intent!=null&&Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_LAUNCHER)){
            finish();return;
        }
        if(state!=null)artworkGame=state.getString("artworkGame");show();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);}
    @Override protected void onResume(){
        super.onResume();
        if(recentOrderChanged){recentOrderChanged=false;show();}
        else if(root!=null)root.post(this::loadVisibleArtwork);
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("artworkGame",artworkGame);}
    private void show() {
        if(artworkObserver!=null&&artworkObserver.isAlive())artworkObserver.removeOnScrollChangedListener(artworkScroll);
        generation++;for(GameCard card:cards)card.releaseArtwork();cards.clear();
        root=Ui.screen(this);
        root.addView(Ui.title(this,R.string.app_name));
        root.addView(Ui.text(this,R.string.tagline,16,Ui.ACCENT_TEXT));
        root.addView(Ui.text(this,R.string.library_hint,14,Ui.MUTED));
        Button add=Ui.button(this,scanning?R.string.indexing:R.string.add_game,()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i,FOLDER);
        });add.setEnabled(!scanning);root.addView(add);
        root.addView(Ui.button(this,R.string.controllers_button,()->startActivity(new Intent(this,ControllerActivity.class))));
        List<JSONObject> games=Library.all(this);
        if(games.isEmpty()) root.addView(Ui.text(this,R.string.empty_library,15,Ui.MUTED));
        for(JSONObject game:games) {
            // Engine is read at launch time, so a change in the ⋮ menu applies to the next Play.
            GameCard card=new GameCard(this,game,()->launchGame(game,GameEngine.player(this,game)),()->gameOptions(game));
            cards.add(card);root.addView(card);
        }
        root.addView(Ui.text(this,getString(R.string.library_footer,"0.6.1"),12,Ui.MUTED));
        artworkObserver=root.getViewTreeObserver();artworkObserver.addOnScrollChangedListener(artworkScroll);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->loadVisibleArtwork());
        root.post(this::loadVisibleArtwork);
    }
    private void launchGame(JSONObject game,Class<?> player){
        String id=game.optString("id");
        startActivity(new Intent(this,player).putExtra("game",id));
        Library.markPlayed(this,id);
        recentOrderChanged=true;
    }
    private void loadVisibleArtwork(){
        if(isDestroyed())return;
        for(GameCard card:cards){
            if(!card.visible()){card.releaseArtwork();continue;}
            if(card.loading||card.finished)continue;
            card.loading=true;int current=generation;
            int width=card.getWidth(),height=card.getHeight();
            worker.execute(()->{
                Bitmap loaded=null;
                try{
                    loaded=ArtworkStore.load(getApplicationContext(),card.game);
                    loaded=ArtworkEffects.prepare(getApplicationContext(),loaded,width,height);
                }
                catch(Exception e){Log.w("PadPort","Unable to load artwork: "+card.game.optString("title"),e);}
                Bitmap image=loaded;
                runOnUiThread(()->{
                    card.loading=false;
                    if(!isDestroyed()&&current==generation&&card.visible())card.artwork(image);
                });
            });
        }
    }
    private void gameOptions(JSONObject game){
        LinearLayout settings=Ui.column(this);
        // Only non-empty panels: menuDialog pads the settings area when it has children.
        for(LinearLayout panel:new LinearLayout[]{GameEngine.controls(this,game,this::show),DualScreenOptions.controls(this,game,this::show)})
            if(panel.getChildCount()>0)settings.addView(panel);
        Ui.menuDialog(this,game.optString("title"),
                    new String[]{getString(R.string.refresh_game),getString(R.string.card_artwork),getString(R.string.remove_game)},settings,(d,n)->{
                if(n==0)scan(Uri.parse(game.optString("uri")));
                else if(n==1)artworkOptions(game);
                else{
                    Library.remove(this,game.optString("id"));show();
                    // RGSS games keep a private copy of their files; saves stay for a later re-add.
                    if(game.has("exec"))worker.execute(()->{
                        try{new RgssRuntime(getApplicationContext(),game.optString("id")).deleteGameCopy();}
                        catch(Exception e){Log.w("PadPort","Nie można usunąć kopii gry",e);}
                    });
                }
            }).show();
    }
    private void artworkOptions(JSONObject game){
        new AlertDialog.Builder(this).setTitle(getString(R.string.artwork_title,game.optString("title")))
            .setItems(new String[]{getString(R.string.choose_image),getString(R.string.restore_artwork),getString(R.string.search_steamgriddb),getString(R.string.search_steam)},(d,n)->{
                if(n==0){
                    artworkGame=game.optString("id");
                    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                    i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/png","image/jpeg","image/webp"});
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,ARTWORK);
                }else if(n==1){
                    worker.execute(()->{
                        try{ArtworkStore.useAutomatic(getApplicationContext(),game.optString("id"));runOnUiThread(()->{if(!isDestroyed())show();});}
                        catch(Exception e){runOnUiThread(()->{if(!isDestroyed())artworkError(e);});}
                    });
                }else{
                    String base=n==2?"https://www.steamgriddb.com/search/grids":"https://store.steampowered.com/search/";
                    Uri url=Uri.parse(base).buildUpon().appendQueryParameter("term",game.optString("title")).build();
                    try{startActivity(new Intent(Intent.ACTION_VIEW,url));}
                    catch(ActivityNotFoundException e){Toast.makeText(this,R.string.no_browser,Toast.LENGTH_LONG).show();}
                }
            }).show();
    }
    private void artworkError(Exception e){
        new AlertDialog.Builder(this).setTitle(R.string.artwork_error).setMessage(e.getMessage()).setPositiveButton(R.string.ok,null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==ARTWORK){
            String id=artworkGame;artworkGame=null;
            if(result!=RESULT_OK||data==null||data.getData()==null||id==null||Library.get(this,id)==null)return;
            Uri image=data.getData();
            worker.execute(()->{
                try{ArtworkStore.choose(getApplicationContext(),id,image);runOnUiThread(()->{
                    if(!isDestroyed()){show();Toast.makeText(this,R.string.artwork_saved,Toast.LENGTH_SHORT).show();}
                });}
                catch(Exception e){runOnUiThread(()->{if(!isDestroyed())artworkError(e);});}
            });
            return;
        }
        if(request==FOLDER && result==RESULT_OK && data!=null && data.getData()!=null) {
            try {
                Uri uri=data.getData();
                if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0) throw new SecurityException(getString(R.string.folder_permission_missing));
                // Write access is kept only so that PC-only runtime files can be deleted on request.
                if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)
                    getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                else getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                scan(uri);
            } catch(Exception e) {error(e);}
        }
    }
    private void scan(Uri uri) {
        if(scanning) return;scanning=true;show();
        worker.execute(()->{
            try {
                GameSource source=new GameSource(this,uri);source.scan();JSONObject record=source.metadata();
                try{ArtworkStore.invalidateAutomatic(getApplicationContext(),source.id);}catch(Exception e){Log.w("PadPort","Unable to refresh thumbnail",e);}
                PcRuntimeFiles.Result pcFiles=writable(uri)?PcRuntimeFiles.find(source.sizes(),source.isRgss()):null;
                runOnUiThread(()->{
                    if(isDestroyed()) return;
                    scanning=false;
                    if(pcFiles!=null&&pcFiles.worthAsking())offerCleanup(source,record,pcFiles);
                    else continueAdding(source,record);
                });
            } catch(Exception e) {runOnUiThread(()->{scanning=false;if(!isDestroyed()){show();error(e);}});}
        });
    }
    private void continueAdding(GameSource source,JSONObject record){
        Runnable add=()->{Library.put(this,record);show();Toast.makeText(this,getString(R.string.game_added,source.title),Toast.LENGTH_SHORT).show();};
        Runnable offerDual=()->offerDualScreen(record,add);
        // XP/VX/VX Ace run on the experimental native engine: ask once, when the game is added.
        if(source.isRgss()&&Library.get(this,source.id)==null){
            show();
            new AlertDialog.Builder(this).setTitle(R.string.rgss_experimental_title)
                .setMessage(getString(R.string.rgss_experimental_message,source.title,source.engine))
                .setPositiveButton(R.string.rgss_experimental_continue,(d,n)->offerDual.run())
                .setNegativeButton(R.string.cancel,null).setCancelable(false).show();
        }else offerDual.run();
    }
    private boolean writable(Uri uri){
        for(var permission:getContentResolver().getPersistedUriPermissions())
            if(permission.getUri().equals(uri)&&permission.isWritePermission())return true;
        return false;
    }
    /** MV/MZ folders copied from a PC carry the Windows NW.js runtime (often 400+ MB); offer to delete it. */
    private void offerCleanup(GameSource source,JSONObject record,PcRuntimeFiles.Result files){
        show();
        String size=Formatter.formatShortFileSize(this,files.bytes());
        String examples=String.join(", ",PcRuntimeFiles.examples(files.paths(),5));
        new AlertDialog.Builder(this).setTitle(R.string.cleanup_title)
            .setMessage(getString(R.string.cleanup_message,source.title,files.paths().size(),examples,size))
            .setPositiveButton(getString(R.string.cleanup_delete,size),(d,n)->deletePcFiles(source,record,files))
            .setNegativeButton(R.string.cleanup_keep,(d,n)->continueAdding(source,record))
            .setCancelable(false).show();
    }
    private void deletePcFiles(GameSource source,JSONObject record,PcRuntimeFiles.Result files){
        scanning=true;show();
        worker.execute(()->{
            Map<String,Long> before=source.sizes();
            List<String> failed=source.delete(files.paths());
            long freed=0;
            for(String path:files.paths())if(!failed.contains(path))freed+=Math.max(0,before.getOrDefault(path,0L));
            Exception rescan=null;
            try{source.scan();}catch(Exception e){rescan=e;}   // the index must not list deleted files
            Exception error=rescan;long freedBytes=freed;
            runOnUiThread(()->{
                scanning=false;
                if(isDestroyed())return;
                if(error!=null){show();error(error);return;}
                int deleted=files.paths().size()-failed.size();
                if(failed.isEmpty())Toast.makeText(this,getString(R.string.cleanup_done,deleted,Formatter.formatShortFileSize(this,freedBytes)),Toast.LENGTH_LONG).show();
                else Toast.makeText(this,getString(R.string.cleanup_failed,failed.size(),String.join(", ",PcRuntimeFiles.examples(failed,3))),Toast.LENGTH_LONG).show();
                continueAdding(source,record);
            });
        });
    }
    private void error(Exception e) {new AlertDialog.Builder(this).setTitle(R.string.open_game_error).setMessage(e.toString()).setPositiveButton(R.string.ok,null).show();}
    private void offerDualScreen(JSONObject game,Runnable add){
        if(!DualScreenProfile.shouldOffer(game,Library.get(this,game.optString("id"))!=null,SecondaryDisplays.find(this)!=null)){add.run();return;}
        show();
        new AlertDialog.Builder(this).setTitle(R.string.dual_screen_question_title)
            .setMessage(getString(R.string.dual_screen_question,game.optString("title"),DualScreenOptions.description(this,game)))
            .setPositiveButton(R.string.dual_screen_enable,(d,n)->{
                add.run();
                if(!Library.setDualScreen(this,game.optString("id"),true))Toast.makeText(this,R.string.controller_setting_error,Toast.LENGTH_LONG).show();
                show();
            }).setNegativeButton(R.string.dual_screen_not_now,(d,n)->add.run()).setCancelable(false).show();
    }
    @Override protected void onDestroy() {
        if(artworkObserver!=null&&artworkObserver.isAlive())artworkObserver.removeOnScrollChangedListener(artworkScroll);
        worker.shutdown();super.onDestroy();
    }
}
