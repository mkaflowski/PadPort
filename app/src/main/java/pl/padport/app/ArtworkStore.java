package pl.padport.app;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Small, private thumbnails. Source game files and selected pictures are read-only. */
final class ArtworkStore {
    private static final int MAX_BYTES=32*1024*1024,MAX_EDGE=1280;
    private static File file(Context context,String id,String suffix) {
        if(!id.matches("[0-9a-f]{24}"))throw new IllegalArgumentException("Invalid game identifier");
        File directory=new File(context.getFilesDir(),"artwork");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException(AppLanguage.text(context,R.string.thumbnail_directory_error));
        return new File(directory,id+suffix);
    }
    static Bitmap load(Context context,JSONObject game) throws Exception {
        String id=game.getString("id");
        Bitmap image=readCache(file(context,id,".custom.jpg"));
        if(image!=null)return image;
        image=readCache(file(context,id,".auto.jpg"));
        if(image!=null&&!blank(image))return image;
        if(image!=null){image.recycle();Files.deleteIfExists(file(context,id,".auto.jpg").toPath());}
        if(file(context,id,".none").exists())return null;
        GameSource source=GameSource.restore(context,game);
        if(source.isRgss()){
            byte[] title=null;
            try{title=RgssArtwork.titleImage(source);}catch(IOException ignored){/* A plain card is fine. */}
            Bitmap decoded=title==null?null:decode(context,title);
            // Games with a map-based title (e.g. To the Moon) ship a plain black title image.
            if(decoded==null||blank(decoded)){if(decoded!=null)decoded.recycle();return steamFallback(context,source,id);}
            save(context,file(context,id,".auto.jpg"),decoded);
            return decoded;
        }
        JSONObject system=new JSONObject(new String(source.read("data/System.json",8*1024*1024),StandardCharsets.UTF_8));
        String key=system.optString("encryptionKey","");
        Bitmap background=titleLayer(source,"img/titles1/",system.optString("title1Name",""),key,true);
        Bitmap foreground=titleLayer(source,"img/titles2/",system.optString("title2Name",""),key,false);
        if(background==null&&foreground==null)return steamFallback(context,source,id);
        JSONObject advanced=system.optJSONObject("advanced");
        int width=advanced==null?816:advanced.optInt("screenWidth",816);
        int height=advanced==null?624:advanced.optInt("screenHeight",624);
        if(width<=0||height<=0){width=816;height=624;}
        double scale=Math.min(1.0,(double)MAX_EDGE/Math.max(width,height));
        width=Math.max(1,(int)Math.round(width*scale));height=Math.max(1,(int)Math.round(height*scale));
        Bitmap composed=Bitmap.createBitmap(width,height,Bitmap.Config.RGB_565);
        Canvas canvas=new Canvas(composed);canvas.drawColor(Ui.CARD);
        drawCover(canvas,background,width,height);drawCover(canvas,foreground,width,height);
        if(background!=null)background.recycle();if(foreground!=null)foreground.recycle();
        if(blank(composed)){composed.recycle();return steamFallback(context,source,id);}
        save(context,file(context,id,".auto.jpg"),composed);
        return composed;
    }
    /** A single-colour picture (black title placeholders) is no usable artwork. */
    static boolean blank(Bitmap image){
        int w=image.getWidth(),h=image.getHeight(),low=255,high=0;
        for(int y=0;y<16;y++)for(int x=0;x<16;x++){
            int c=image.getPixel(Math.min(w-1,x*w/16+w/32),Math.min(h-1,y*h/16+h/32));
            int luma=(Color.red(c)*299+Color.green(c)*587+Color.blue(c)*114)/1000;
            low=Math.min(low,luma);high=Math.max(high,luma);
        }
        return high-low<12;
    }
    /**
     * No title image in the game files: try the public Steam store artwork.
     * "Not on Steam" is remembered (.none); being offline is retried later.
     */
    private static Bitmap steamFallback(Context context,GameSource source,String id) throws Exception {
        byte[] bytes;
        try{bytes=SteamArtwork.download(source);}
        catch(SteamArtwork.NotFound e){
            try(OutputStream ignored=new FileOutputStream(file(context,id,".none"))) { }
            return null;
        }
        catch(IOException e){return null;}
        Bitmap image=decode(context,bytes);
        save(context,file(context,id,".auto.jpg"),image);
        return image;
    }
    private static Bitmap titleLayer(GameSource source,String folder,String name,String key,boolean fallback) throws Exception {
        List<String> paths=new ArrayList<>();
        if(!name.isEmpty()){
            paths.add(folder+name);
            for(String ext:new String[]{".png",".png_",".rpgmvp",".jpg",".webp"})paths.add(folder+name+ext);
        }
        if(fallback){
            String prefix=(source.prefix+folder).toLowerCase(Locale.ROOT);
            source.entries.keySet().stream().filter(p->p.toLowerCase(Locale.ROOT).startsWith(prefix))
                .filter(p->p.toLowerCase(Locale.ROOT).matches(".*\\.(png_?|rpgmvp|jpg|jpeg|webp)$"))
                .sorted(String.CASE_INSENSITIVE_ORDER).forEach(p->paths.add(p.substring(source.prefix.length())));
        }
        for(String path:new LinkedHashSet<>(paths)){
            try {
                if(source.entry(path)!=null)return decode(source.context,RpgImage.decode(source.read(path,MAX_BYTES),key));
            }catch(IOException|IllegalArgumentException ignored){/* Try another title image; artwork must not block the game. */}
        }
        return null;
    }
    private static Bitmap decode(Context context,byte[] bytes) throws IOException {
        BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,size);
        if(size.outWidth<=0||size.outHeight<=0)throw new IOException(AppLanguage.text(context,R.string.image_decode_error));
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(Math.max(size.outWidth,size.outHeight)/options.inSampleSize>MAX_EDGE)options.inSampleSize*=2;
        Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(image==null)throw new IOException(AppLanguage.text(context,R.string.image_decode_error));
        return image;
    }
    private static Bitmap readCache(File path) {
        if(!path.isFile())return null;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(path.getPath(),options);
    }
    private static void drawCover(Canvas canvas,Bitmap image,int width,int height) {
        if(image==null)return;
        float scale=Math.max((float)width/image.getWidth(),(float)height/image.getHeight());
        float w=image.getWidth()*scale,h=image.getHeight()*scale;
        canvas.drawBitmap(image,null,new RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),new Paint(Paint.FILTER_BITMAP_FLAG));
    }
    private static void save(Context context,File path,Bitmap image) throws IOException {
        File tmp=new File(path+".tmp");
        try{
            try(OutputStream out=new FileOutputStream(tmp)){
                if(!image.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IOException(AppLanguage.text(context,R.string.thumbnail_save_error));
            }
            Files.move(tmp.toPath(),path.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp.toPath());}
    }
    static void choose(Context context,String id,Uri uri) throws IOException {
        byte[] bytes;
        try(InputStream input=context.getContentResolver().openInputStream(uri)){
            if(input==null)throw new IOException(AppLanguage.text(context,R.string.image_access_error));
            bytes=GameSource.readBounded(context,input,MAX_BYTES);
        }
        Bitmap original=decode(context,bytes);
        Bitmap image=Bitmap.createBitmap(original.getWidth(),original.getHeight(),Bitmap.Config.RGB_565);
        Canvas canvas=new Canvas(image);canvas.drawColor(Ui.CARD);canvas.drawBitmap(original,0,0,null);
        try{save(context,file(context,id,".custom.jpg"),image);}
        finally{original.recycle();image.recycle();}
    }
    static void useAutomatic(Context context,String id) throws IOException {
        Files.deleteIfExists(file(context,id,".custom.jpg").toPath());invalidateAutomatic(context,id);
    }
    static void invalidateAutomatic(Context context,String id) throws IOException {
        Files.deleteIfExists(file(context,id,".auto.jpg").toPath());
        Files.deleteIfExists(file(context,id,".none").toPath());
    }
}
