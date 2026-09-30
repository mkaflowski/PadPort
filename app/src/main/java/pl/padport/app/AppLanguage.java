package pl.padport.app;

import android.content.Context;
/** Android selects the resources; WebView uses the same resolved language. */
final class AppLanguage {
    static String code(Context context){
        return context.getString(R.string.ui_language);
    }
    /** Compatibility for runtime activities: do not override the Android locale. */
    static Context wrap(Context context){
        return context;
    }
    static String text(Context context,int id,Object... args){return context.getString(id,args);}
}
