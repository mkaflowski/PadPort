package pl.padport.app;

import org.json.JSONObject;

/** Explicit game adapters, not an assumption that every RPG Maker game supports two screens. */
final class DualScreenProfile {
    static final String LOOK_OUTSIDE="look-outside";
    static final String TO_THE_MOON="to-the-moon";
    static final String FEAR_AND_HUNGER="fear-and-hunger";
    static final String ELDERFIELD=GameCompat.ELDERFIELD;
    static String identify(String title,String engine){
        if("MZ".equals(engine)&&"look outside".equalsIgnoreCase(title.trim()))return LOOK_OUTSIDE;
        if("XP".equals(engine)&&"to the moon".equalsIgnoreCase(title.trim()))return TO_THE_MOON;
        // Fear & Hunger 1 only: Termina is a different game with other data.
        if("MV".equals(engine)&&"fear & hunger".equalsIgnoreCase(title.trim()))return FEAR_AND_HUNGER;
        if(ELDERFIELD.equals(GameCompat.profile(title,engine)))return ELDERFIELD;
        return "";
    }
    /** Lower-screen page (assets/<page>.html/.js) of a profile. */
    static String panel(String profile){
        if(TO_THE_MOON.equals(profile))return "to-the-moon-panel";
        if(FEAR_AND_HUNGER.equals(profile))return "fear-and-hunger-panel";
        if(ELDERFIELD.equals(profile))return "elderfield-panel";
        return "look-outside-panel";
    }
    static boolean supports(JSONObject game){
        return game!=null&&!identify(game.optString("title"),game.optString("engine")).isEmpty();
    }
    static boolean enabled(JSONObject game){return supports(game)&&game.optBoolean("dualScreen",false);}
    static boolean shouldOffer(JSONObject game,boolean alreadyAdded,boolean secondDisplay){
        return supports(game)&&!alreadyAdded&&secondDisplay;
    }
}
