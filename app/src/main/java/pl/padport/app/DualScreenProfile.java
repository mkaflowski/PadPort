package pl.padport.app;

import org.json.JSONObject;

/** Explicit game adapters, not an assumption that every RPG Maker game supports two screens. */
final class DualScreenProfile {
    static final String LOOK_OUTSIDE="look-outside";
    static final String TO_THE_MOON="to-the-moon";
    static String identify(String title,String engine){
        if("MZ".equals(engine)&&"look outside".equalsIgnoreCase(title.trim()))return LOOK_OUTSIDE;
        if("XP".equals(engine)&&"to the moon".equalsIgnoreCase(title.trim()))return TO_THE_MOON;
        return "";
    }
    static boolean supports(JSONObject game){
        return game!=null&&!identify(game.optString("title"),game.optString("engine")).isEmpty();
    }
    static boolean enabled(JSONObject game){return supports(game)&&game.optBoolean("dualScreen",false);}
    static boolean shouldOffer(JSONObject game,boolean alreadyAdded,boolean secondDisplay){
        return supports(game)&&!alreadyAdded&&secondDisplay;
    }
}
