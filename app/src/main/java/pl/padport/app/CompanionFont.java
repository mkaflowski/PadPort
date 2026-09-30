package pl.padport.app;

import java.util.Locale;

/** Font paths received from the live game, relative to the same read-only SAF root. */
final class CompanionFont {
    static String path(String decoded){
        if(decoded==null||decoded.length()>2048)throw new IllegalArgumentException("Invalid font path");
        String path=AssetPaths.normalize(decoded),lower=path.toLowerCase(Locale.ROOT);
        if(!lower.startsWith("fonts/")||!lower.matches(".*\\.(ttf|otf|woff|woff2)$"))throw new IllegalArgumentException("Not a game font");
        return path;
    }
    static String mime(String path){
        String lower=path.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".woff2"))return "font/woff2";
        if(lower.endsWith(".woff"))return "font/woff";
        if(lower.endsWith(".otf"))return "font/otf";
        return "font/ttf";
    }
}
