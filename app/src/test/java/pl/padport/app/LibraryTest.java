package pl.padport.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class LibraryTest {
    private static JSONObject game(String id,long time) throws Exception{return new JSONObject().put("id",id).put("title",id).put("lastPlayed",time);}
    private static List<String> ids(List<JSONObject> games) throws Exception{List<String> ids=new ArrayList<>();for(JSONObject g:games)ids.add(g.getString("id"));return ids;}

    @Test public void newestGameIsFirstAndNeverPlayedGamesStayAtTheEnd() throws Exception{
        List<JSONObject> games=new ArrayList<>(List.of(new JSONObject().put("id","legacy"),game("older",100),game("newer",500),game("unplayed",0)));
        Library.sortByLastPlayed(games);
        assertEquals(List.of("newer","older","legacy","unplayed"),ids(games));
    }
    @Test public void launchingExistingGameUpdatesAndPersistsItsPosition() throws Exception{
        List<JSONObject> games=new ArrayList<>(List.of(game("a",100),game("b",200)));
        assertTrue(Library.markPlayed(games,"a",300));
        JSONArray saved=new JSONArray(games.toString());
        List<JSONObject> restored=new ArrayList<>();for(int i=0;i<saved.length();i++)restored.add(saved.getJSONObject(i));
        Library.sortByLastPlayed(restored);
        assertEquals(List.of("a","b"),ids(restored));assertEquals(300,restored.get(0).getLong("lastPlayed"));
    }
    @Test public void refreshingMetadataPreservesHistoryButUpdatesGameDetails() throws Exception{
        List<JSONObject> games=new ArrayList<>(List.of(game("a",300).put("engine","MV"),game("b",200)));
        JSONObject fresh=new JSONObject().put("id","a").put("title","Updated title").put("engine","MZ");
        Library.replaceRecord(games,fresh);
        assertEquals(List.of("a","b"),ids(games));assertEquals(300,games.get(0).getLong("lastPlayed"));
        assertEquals("Updated title",games.get(0).getString("title"));assertEquals("MZ",games.get(0).getString("engine"));
        assertFalse(fresh.has("lastPlayed"));
    }
    @Test public void refreshDoesNotShuffleUnplayedGames() throws Exception{
        List<JSONObject> games=new ArrayList<>(List.of(game("a",0),game("b",0)));
        Library.replaceRecord(games,new JSONObject().put("id","a").put("title","Refreshed"));
        assertEquals(List.of("a","b"),ids(games));
        Library.replaceRecord(games,new JSONObject().put("id","c"));
        assertEquals(List.of("a","b","c"),ids(games));
    }
    @Test public void unknownLaunchDoesNotCreateAnEntryAndTiesRemainStable() throws Exception{
        List<JSONObject> games=new ArrayList<>(List.of(game("a",200),game("b",200)));
        assertFalse(Library.markPlayed(games,"missing",500));Library.sortByLastPlayed(games);
        assertEquals(List.of("a","b"),ids(games));
    }
}
