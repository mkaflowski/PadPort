package pl.padport.app;

import android.content.Context;
import android.net.Uri;
import android.webkit.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

final class GameWebClient extends WebViewClient {
    final Context context;
    final GameSource source;
    final String origin;
    final Consumer<String> log;
    Runnable loaded=()->{};
    /** Renderer crashed (true) or was killed for memory (false). The WebView must be destroyed. */
    Consumer<Boolean> gone=crashed->{};
    @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail) {
        // Returning true keeps the app alive; otherwise Chromium kills the whole process.
        gone.accept(detail.didCrash());
        return true;
    }
    GameWebClient(Context c,GameSource s,Consumer<String> log) {
        context=c;source=s;origin=s==null?"https://tester.padport.local":s.origin();this.log=log;
    }
    private WebResourceResponse bytes(String mime,byte[] bytes) {
        return response(mime,200,"OK",new ByteArrayInputStream(bytes),Map.of("Cache-Control","no-store","Content-Length",String.valueOf(bytes.length)));
    }
    private WebResourceResponse error(int status,String reason,String path) {
        if(status!=404 || !path.contains("favicon")) log.accept(status+" "+path);
        return response("text/plain",status,reason,new ByteArrayInputStream(reason.getBytes(StandardCharsets.UTF_8)),Map.of("Cache-Control","no-store"));
    }
    private WebResourceResponse response(String mime,int status,String reason,InputStream stream,Map<String,String> headers) {
        HashMap<String,String> map=new HashMap<>(headers);
        map.put("Access-Control-Allow-Origin",origin);
        return new WebResourceResponse(mime,mime.startsWith("text/")||mime.contains("javascript")||mime.contains("json")?"utf-8":null,status,reason,map,stream);
    }
    static String inject(String html) {
        String script="<script src=\"/__padport__/bridge.js\"></script>";
        java.util.regex.Matcher head=java.util.regex.Pattern.compile("<head\\b[^>]*>",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if(head.find()) return html.substring(0,head.end())+script+html.substring(head.end());
        return script+html;
    }
    private byte[] asset(String name) throws IOException {
        try(InputStream in=context.getAssets().open(name)) {return GameSource.readBounded(context,in,1024*1024);}
    }
    @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
        Uri uri=request.getUrl();String url=uri.toString();
        if(!"https".equals(uri.getScheme()) || !Uri.parse(origin).getHost().equals(uri.getHost())) return error(403,"External resource blocked",url);
        String path;
        try {path=AssetPaths.normalize(uri.getPath());}
        catch(Exception e) {return error(403,"Invalid path",url);}
        if(path.isEmpty()) path="index.html";
        try {
            if(path.equals("__padport__/bridge.js")) {
                JSONObject config=new JSONObject().put("title",source==null?context.getString(R.string.controller_tester):source.title)
                    .put("engine",source==null?"diagnostics":source.engine).put("language",AppLanguage.code(context))
                    .put("dualScreenProfile",source==null?"":DualScreenProfile.identify(source.title,source.engine));
                return bytes("application/javascript",("window.__PADPORT_CONFIG__="+config+";\n"+
                    new String(asset("ui-strings.js"),StandardCharsets.UTF_8)+"\n"+new String(asset("bridge.js"),StandardCharsets.UTF_8)+"\n"+
                    new String(asset("look-outside-dual.js"),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            }
            if(path.equals("index.html")) {
                byte[] html=source==null?asset("diagnostics.html"):source.read(path,4*1024*1024);
                return bytes("text/html",inject(new String(html,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            }
            if(source==null) return error(404,"Not found",path);
            GameSource.Entry entry=source.entry(path);
            if(entry==null) return error(404,"Not found",path);
            String rangeHeader=null;
            for(var h:request.getRequestHeaders().entrySet()) if(h.getKey().equalsIgnoreCase("Range")) rangeHeader=h.getValue();
            Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");
            InputStream in;
            int status=200;
            if(rangeHeader!=null && entry.size()>=0) {
                long[] range=AssetPaths.range(rangeHeader,entry.size());
                if(range==null) return response("text/plain",416,"Range Not Satisfiable",new ByteArrayInputStream(new byte[0]),Map.of("Content-Range","bytes */"+entry.size()));
                in=source.open(path);
                try {skip(in,range[0]);} catch(Exception e) {in.close();throw e;}
                in=new LimitedStream(in,range[1]-range[0]+1);
                headers.put("Content-Range","bytes "+range[0]+"-"+range[1]+"/"+entry.size());
                headers.put("Content-Length",String.valueOf(range[1]-range[0]+1));status=206;
            } else {
                in=source.open(path);
                if(entry.size()>=0) headers.put("Content-Length",String.valueOf(entry.size()));
            }
            headers.put("Accept-Ranges","bytes");
            return response(mime(source.mimePath(path)),status,status==206?"Partial Content":"OK",in,headers);
        } catch(Exception e) {log.accept(e.toString());return error(500,"Read failed",path);}
    }
    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
        return !request.getUrl().toString().startsWith(origin+"/");
    }
    @Override public void onPageFinished(WebView view,String url) {loaded.run();}
    @Override public void onReceivedError(WebView view,WebResourceRequest req,WebResourceError error) {
        log.accept("WebView: "+error.getDescription()+" @ "+req.getUrl());
    }
    private static void skip(InputStream in,long count) throws IOException {
        while(count>0) {long n=in.skip(count);if(n==0){if(in.read()==-1)throw new EOFException();n=1;}count-=n;}
    }
    static String mime(String name) {
        name=name.toLowerCase(Locale.ROOT);
        if(name.endsWith(".wasm"))return "application/wasm";
        if(name.endsWith(".js"))return "application/javascript";
        if(name.endsWith(".json"))return "application/json";
        if(name.endsWith(".ogg"))return "audio/ogg";
        if(name.endsWith(".ttf"))return "font/ttf";
        if(name.endsWith(".woff"))return "font/woff";
        String ext=name.lastIndexOf('.')<0?"":name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        String result=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return result==null?"application/octet-stream":result;
    }
    private static final class LimitedStream extends FilterInputStream {
        long left;
        LimitedStream(InputStream in,long limit){super(in);left=limit;}
        @Override public int read()throws IOException{if(left<=0)return -1;int value=super.read();if(value!=-1)left--;return value;}
        @Override public int read(byte[] b,int off,int len)throws IOException{if(left<=0)return -1;int n=in.read(b,off,(int)Math.min(left,len));if(n>0)left-=n;return n;}
    }
}
