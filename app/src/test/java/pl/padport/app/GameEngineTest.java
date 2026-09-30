package pl.padport.app;

import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class GameEngineTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temp=new org.junit.rules.TemporaryFolder();

    @Test public void geckoViewIsTheDefaultAndOnlyAnExplicitWebViewChoiceSwitchesIt(){
        assertEquals(GameEngine.GECKO,GameEngine.parse(null));
        assertEquals(GameEngine.GECKO,GameEngine.parse(""));
        assertEquals(GameEngine.GECKO,GameEngine.parse("gecko"));
        assertEquals(GameEngine.GECKO,GameEngine.parse("something else"));
        assertEquals(GameEngine.WEBVIEW,GameEngine.parse("webview"));
        assertEquals(GameEngine.WEBVIEW,GameEngine.parse(" webview\n"));
    }
    @Test public void missingFileMeansDefaultAndChoiceSurvivesReload() throws Exception{
        File file=new File(temp.getRoot(),"engine-0123456789abcdef01234567.txt");
        assertEquals(GameEngine.GECKO,GameEngine.read(file));
        GameEngine.write(file,GameEngine.WEBVIEW);
        assertEquals(GameEngine.WEBVIEW,GameEngine.read(new File(file.getPath())));
        GameEngine.write(file,GameEngine.GECKO);
        assertEquals(GameEngine.GECKO,GameEngine.read(new File(file.getPath())));
        assertEquals("gecko",new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        assertFalse(new File(file+".tmp").exists());
    }
    @Test public void unknownValuesAreNeverWrittenToTheFile() throws Exception{
        File file=new File(temp.getRoot(),"engine");
        GameEngine.write(file,"firefox");
        assertEquals("gecko",new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void onlyMvMzGamesUseAWebEngine() throws Exception{
        JSONObject mz=new JSONObject().put("id","0123456789abcdef01234567").put("title","Look Outside").put("engine","MZ");
        JSONObject xp=new JSONObject().put("id","0123456789abcdef01234568").put("title","To the Moon").put("engine","XP").put("exec","To the Moon");
        assertTrue(GameEngine.isWebGame(mz));
        assertFalse(GameEngine.isWebGame(xp));
        assertFalse(GameEngine.isWebGame(null));
        // RGSS never asks the engine setting (no Context needed).
        assertEquals(RgssActivity.class,GameEngine.player(null,xp));
    }
}
