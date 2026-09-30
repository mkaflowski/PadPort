package pl.padport.app;

import java.util.ArrayList;
import java.util.List;

public final class AssetPaths {
    private AssetPaths() {}
    /** Takes an already URL-decoded path. All reads must stay inside the selected tree. */
    public static String normalize(String path) {
        if(path==null || path.indexOf('\0')>=0) throw new IllegalArgumentException("Invalid path");
        List<String> parts=new ArrayList<>();
        for(String part:path.replace('\\','/').split("/")) {
            if(part.isEmpty() || part.equals(".")) continue;
            if(part.equals("..")) throw new IllegalArgumentException("Path leaves game folder");
            parts.add(part);
        }
        return String.join("/",parts);
    }
    public static String audioFallback(String path) {
        if(path.endsWith(".m4a")) return path.substring(0,path.length()-4)+".ogg";
        if(path.endsWith(".m4a_")) return path.substring(0,path.length()-5)+".ogg_";
        if(path.endsWith(".rpgmvm")) return path.substring(0,path.length()-7)+".rpgmvo";
        return path;
    }
    /** Inclusive byte range. Illegal/multi ranges return null and become HTTP 416. */
    public static long[] range(String header,long size) {
        if(header==null) return new long[]{0,size-1};
        if(size<=0 || !header.matches("bytes=\\d*-\\d*")) return null;
        String[] parts=header.substring(6).split("-",-1);
        try {
            long start,end;
            if(parts[0].isEmpty()) {
                long suffix=Long.parseLong(parts[1]);
                if(suffix<=0) return null;
                start=Math.max(0,size-suffix);end=size-1;
            } else {
                start=Long.parseLong(parts[0]);
                end=parts[1].isEmpty()?size-1:Math.min(Long.parseLong(parts[1]),size-1);
            }
            return start<0 || start>=size || end<start ? null : new long[]{start,end};
        } catch(NumberFormatException e) {return null;}
    }
}
