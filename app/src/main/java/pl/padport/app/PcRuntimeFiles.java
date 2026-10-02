package pl.padport.app;

import java.util.*;

/**
 * Files of the Windows NW.js runtime (and Steam's native libraries) shipped next to an
 * RPG Maker MV/MZ game. PadPort never loads them; deleting them is offered once the user
 * has seen the list and the size. Deliberately narrow: only the folder root and the
 * runtime's own subfolders, never game data (www/, img/, js/...) or text files.
 */
final class PcRuntimeFiles {
    record Result(List<String> paths,long bytes) {
        boolean worthAsking(){return bytes>=MIN_BYTES;}
    }
    static final long MIN_BYTES=5L*1024*1024;
    private static final Set<String> ROOT_NAMES=Set.of(
        "icudtl.dat","natives_blob.bin","snapshot_blob.bin","v8_context_snapshot.bin","vk_swiftshader_icd.json");
    private static final String[] ROOT_SUFFIXES={".dll",".exe",".node",".pak",".dll.original"};
    /** Runtime subfolders and the file types that may be removed from them. */
    private static final Map<String,String[]> FOLDERS=Map.of(
        "locales/",new String[]{".pak",".pak.info"},
        "swiftshader/",new String[]{".dll"},
        "lib/",new String[]{".dll",".node",".dll.original"});

    private PcRuntimeFiles(){}

    /** @param files game folder index: path relative to the picked folder -> size in bytes (-1 unknown). */
    static Result find(Map<String,Long> files,boolean rgss){
        if(rgss)return new Result(List.of(),0);   // RGSS games identify themselves by Game.exe/Game.ini
        List<String> paths=new ArrayList<>();long bytes=0;
        for(var file:files.entrySet()){
            if(!removable(file.getKey()))continue;
            paths.add(file.getKey());
            bytes+=Math.max(0,file.getValue());
        }
        paths.sort((a,b)->Long.compare(Math.max(0,files.get(b)),Math.max(0,files.get(a))));
        return new Result(List.copyOf(paths),bytes);
    }

    static boolean removable(String path){
        String lower=path.toLowerCase(Locale.ROOT);
        int slash=lower.indexOf('/');
        if(slash<0)return ROOT_NAMES.contains(lower)||endsWith(lower,ROOT_SUFFIXES);
        if(lower.indexOf('/',slash+1)>=0)return false;   // never deeper than one runtime subfolder
        String[] types=FOLDERS.get(lower.substring(0,slash+1));
        return types!=null&&endsWith(lower,types);
    }

    /** Up to {@code limit} names for the dialog, folders collapsed ("locales/"). */
    static List<String> examples(List<String> paths,int limit){
        LinkedHashSet<String> names=new LinkedHashSet<>();
        for(String path:paths){
            int slash=path.indexOf('/');
            names.add(slash<0?path:path.substring(0,slash+1));
            if(names.size()>=limit)break;
        }
        return List.copyOf(names);
    }

    private static boolean endsWith(String name,String[] suffixes){
        for(String suffix:suffixes)if(name.endsWith(suffix)&&name.length()>suffix.length())return true;
        return false;
    }
}
