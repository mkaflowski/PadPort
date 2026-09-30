package pl.padport.app;
import org.junit.Test;
import static org.junit.Assert.*;

public class AssetPathsTest {
    @Test public void pathsKeepUnicodeSpacesAndFileCase(){
        assertEquals("img/characters/Żaba 1.png",AssetPaths.normalize("/img/./characters/Żaba 1.png"));
        assertEquals("js/plugins.js",AssetPaths.normalize("js\\plugins.js"));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsLeavingSelectedFolder(){AssetPaths.normalize("/img/../../outside.txt");}
    @Test public void desktopAudioWorksForMobileRequests(){
        assertEquals("audio/bgm/theme.ogg",AssetPaths.audioFallback("audio/bgm/theme.m4a"));
        assertEquals("audio/bgm/theme.ogg_",AssetPaths.audioFallback("audio/bgm/theme.m4a_"));
        assertEquals("audio/bgm/theme.rpgmvo",AssetPaths.audioFallback("audio/bgm/theme.rpgmvm"));
    }
    @Test public void rangeReadsSupportMediaSeeking(){
        assertArrayEquals(new long[]{10,19},AssetPaths.range("bytes=10-19",100));
        assertArrayEquals(new long[]{90,99},AssetPaths.range("bytes=-10",100));
        assertArrayEquals(new long[]{10,99},AssetPaths.range("bytes=10-",100));
        assertArrayEquals(new long[]{0,99},AssetPaths.range("bytes=0-999",100));
        assertNull(AssetPaths.range("bytes=100-200",100));
        assertNull(AssetPaths.range("bytes=0-1,3-4",100));
    }
}
