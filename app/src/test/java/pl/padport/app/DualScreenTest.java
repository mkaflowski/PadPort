package pl.padport.app;

import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class DualScreenTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temp=new org.junit.rules.TemporaryFolder();
    private JSONObject game() throws Exception{return new JSONObject().put("id","look").put("title","Look Outside").put("engine","MZ");}
    @Test public void onlyKnownGamesOnTwoDisplaysAreOfferedAtFirstAddition() throws Exception{
        JSONObject game=game();
        assertTrue(DualScreenProfile.shouldOffer(game,false,true));
        assertFalse(DualScreenProfile.shouldOffer(game,true,true));assertFalse(DualScreenProfile.shouldOffer(game,false,false));
        assertFalse(DualScreenProfile.enabled(game));
        game.put("title","Other game");assertFalse(DualScreenProfile.shouldOffer(game,false,true));
        game.put("title","Look Outside").put("engine","MV");assertFalse(DualScreenProfile.supports(game));
    }
    @Test public void toTheMoonUsesTheXpAdapterAndCanBeOfferedAfterRgssConsent() throws Exception{
        JSONObject moon=new JSONObject().put("id","moon").put("title","To the Moon").put("engine","XP").put("exec","To the Moon");
        assertTrue(DualScreenProfile.supports(moon));assertTrue(DualScreenProfile.shouldOffer(moon,false,true));
        assertFalse(DualScreenProfile.shouldOffer(moon,true,true));
        assertEquals(DualScreenProfile.TO_THE_MOON,DualScreenProfile.identify("To the Moon","XP"));
        assertEquals("",DualScreenProfile.identify("To the Moon","MZ"));
    }
    @Test public void welcomeToElderfieldHasItsOwnPanelOnMzOnly() throws Exception{
        assertEquals(DualScreenProfile.ELDERFIELD,DualScreenProfile.identify("Welcome to Elderfield","MZ"));
        assertEquals("",DualScreenProfile.identify("Welcome to Elderfield","MV"));
        assertEquals("elderfield-panel",DualScreenProfile.panel(DualScreenProfile.ELDERFIELD));
        JSONObject game=new JSONObject().put("id","ef").put("title","Welcome to Elderfield").put("engine","MZ");
        assertTrue(DualScreenProfile.supports(game));assertTrue(DualScreenProfile.shouldOffer(game,false,true));
    }
    @Test public void fearAndHungerGetsItsOwnReadOnlyPanelAndNoOtherAdapter() throws Exception{
        assertEquals(DualScreenProfile.FEAR_AND_HUNGER,DualScreenProfile.identify(" Fear & Hunger ","MV"));
        assertEquals("",DualScreenProfile.identify("Fear & Hunger","MZ"));
        assertEquals("",DualScreenProfile.identify("Fear & Hunger 2: Termina","MV"));
        assertEquals("fear-and-hunger-panel",DualScreenProfile.panel(DualScreenProfile.FEAR_AND_HUNGER));
        assertEquals("look-outside-panel",DualScreenProfile.panel(DualScreenProfile.LOOK_OUTSIDE));
        assertEquals("to-the-moon-panel",DualScreenProfile.panel(DualScreenProfile.TO_THE_MOON));
        JSONObject game=new JSONObject().put("id","fh").put("title","Fear & Hunger").put("engine","MV");
        assertTrue(DualScreenProfile.supports(game));assertFalse(DualScreenProfile.enabled(game));
        assertTrue(DualScreenProfile.enabled(game.put("dualScreen",true)));
    }
    @Test public void preferenceCanBeReloadedWithoutSharedPreferencesCache() throws Exception{
        java.io.File file=new java.io.File(temp.getRoot(),"setting");
        assertNull(DualScreenPreference.read(file));
        DualScreenPreference.write(file,true);assertEquals(Boolean.TRUE,DualScreenPreference.read(new java.io.File(file.getPath())));
        DualScreenPreference.write(file,false);assertEquals(Boolean.FALSE,DualScreenPreference.read(new java.io.File(file.getPath())));
        assertFalse(new java.io.File(file+".tmp").exists());
    }
    @Test public void rubyStateFromPreviousProcessOrDisplayIsRejected() throws Exception{
        DualScreenChannel channel=new DualScreenChannel();channel.active(true);
        long epoch=new JSONObject(channel.poll()).getLong("epoch");
        JSONObject state=new JSONObject().put("epoch",epoch).put("session","new-run");
        assertTrue(RgssCompanionBridge.accepts(state,"new-run",channel));
        assertFalse(RgssCompanionBridge.accepts(state,"old-run",channel));
        channel.active(false);channel.active(true);assertFalse(RgssCompanionBridge.accepts(state,"new-run",channel));
    }
    @Test public void fileBridgePublishesControlAcceptsRubyStateAndStopsCleanly() throws Exception{
        java.io.File dir=temp.newFolder("companion");DualScreenChannel channel=new DualScreenChannel();channel.active(true);
        java.util.concurrent.CountDownLatch received=new java.util.concurrent.CountDownLatch(1);
        try(RgssCompanionBridge bridge=new RgssCompanionBridge(dir,channel,json->received.countDown())){
            JSONObject control=new JSONObject(new String(java.nio.file.Files.readAllBytes(new java.io.File(dir,"control.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(control.getBoolean("active"));assertTrue(control.getLong("updated")>0);
            JSONObject state=new JSONObject().put("session",control.getString("session")).put("epoch",control.getLong("epoch")).put("mode","notebook");
            RgssRuntime.writeAtomic(new java.io.File(dir,"state.json"),state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(received.await(3,java.util.concurrent.TimeUnit.SECONDS));
        }
        JSONObject stopped=new JSONObject(new String(java.nio.file.Files.readAllBytes(new java.io.File(dir,"control.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(stopped.getBoolean("active"));assertFalse(new java.io.File(dir,"control.json.tmp").exists());
    }
    @Test public void refreshPreservesPerGameOptInAndDoesNotEnableOtherGames() throws Exception{
        JSONObject old=game().put("dualScreen",true).put("lastPlayed",123);
        List<JSONObject> games=new ArrayList<>(List.of(old,new JSONObject().put("id","other").put("title","Other").put("engine","MZ")));
        Library.replaceRecord(games,game());
        assertTrue(DualScreenProfile.enabled(games.get(0)));assertEquals(123,games.get(0).getLong("lastPlayed"));
        assertFalse(DualScreenProfile.enabled(games.get(1)));
        games.get(0).put("dualScreen",false);Library.replaceRecord(games,game());assertFalse(DualScreenProfile.enabled(games.get(0)));
    }
    @Test public void displayIdsAreNotAssumedAndUnavailablePrivateDisplaysAreIgnored(){
        List<SecondaryDisplays.Candidate> displays=List.of(
            new SecondaryDisplays.Candidate(7,true,true,false,false),new SecondaryDisplays.Candidate(0,true,true,false,false),
            new SecondaryDisplays.Candidate(2,true,true,true,true),new SecondaryDisplays.Candidate(3,true,false,false,true));
        assertEquals(7,SecondaryDisplays.choose(displays,0));assertEquals(0,SecondaryDisplays.choose(displays,7));
        assertEquals(-1,SecondaryDisplays.choose(List.of(displays.get(1),displays.get(2),displays.get(3)),0));
    }
    @Test public void hidingDisplayDropsQueuedCommandsAndRejectsStaleSnapshot() throws Exception{
        DualScreenChannel channel=new DualScreenChannel();channel.active(true);
        long epoch=new JSONObject(channel.poll()).getLong("epoch");
        JSONObject c=new JSONObject().put("epoch",epoch).put("action","use").put("scene",1);
        channel.enqueue(c.toString());assertTrue(channel.accepts(c));
        channel.active(false);channel.active(true);
        assertFalse(channel.accepts(c));channel.enqueue(c.toString());
        assertEquals(0,new JSONObject(channel.poll()).getJSONArray("commands").length());
    }
    @Test public void queueIsBoundedAndEachInputIsConsumedOnlyOnce() throws Exception{
        DualScreenChannel channel=new DualScreenChannel();channel.active(true);
        long epoch=new JSONObject(channel.poll()).getLong("epoch");
        String command=new JSONObject().put("epoch",epoch).put("action","inventory").toString();
        for(int i=0;i<100;i++)channel.enqueue(command);
        assertEquals(16,new JSONObject(channel.poll()).getJSONArray("commands").length());
        assertEquals(0,new JSONObject(channel.poll()).getJSONArray("commands").length());
        channel.enqueue("broken");channel.enqueue(new JSONObject().put("epoch",epoch).put("action","eval").toString());
        assertEquals(0,new JSONObject(channel.poll()).getJSONArray("commands").length());
    }
}
