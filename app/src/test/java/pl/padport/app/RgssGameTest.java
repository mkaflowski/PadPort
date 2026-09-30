package pl.padport.app;

import static org.junit.Assert.*;

import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RgssGameTest {
    private static RgssGame.Files files(Map<String, String> content) {
        return new RgssGame.Files() {
            public Collection<String> paths() { return content.keySet(); }
            public byte[] read(String path) throws IOException {
                String value = content.get(path);
                if (value == null) throw new IOException(path);
                return value.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    @Test public void detectsToTheMoonLayout() throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("Game.ini", "[Game]\r\nLibrary=RGSS102E.dll\r\nScripts=Data\\Scripts.rxdata\r\nTitle=Untitled\r\n");
        f.put("To the Moon.ini", "[Game]\r\nLibrary=RGSS102E.dll\r\nScripts=Data\\Scripts.rxdata\r\nTitle=To the Moon\r\n");
        f.put("To the Moon.rgssad", "");
        f.put("mkxp.conf", "execName=To the Moon\nRTP=lang.dat\n");
        f.put("Audio/BGM/theme.ogg", "");
        RgssGame.Info info = RgssGame.detect(files(f));
        assertNotNull(info);
        assertEquals("To the Moon", info.exec());
        assertEquals("To the Moon", info.title());
        assertEquals(1, info.version());
        assertEquals("XP", info.engine());
    }

    @Test public void detectsVxAceAndLooseXp() throws Exception {
        Map<String, String> ace = new LinkedHashMap<>();
        ace.put("Game.ini", "[Game]\nRTP=RPGVXAce\nLibrary=System\\RGSS301.dll\nScripts=Data\\Scripts.rvdata2\nTitle=Ace Quest\n");
        ace.put("Game.rgss3a", "");
        RgssGame.Info info = RgssGame.detect(files(ace));
        assertEquals("Game", info.exec());
        assertEquals("VX Ace", info.engine());
        assertEquals("Ace Quest", info.title());

        Map<String, String> xp = new LinkedHashMap<>();
        xp.put("Game.ini", "[Game]\nLibrary=RGSS104E.dll\nScripts=Data\\Scripts.rxdata\nTitle=\n");
        xp.put("Data/scripts.rxdata", "");
        info = RgssGame.detect(files(xp));
        assertEquals(1, info.version());
        assertEquals("RPG Maker", info.title());
    }

    @Test public void ignoresMvAndIncompleteFolders() throws Exception {
        assertNull(RgssGame.detect(files(Map.of("index.html", "", "js/rpg_core.js", ""))));
        assertNull(RgssGame.detect(files(Map.of("Game.ini", "[Game]\nLibrary=RGSS104E.dll\nScripts=Data\\Scripts.rxdata\n"))));
    }

    @Test public void translatesMkxpConf() throws Exception {
        Map<String, String> conf = RgssGame.mkxpConf("fullscreen=true\n# comment\nRTP=lang.dat\nfontSub=Arial>Open Sans\nfontSub=New Times Roman>Open Sans\nmidi.chorus=true\nfixedFramerate=60\nexecName=To the Moon\n");
        JSONObject json = RgssGame.confToJson(conf);
        assertTrue(json.getBoolean("fullscreen"));
        assertEquals(new JSONArray().put("lang.dat").toString(), json.getJSONArray("RTP").toString());
        assertEquals(2, json.getJSONArray("fontSub").length());
        assertTrue(json.getBoolean("midiChorus"));
        assertEquals(60, json.getInt("fixedFramerate"));
        assertEquals("To the Moon", json.getString("execName"));
    }

    @Test public void buildsConfigWithPadPortPreloadFirst() throws Exception {
        RgssGame.Info info = new RgssGame.Info("To the Moon", "To the Moon", 1, "To the Moon.ini");
        Map<String, String> conf = RgssGame.mkxpConf("preloadScript=preload/ruby18_comp.rb\npreloadScript=preload/win32_wrap.rb\nfullscreen=false\ngameFolder=/elsewhere\n");
        JSONObject json5 = RgssGame.json5("{\n // comment\n \"smoothScaling\": true, /* block */ \"SESourceCount\": 8,\n}");
        JSONObject config = RgssGame.config(info, "/data/game", "/data/padport_rgss.rb", json5, conf);
        JSONArray preload = config.getJSONArray("preloadScript");
        assertEquals("/data/padport_rgss.rb", preload.getString(0));
        assertEquals("preload/win32_wrap.rb", preload.getString(2));
        assertEquals("/data/game", config.getString("gameFolder"));
        assertTrue(config.getBoolean("fullscreen"));
        assertTrue(config.getBoolean("smoothScaling"));
        assertEquals(8, config.getInt("SESourceCount"));
        assertEquals("To the Moon", config.getString("execName"));
        assertEquals(1, config.getInt("rgssVersion"));
    }

    @Test public void addsPreloadFolderScriptsOnlyWhenConfDoesNotListThem() {
        Map<String, String> conf = RgssGame.mkxpConf("execName=To the Moon\nRTP=lang.dat\n");
        Map<String, String> result = RgssGame.withDefaultPreloads(conf, List.of("preload/win32_wrap.rb", "preload/ruby18_comp.rb"));
        assertEquals("preload/ruby18_comp.rb\npreload/win32_wrap.rb", result.get("preloadScript"));
        Map<String, String> explicit = RgssGame.mkxpConf("preloadScript=mine.rb\n");
        assertSame(explicit, RgssGame.withDefaultPreloads(explicit, List.of("preload/other.rb")));
        assertNull(RgssGame.withDefaultPreloads(null, List.of("preload/other.rb")));
    }

    @Test public void decodesIniTextEncodings() {
        assertEquals("ゲーム", RgssGame.ini("[Game]\nTitle=ゲーム".getBytes(java.nio.charset.Charset.forName("Shift_JIS")), "Game").get("title"));
        byte[] bom = {(byte)0xef, (byte)0xbb, (byte)0xbf, 'a'};
        assertEquals("a", RgssGame.text(bom));
    }

    private static byte[] archiveV1(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RGSSAD\0".getBytes(StandardCharsets.US_ASCII));
        out.write(1);
        int key = 0xDEADCAFE;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            out.write(le(name.length ^ key)); key = key * 7 + 3;
            for (byte b : name) { out.write(b ^ (byte)key); key = key * 7 + 3; }
            out.write(le(e.getValue().length ^ key)); key = key * 7 + 3;
            byte[] data = e.getValue().clone();
            int fileKey = key;
            for (int i = 0; i < data.length; i += 4) {
                for (int j = 0; j < 4 && i + j < data.length; j++) data[i + j] ^= (byte)(fileKey >>> (8 * j));
                fileKey = fileKey * 7 + 3;
            }
            out.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] le(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }

    @Test public void readsRgssadV1Archive() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("Data\\Scripts.rxdata", new byte[]{1, 2, 3, 4, 5, 6, 7});
        entries.put("Graphics\\Titles\\Title.png", "PNGDATA!!".getBytes(StandardCharsets.US_ASCII));
        byte[] archive = archiveV1(entries);
        RgssGame.Reader reader = RgssGame.memory(archive);
        List<RgssGame.ArchiveEntry> list = RgssGame.archiveEntries(Arrays.copyOf(archive, 8), reader);
        assertEquals(2, list.size());
        assertEquals("Graphics/Titles/Title.png", list.get(1).name());
        assertArrayEquals("PNGDATA!!".getBytes(StandardCharsets.US_ASCII), RgssGame.archiveData(list.get(1), reader));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7}, RgssGame.archiveData(list.get(0), reader));
        assertEquals(List.of("Graphics/Titles/Title.png"), RgssGame.titleImages(List.of("Graphics/Titles/Title.png", "Graphics/Pictures/x.png")));
    }

    @Test public void mapsPadToRgssKeys() {
        float[] buttons = new float[17], axes = new float[4];
        buttons[0] = 1; buttons[7] = .8f; axes[0] = -.9f; axes[1] = .2f;
        Set<Integer> keys = RgssKeys.pressed(new RgssKeys.State() {
            public float button(int index) { return buttons[index]; }
            public float axis(int index) { return axes[index]; }
        });
        assertEquals(new TreeSet<>(List.of(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_LEFT)), keys);
        buttons[0] = 0; buttons[7] = .3f; axes[0] = 0; buttons[9] = 1;
        keys = RgssKeys.pressed(new RgssKeys.State() {
            public float button(int index) { return buttons[index]; }
            public float axis(int index) { return axes[index]; }
        });
        assertEquals(Set.of(KeyEvent.KEYCODE_ESCAPE), keys);
    }

    @Test public void skipsWindowsBinaries() {
        assertTrue(RgssRuntime.skipped("To the Moon.exe"));
        assertTrue(RgssRuntime.skipped("System/RGSS301.dll"));
        assertTrue(RgssRuntime.skipped(".DS_Store"));
        assertFalse(RgssRuntime.skipped("Audio/BGM/theme.ogg"));
    }
}
