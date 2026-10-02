package pl.padport.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Runtime fixes for PC (NW.js) plugin code that runs in PadPort's browser engines.
 * The JavaScript side is assets/game-compat.js; see "Zgodność gier" in DEVELOPMENT.md.
 */
final class GameCompat {
    static final String ELDERFIELD="welcome-to-elderfield";
    private static final int MAX_TABLES=1000;
    /** FOSSIL redirects to FOSSILindex.html only if it is the first, enabled plugin. */
    private static final Pattern FOSSIL_FIRST=Pattern.compile(
        "\\$plugins\\s*=\\s*\\[\\s*\\{\\s*\"name\"\\s*:\\s*\"FOSSIL\"\\s*,\\s*\"status\"\\s*:\\s*true");

    private GameCompat(){}

    static String profile(String title,String engine){
        if("MZ".equals(engine)&&title!=null&&"welcome to elderfield".equalsIgnoreCase(title.trim()))return ELDERFIELD;
        return "";
    }

    /** Adds compat fields to window.__PADPORT_CONFIG__. */
    static JSONObject config(JSONObject config,GameSource source) throws Exception {
        String profile=source==null?"":profile(source.title,source.engine);
        config.put("compat",profile);
        if(ELDERFIELD.equals(profile)){
            // Cyclone-Steam and WTE_PluginToggleManager read process.* as soon as require exists.
            config.put("nodeShim",false);
            // LookupTableComparison lists Tables/ only under NW.js; the browser path needs the file names.
            config.put("tables",tables(source));
        }
        return config;
    }

    static JSONArray tables(GameSource source){
        JSONArray result=new JSONArray();
        String start=(source.prefix+"Tables/").toLowerCase(Locale.ROOT);
        for(String path:source.entries.keySet()){
            String lower=path.toLowerCase(Locale.ROOT);
            if(!lower.startsWith(start)||!lower.endsWith(".csv"))continue;
            result.put(path.substring(source.prefix.length()));
            if(result.length()>=MAX_TABLES)break;
        }
        return result;
    }

    /**
     * Under NW.js FOSSIL writes FOSSILindex.html (index.html with main.js replaced by
     * plugins/FOSSIL.js) and reloads into it. PadPort cannot write game files, so it
     * serves that page directly; FOSSIL's main mode needs no Node APIs.
     */
    static String fossilEntry(String html,String pluginsHead){
        if(pluginsHead==null||!FOSSIL_FIRST.matcher(pluginsHead).find())return html;
        int at=html.indexOf("js/main.js");
        if(at<0)return html;
        return html.substring(0,at)+"js/plugins/FOSSIL.js"+html.substring(at+"js/main.js".length());
    }

    static String entry(GameSource source,String html){
        if(source==null||source.isRgss()||source.entry("js/plugins/FOSSIL.js")==null)return html;
        try{
            return fossilEntry(html,new String(source.head("js/plugins.js",64*1024),StandardCharsets.UTF_8));
        } catch(IOException e){
            return html;
        }
    }
}
