package pl.padport.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class CompanionFontTest {
    @Test public void supportsGameFontNamesAndFormats(){
        assertEquals("fonts/Zażółć gęślą.ttf",CompanionFont.path("fonts/Zażółć gęślą.ttf"));
        assertEquals("fonts/subdir/Main.OTF",CompanionFont.path("fonts\\subdir\\Main.OTF"));
        assertEquals("font/otf",CompanionFont.mime("fonts/Main.OTF"));
        assertEquals("font/woff2",CompanionFont.mime("fonts/ui.woff2"));
    }
    @Test public void onlyFontFilesInsideTheGameFontDirectoryAreServed(){
        for(String path:new String[]{null,"fonts/../../other.ttf","fonts/../Data/secret.ttf","data/System.json","fonts/script.js","https://example.org/fonts/a.ttf","fonts/a.ttf\0"}){
            assertThrows(IllegalArgumentException.class,()->CompanionFont.path(path));
        }
    }
}
