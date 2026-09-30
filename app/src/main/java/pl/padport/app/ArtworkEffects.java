package pl.padport.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;
import android.widget.ImageView;

/** Presentation only: cached originals stay sharp and foreground controls are separate. */
final class ArtworkEffects {
    private static final float BLUR_DP=6f;

    static void apply(ImageView picture,Context context){
        picture.setColorFilter(0x20000000,PorterDuff.Mode.SRC_ATOP); // ~12.5% darker.
        if(Build.VERSION.SDK_INT>=31){
            float radius=BLUR_DP*context.getResources().getDisplayMetrics().density;
            picture.setRenderEffect(RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP));
        }
    }

    /** Called by the artwork worker, only for Android 8–11. */
    @SuppressWarnings("deprecation")
    static Bitmap prepare(Context context,Bitmap source,int viewWidth,int viewHeight){
        if(source==null||Build.VERSION.SDK_INT>=31)return source;
        RenderScript rs=null;Allocation input=null,output=null;ScriptIntrinsicBlur blur=null;
        Bitmap working=null,result=null;
        try{
            float density=context.getResources().getDisplayMetrics().density;
            float scale=Math.max((float)Math.max(1,viewWidth)/source.getWidth(),(float)Math.max(1,viewHeight)/source.getHeight());
            float radius=Math.max(.01f,Math.min(25f,BLUR_DP*density/scale));
            rs=RenderScript.create(context);
            working=source.copy(Bitmap.Config.ARGB_8888,false);
            input=Allocation.createFromBitmap(rs,working,Allocation.MipmapControl.MIPMAP_NONE,Allocation.USAGE_SCRIPT);
            output=Allocation.createTyped(rs,input.getType());
            blur=ScriptIntrinsicBlur.create(rs,Element.U8_4(rs));
            blur.setRadius(radius);blur.setInput(input);blur.forEach(output);
            result=Bitmap.createBitmap(working.getWidth(),working.getHeight(),Bitmap.Config.ARGB_8888);
            result.setDensity(source.getDensity());output.copyTo(result);
            return result;
        }catch(RuntimeException e){
            Log.w("PadPort","Unable to blur thumbnail",e);
            if(result!=null)result.recycle();
            return source;
        }finally{
            if(blur!=null)blur.destroy();if(output!=null)output.destroy();if(input!=null)input.destroy();
            if(rs!=null)rs.destroy();if(working!=null)working.recycle();
        }
    }
}
