package pl.padport.app;

import android.app.Activity;
import android.app.Presentation;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.webkit.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Lightweight game UI on the other display; never starts another game engine or save session. */
final class DualScreenSession implements DisplayManager.DisplayListener,AutoCloseable {
    private static final String LOOK_OUTSIDE_LOGO="https://cdn2.steamgriddb.com/logo_thumb/10c6cea7f006e0752cfe7e68cc3c42c6.png";
    private final Activity activity;
    private final String gameId;
    private final String page;
    private final GameSource source;
    private final DisplayManager manager;
    private final Handler main=new Handler(Looper.getMainLooper());
    final DualScreenChannel channel=new DualScreenChannel();
    private Panel panel;
    private boolean foreground,closed;

    DualScreenSession(Activity activity,String gameId){
        this(activity,gameId,null);
    }
    DualScreenSession(Activity activity,GameSource source){
        this(activity,source.id,source);
    }
    private DualScreenSession(Activity activity,String gameId,GameSource source){
        this.activity=activity;this.gameId=gameId;
        this.source=source;
        JSONObject game=Library.get(activity,gameId);
        String profile=game==null?"":DualScreenProfile.identify(game.optString("title"),game.optString("engine"));
        page=DualScreenProfile.panel(profile);
        manager=(DisplayManager)activity.getSystemService(Activity.DISPLAY_SERVICE);
        manager.registerDisplayListener(this,main);
    }
    void resume(){foreground=true;refresh();}
    void pause(){foreground=false;dismiss();}
    void refresh(){
        if(closed||!foreground||!DualScreenProfile.enabled(Library.get(activity,gameId))){dismiss();return;}
        Display target=SecondaryDisplays.find(activity);
        if(target==null){dismiss();return;}
        if(panel!=null&&panel.getDisplay().getDisplayId()==target.getDisplayId())return;
        dismiss();
        try{
            Panel next=new Panel(target);panel=next;
            next.setOnDismissListener(d->{if(panel==next){panel=null;channel.active(false);}next.destroy();});
            next.show();
        }catch(RuntimeException e){dismiss();android.util.Log.w("PadPort","Secondary display unavailable",e);}
    }
    private void dismiss(){
        channel.active(false);Panel old=panel;panel=null;if(old!=null){old.dismiss();old.destroy();}
    }
    void snapshot(String json){
        if(json==null||json.length()>512*1024)return;
        try{
            JSONObject state=new JSONObject(json);
            if(!channel.accepts(state))return;
            main.post(()->{if(panel!=null&&channel.accepts(state))panel.update(state.toString());});
        }catch(Exception ignored){}
    }
    @Override public void close(){closed=true;pause();manager.unregisterDisplayListener(this);main.removeCallbacksAndMessages(null);}
    public void onDisplayAdded(int id){refresh();}
    public void onDisplayRemoved(int id){refresh();}
    public void onDisplayChanged(int id){refresh();}

    private final class Panel extends Presentation {
        private WebView web;
        private String latest;
        Panel(Display display){super(activity,display);}
        @SuppressWarnings("SetJavaScriptEnabled") @Override protected void onCreate(Bundle state){
            super.onCreate(state);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().setLayout(-1,-1);
            web=new WebView(getContext());web.setBackgroundColor("to-the-moon-panel".equals(page)?Color.rgb(20,18,18):Color.BLACK);setContentView(web);
            WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);
            settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setSupportMultipleWindows(false);
            web.addJavascriptInterface(new Object(){
                @JavascriptInterface public void command(String json){channel.enqueue(json);}
                @JavascriptInterface public void ready(){main.post(()->{
                    if(panel==Panel.this&&foreground){channel.active(true);if(latest!=null)update(latest);}
                });}
            },"GameCompanionHost");
            web.setWebViewClient(new WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                    // Let WebView download/cache this single optional image asynchronously.
                    if("look-outside-panel".equals(page)&&"GET".equals(request.getMethod())&&LOOK_OUTSIDE_LOGO.equals(request.getUrl().toString()))return null;
                    if(source!=null&&"GET".equals(request.getMethod())&&"https".equals(request.getUrl().getScheme())
                        &&"companion.padport.local".equals(request.getUrl().getHost())&&"/game-font".equals(request.getUrl().getPath())){
                        try{
                            String path=CompanionFont.path(request.getUrl().getQueryParameter("path"));
                            byte[] bytes=source.read(path,16*1024*1024);
                            return new WebResourceResponse(CompanionFont.mime(path),null,200,"OK",
                                java.util.Map.of("Cache-Control","no-store"),new ByteArrayInputStream(bytes));
                        }catch(Exception e){
                            return new WebResourceResponse("text/plain","utf-8",404,"Not Found",java.util.Map.of(),new ByteArrayInputStream(new byte[0]));
                        }
                    }
                    String name=request.getUrl().getLastPathSegment();
                    if(!"companion.padport.local".equals(request.getUrl().getHost())||!java.util.Set.of(page+".html",page+".js","ui-strings.js").contains(name==null?"":name))
                        return new WebResourceResponse("text/plain","utf-8",403,"Blocked",java.util.Map.of(),new ByteArrayInputStream(new byte[0]));
                    try(InputStream in=activity.getAssets().open(name)){
                        byte[] data=GameSource.readBounded(in,1024*1024);
                        if("ui-strings.js".equals(name)){
                            String config="window.__PADPORT_CONFIG__={language:"+JSONObject.quote(AppLanguage.code(activity))+
                                ",logoUrl:"+JSONObject.quote("look-outside-panel".equals(page)?LOOK_OUTSIDE_LOGO:"")+"};\n";
                            data=(config+new String(data,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                        }
                        return new WebResourceResponse(name.endsWith("html")?"text/html":"application/javascript","utf-8",new ByteArrayInputStream(data));
                    }catch(IOException e){return new WebResourceResponse("text/plain","utf-8",new ByteArrayInputStream(new byte[0]));}
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
                @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){dismiss();return true;}
            });
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            DisplayRate.apply(getWindow(),getDisplay(),DisplayRate.enabled(getContext()));
            web.loadUrl("https://companion.padport.local/"+page+".html");
        }
        void update(String json){latest=json;if(web!=null)web.evaluateJavascript("if(window.GameCompanionPanel)GameCompanionPanel.update("+json+");",null);}
        void destroy(){if(web!=null){web.removeJavascriptInterface("GameCompanionHost");web.destroy();web=null;}}
    }
}
