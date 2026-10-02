package pl.padport.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class PlayerActivity extends LocalizedActivity {
    private static final int EXPORT=201,IMPORT=202;
    private static final long MENU_IDLE_MS=5000;
    private static final float MENU_ALPHA=.4f;
    private ControllerHub controllers;
    private WebView web;
    private KeyboardDispatcher keyboard;
    private boolean webReady;
    private GameSource source;
    private volatile DualScreenSession dualScreen;
    private FrameLayout root;
    private AlertDialog menu;
    private Button menuButton;
    private VirtualControllerView virtualControls;
    private boolean touchingScreen;
    private final Handler menuHandler=new Handler(Looper.getMainLooper());
    private final Runnable hideMenuButton=()->{
        if(menuButton!=null && menu==null && !touchingScreen) menuButton.setVisibility(View.INVISIBLE);
    };
    private boolean foreground;
    private volatile boolean exporting;
    private String exportPayload;
    private final ArrayDeque<String> logs=new ArrayDeque<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);setContentView(root);
        DisplayRate.apply(this);
        controllers=new ControllerHub(this);controllers.onChange=this::updateKeyboard;controllers.start();
        TextView loading=Ui.text(this,R.string.opening_game,20,Ui.TEXT);loading.setGravity(Gravity.CENTER);
        root.addView(loading,new FrameLayout.LayoutParams(-1,-1));
        String game=getIntent().getStringExtra("game");
        if(game==null) setup(null);
        else worker.execute(()->{
            try {
                JSONObject metadata=Library.get(this,game);
                if(metadata==null)throw new IOException(getString(R.string.game_not_in_library));
                GameSource src=GameSource.restore(this,metadata);
                runOnUiThread(()->{if(!isDestroyed())setup(src);});
            } catch(Exception e){runOnUiThread(()->{if(!isDestroyed())new AlertDialog.Builder(this).setTitle(R.string.launch_error)
                .setMessage(getString(R.string.launch_error_details,e.toString())).setPositiveButton(R.string.back,(d,n)->finish()).setOnCancelListener(d->finish()).show();});}
        });
    }
    @SuppressWarnings("SetJavaScriptEnabled") private void setup(GameSource game) {
        if(keyboard!=null)keyboard.clear();webReady=false;
        if(dualScreen!=null){dualScreen.close();dualScreen=null;}
        if(game!=null&&!DualScreenProfile.identify(game.title,game.engine).isEmpty())dualScreen=new DualScreenSession(this,game);
        source=game;root.removeAllViews();web=new WebView(this);
        keyboard=new KeyboardDispatcher(event->{if(web!=null)web.dispatchKeyEvent(event);});
        web.setBackgroundColor(Color.BLACK);web.setFocusableInTouchMode(true);
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);settings.setSupportMultipleWindows(false);
        settings.setBuiltInZoomControls(false);settings.setSupportZoom(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0) WebView.setWebContentsDebuggingEnabled(true);
        web.addJavascriptInterface(new Host(),"PadPortHost");
        GameWebClient client=new GameWebClient(this,source,this::log);
        client.loaded=()->{webReady=true;if(foreground && menu==null)resumeGame();};
        client.gone=this::renderGone;
        web.setWebViewClient(client);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onConsoleMessage(ConsoleMessage message){log(message.messageLevel()+" "+message.message()+" @ "+message.sourceId()+":"+message.lineNumber());return true;}
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){
                new AlertDialog.Builder(PlayerActivity.this).setMessage(message).setPositiveButton(R.string.ok,(d,n)->result.confirm()).setOnCancelListener(d->result.cancel()).show();return true;
            }
        });
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        virtualControls=new VirtualControllerView(this,controllers);
        root.addView(virtualControls,new FrameLayout.LayoutParams(-1,-1));
        menuButton=Ui.button(this,"⋮",this::openMenu);menuButton.setTextSize(24);menuButton.setAlpha(MENU_ALPHA);
        menuButton.setContentDescription(getString(R.string.app_menu));menuButton.setFocusable(false);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(Ui.dp(this,52),Ui.dp(this,48),Gravity.TOP|Gravity.END);
        bp.setMargins(0,Ui.dp(this,8),Ui.dp(this,10),0);root.addView(menuButton,bp);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int right=0,left=0;
            if(android.os.Build.VERSION.SDK_INT>=28){
                android.view.DisplayCutout cut=insets.getDisplayCutout();
                if(cut!=null){right=cut.getSafeInsetRight();left=cut.getSafeInsetLeft();}
            }
            v.setPadding(left,0,right,0);return insets;
        });
        immersive();web.requestFocus();web.loadUrl(client.origin+"/index.html");
        refreshVirtualController();
        showMenuButton();
    }
    private void immersive(){
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            |View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){return controllers!=null && controllers.key(event) || super.dispatchKeyEvent(event);}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){return controllers!=null && controllers.motion(event) || super.dispatchGenericMotionEvent(event);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        // Deliver DOWN before revealing the button. A tap on its hidden area
        // still belongs to the game, rather than accidentally opening the menu.
        boolean handled=super.dispatchTouchEvent(event);
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){touchingScreen=true;showMenuButton();}
        else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL){touchingScreen=false;showMenuButton();}
        return handled;
    }
    private void showMenuButton(){
        menuHandler.removeCallbacks(hideMenuButton);
        if(menuButton==null || !foreground || menu!=null || isFinishing())return;
        menuButton.setVisibility(View.VISIBLE);
        if(!touchingScreen)menuHandler.postDelayed(hideMenuButton,MENU_IDLE_MS);
    }
    @Override protected void onResume(){super.onResume();foreground=true;if(controllers!=null)controllers.refresh();if(web!=null && menu==null)resumeGame();}
    @Override protected void onPause(){foreground=false;pauseGame();super.onPause();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus){if(virtualControls!=null)virtualControls.suspend();if(controllers!=null)controllers.clear(true);js("PadPort.clearInput()");}else if(foreground && menu==null)resumeGame();}
    @Override public void onBackPressed(){if(menu!=null)menu.dismiss();else openMenu();}
    private void js(String code){if(web!=null)web.evaluateJavascript("if(window.PadPort){"+code+";}",null);}
    private void updateKeyboard(){
        if(keyboard==null)return;
        if(web!=null&&webReady&&foreground&&menu==null&&hasWindowFocus()&&controllers.keyboardMode())keyboard.update(controllers.keyboardKeys());
        else keyboard.clear();
    }
    private void pauseGame(){if(dualScreen!=null)dualScreen.pause();js("if(window.LookOutsideDual)LookOutsideDual.disable();if(window.FearHungerDual)FearHungerDual.disable();if(window.ElderfieldDual)ElderfieldDual.disable()");menuHandler.removeCallbacks(hideMenuButton);touchingScreen=false;if(virtualControls!=null)virtualControls.suspend();if(controllers!=null)controllers.clear(true);js("PadPort.pause()");if(web!=null)web.onPause();}
    private void resumeGame(){if(controllers!=null)controllers.clear(false);if(web!=null){web.onResume();js("PadPort.resume()");web.requestFocus();immersive();refreshVirtualController();showMenuButton();if(dualScreen!=null)dualScreen.resume();}}
    private void refreshVirtualController(){
        if(virtualControls!=null)virtualControls.configure(VirtualControllerSettings.enabled(this),foreground&&menu==null&&hasWindowFocus());
    }
    private void openMenu(){
        if(web==null || menu!=null)return;
        pauseGame();
        String[] options=source==null?new String[]{getString(R.string.back_to_test),getString(R.string.controller_mapping),getString(R.string.diagnostics_copy_log),getString(R.string.close_tester)}
            :new String[]{getString(R.string.back_to_game),getString(R.string.controller_mapping),getString(R.string.export_saves),getString(R.string.import_saves),getString(R.string.diagnostics_copy_log),getString(R.string.back_to_library)};
        LinearLayout settings=Ui.column(this);
        settings.addView(VirtualControllerSettings.checkbox(this,enabled->refreshVirtualController()));
        if(source!=null)settings.addView(DisplayRate.checkbox(this));
        if(source!=null)settings.addView(DualScreenOptions.controls(this,Library.get(this,source.id),()->{}));
        menu=Ui.menuDialog(this,source==null?getString(R.string.controller_tester):source.title,options,settings,(dialog,n)->{
            if(n==1)startActivity(new Intent(this,ControllerActivity.class));
            else if(source!=null && n==2){exporting=true;js("PadPort.exportSaves()");}
            else if(source!=null && n==3){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,IMPORT);}
            else if(n==(source==null?2:4))showLog();
            else if(n==(source==null?3:5))finish();
        });
        menu.setOnDismissListener(d->{menu=null;if(foreground&&!isFinishing())resumeGame();});menu.show();
    }
    private synchronized void log(String text){logs.add(text.length()>2000?text.substring(0,2000):text);while(logs.size()>120)logs.remove();}
    private void showLog(){
        String content;
        synchronized(this){content=String.join("\n",logs);}
        TextView text=Ui.text(this,content,12,Ui.TEXT);text.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(text);
        new AlertDialog.Builder(this).setTitle(R.string.game_diagnostics).setView(scroll).setPositiveButton(R.string.close,null)
            .setNeutralButton(R.string.copy,(d,n)->((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PadPort log",content))).show();
    }
    private void error(String message){log(message);new AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage(message).setPositiveButton(R.string.ok,null).show();}
    public final class Host {
        @JavascriptInterface public String dualPoll(){DualScreenSession current=dualScreen;return current==null?"{\"active\":false}":current.channel.poll();}
        @JavascriptInterface public void dualSnapshot(String json){DualScreenSession current=dualScreen;if(current!=null)current.snapshot(json);}
        @JavascriptInterface public String snapshot(){return controllers.snapshot();}
        @JavascriptInterface public void log(String message){PlayerActivity.this.log(message);}
        @JavascriptInterface public void exportReady(String payload){
            if(!exporting)return;exporting=false;
            if(payload.length()>32*1024*1024){saveError(getString(R.string.backup_too_large));return;}
            runOnUiThread(()->{
                if(isDestroyed())return;exportPayload=payload;
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_TITLE,"PadPort-"+(source==null?"game":source.title.replaceAll("[^\\p{L}\\p{N} ._-]","_"))+"-saves.json");
                startActivityForResult(intent,EXPORT);
            });
        }
        @JavascriptInterface public void importReady(){runOnUiThread(()->{if(web!=null&&!isDestroyed()){Toast.makeText(PlayerActivity.this,R.string.saves_restored,Toast.LENGTH_LONG).show();web.reload();}});}
        @JavascriptInterface public void saveError(String message){exporting=false;runOnUiThread(()->{if(!isDestroyed())error(message);});}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null){if(request==EXPORT)exportPayload=null;return;}
        Uri uri=data.getData();
        if(request==EXPORT && exportPayload!=null){
            String payload=exportPayload;exportPayload=null;
            worker.execute(()->{
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                    if(out==null)throw new IOException(getString(R.string.file_access_error));out.write(payload.getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(()->Toast.makeText(this,R.string.backup_exported,Toast.LENGTH_LONG).show());
                }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())error(e.toString());});}
            });
        }else if(request==IMPORT){
            worker.execute(()->{
                try(InputStream in=getContentResolver().openInputStream(uri)){
                    if(in==null)throw new IOException(getString(R.string.backup_access_error));
                    JSONObject backup=new JSONObject(new String(GameSource.readBounded(this,in,32*1024*1024),StandardCharsets.UTF_8));
                    runOnUiThread(()->{if(!isDestroyed())new AlertDialog.Builder(this).setTitle(R.string.restore_question)
                        .setMessage(R.string.restore_details)
                        .setPositiveButton(R.string.restore,(d,n)->js("PadPort.restore("+backup+")")).setNegativeButton(R.string.cancel,null).show();});
                }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())error(e.toString());});}
            });
        }
    }
    /** The WebView renderer died (crash or low-memory kill). Keep the app, offer a restart. */
    private void renderGone(boolean crashed){
        if(dualScreen!=null)dualScreen.pause();
        log("WebView renderer "+(crashed?"crashed":"was killed by the system to free memory"));
        webReady=false;if(keyboard!=null)keyboard.clear();
        if(web!=null){WebView dead=web;web=null;root.removeView(dead);dead.removeJavascriptInterface("PadPortHost");dead.destroy();}
        if(menu!=null){menu.dismiss();menu=null;}
        if(isFinishing()||isDestroyed())return;
        new AlertDialog.Builder(this).setTitle(R.string.renderer_gone_title)
            .setMessage(crashed?R.string.renderer_crashed:R.string.renderer_killed)
            .setPositiveButton(R.string.renderer_restart,(d,n)->setup(source))
            .setNegativeButton(R.string.back_to_library,(d,n)->finish()).setCancelable(false).show();
    }
    @Override protected void onDestroy(){
        if(dualScreen!=null){dualScreen.close();dualScreen=null;}
        menuHandler.removeCallbacks(hideMenuButton);
        webReady=false;if(keyboard!=null)keyboard.clear();
        if(virtualControls!=null)virtualControls.suspend();
        if(controllers!=null)controllers.close();
        if(web!=null){root.removeView(web);web.removeJavascriptInterface("PadPortHost");web.destroy();web=null;}
        worker.shutdown();super.onDestroy();
    }
}
