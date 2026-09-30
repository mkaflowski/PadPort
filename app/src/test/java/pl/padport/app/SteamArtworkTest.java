package pl.padport.app;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class SteamArtworkTest {
    @Test public void readsSteamAppIdFile() {
        assertEquals("206440", SteamArtwork.appIdFromFile("206440\r\n"));
        assertNull(SteamArtwork.appIdFromFile("not an id"));
        assertNull(SteamArtwork.appIdFromFile(null));
    }

    @Test public void acceptsOnlyExactTitleMatches() throws Exception {
        JSONObject search = new JSONObject("{\"items\":[{\"type\":\"app\",\"name\":\"To the Moon: Holiday Minisode\",\"id\":1},"
            + "{\"type\":\"app\",\"name\":\"To the Moon™\",\"id\":206440},{\"type\":\"app\",\"name\":\"TO THE MOON\",\"id\":3626460}]}");
        assertEquals("206440", SteamArtwork.pickApp(search, "To the Moon"));
        assertNull(SteamArtwork.pickApp(search, "Finding Paradise"));
        assertNull(SteamArtwork.pickApp(new JSONObject("{}"), "To the Moon"));
        assertEquals(SteamArtwork.normalize("Look Outside!"), SteamArtwork.normalize("look  outside"));
    }
}
