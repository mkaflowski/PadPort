package pl.padport.app;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.junit.Test;

public class PcRuntimeFilesTest {
    private static Map<String,Long> files(Object... pairs){
        Map<String,Long> map=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],((Number)pairs[i+1]).longValue());
        return map;
    }
    @Test public void nwjsRuntimeAndSteamLibrariesAreListedBiggestFirst(){
        var result=PcRuntimeFiles.find(files(
            "nw.dll",300_000_000,"Game.exe",3_000_000,"icudtl.dat",10_000_000,"resources.pak",22_000_000,
            "locales/pl.pak",600_000,"locales/pl.pak.info",1_000_000,"swiftshader/libEGL.dll",400_000,
            "lib/greenworks-win64.node",700_000,"lib/steam_api64.dll.original",300_000,"steam_api64.dll",2_000_000,
            "index.html",1_000,"js/rmmz_core.js",175_000),false);
        assertEquals(List.of("nw.dll","resources.pak","icudtl.dat","Game.exe","steam_api64.dll","locales/pl.pak.info",
            "lib/greenworks-win64.node","locales/pl.pak","swiftshader/libEGL.dll","lib/steam_api64.dll.original"),result.paths());
        assertEquals(340_000_000L,result.bytes());assertTrue(result.worthAsking());
        assertEquals(List.of("nw.dll","resources.pak","icudtl.dat"),PcRuntimeFiles.examples(result.paths(),3));
        assertEquals(List.of("nw.dll","resources.pak","icudtl.dat","Game.exe","steam_api64.dll","locales/","lib/"),
            PcRuntimeFiles.examples(result.paths(),7));
    }
    @Test public void gameDataTextAndNestedFilesAreNeverRemovable(){
        for(String path:List.of("index.html","package.json","game_messages.csv","credits.html","debug.log","steam_appid.txt",
                "www/index.html","www/nw.dll","img/system/IconSet.png_","js/plugins/FOSSIL.js","data/System.json",
                "locales/readme.txt","lib/steam_appid.txt","lib/steam_settings/force_language.txt","locales/sub/x.pak",
                "mods/Example_Mod/plugin.dll","save/config.rmmzsave",".dll","Game.exe.config"))
            assertFalse(path,PcRuntimeFiles.removable(path));
        assertTrue(PcRuntimeFiles.removable("GAME.EXE"));assertTrue(PcRuntimeFiles.removable("Locales/en-US.pak"));
    }
    @Test public void rgssGamesAndSmallLeftoversAreNotOffered(){
        assertTrue(PcRuntimeFiles.find(files("Game.exe",3_000_000,"RGSS104E.dll",900_000),true).paths().isEmpty());
        assertFalse(PcRuntimeFiles.find(files("steam_api.dll",300_000,"index.html",1_000),false).worthAsking());
        assertFalse(PcRuntimeFiles.find(files("nw.dll",-1),false).worthAsking());
    }
    /** ELDERFIELD_GAME=<folder>: everything the game loads stays; the NW.js runtime goes. */
    @Test public void realElderfieldFolder() throws Exception {
        String game=System.getenv("ELDERFIELD_GAME");
        assumeTrue(game!=null&&!game.isEmpty());
        Path root=Path.of(game);Map<String,Long> index=new LinkedHashMap<>();
        try(Stream<Path> walk=Files.walk(root)){
            for(Path p:(Iterable<Path>)walk.filter(Files::isRegularFile)::iterator)
                index.put(root.relativize(p).toString().replace('\\','/'),Files.size(p));
        }
        var result=PcRuntimeFiles.find(index,false);
        assertTrue(result.paths().containsAll(List.of("nw.dll","node.dll","Game.exe","resources.pak","icudtl.dat","lib/greenworks-win64.node")));
        assertTrue(result.paths().stream().anyMatch(p->p.startsWith("locales/")));
        for(String path:result.paths()){
            String lower=path.toLowerCase(Locale.ROOT);
            assertFalse(path,lower.startsWith("img/")||lower.startsWith("js/")||lower.startsWith("data/")||lower.startsWith("audio/")
                ||lower.startsWith("media/")||lower.startsWith("tables/")||lower.startsWith("fonts/")||lower.endsWith(".html")
                ||lower.endsWith(".csv")||lower.endsWith(".json")&&!lower.equals("vk_swiftshader_icd.json"));
        }
        assertTrue("over 500 MB: "+result.bytes(),result.bytes()>500L*1024*1024);
        System.out.println("Elderfield PC runtime: "+result.paths().size()+" files, "+result.bytes()/(1024*1024)+" MB");
    }
}
