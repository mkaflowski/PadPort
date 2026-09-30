package pl.padport.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback card artwork from the public Steam store when the game files have no
 * usable title image. The Steam app id comes from steam_appid.txt shipped with
 * Steam copies of games; otherwise the store search is used and only an exact
 * title match is accepted. Nothing is sent except the app id / game title.
 */
final class SteamArtwork {
    private static final int MAX_BYTES = 16 * 1024 * 1024, TIMEOUT_MS = 8000;
    private static final long RETRY_MS = 10 * 60 * 1000;
    private static final Map<String, Long> attempts = new ConcurrentHashMap<>();

    /** Definitive "Steam does not know this game" (as opposed to being offline). */
    static final class NotFound extends IOException { NotFound(String m) { super(m); } }

    static String appIdFromFile(String content) {
        String digits = content == null ? "" : content.trim();
        return digits.matches("\\d{1,10}") ? digits : null;
    }

    static String normalize(String title) {
        // Trademark signs go first: NFKD would turn "™" into the letters "TM".
        String plain = (title == null ? "" : title).replaceAll("[™®©℠]", "");
        plain = Normalizer.normalize(plain, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        return plain.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** First search result with the same normalised title, or null. */
    static String pickApp(JSONObject search, String title) {
        JSONArray items = search.optJSONArray("items");
        String wanted = normalize(title);
        if (items == null || wanted.isEmpty()) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "app".equals(item.optString("type", "app")) && normalize(item.optString("name")).equals(wanted))
                return String.valueOf(item.optLong("id"));
        }
        return null;
    }

    /** Local steam_appid.txt next to the game (Steam copies of RPG Maker games). */
    static String localAppId(GameSource source) {
        for (Map.Entry<String, GameSource.Entry> e : source.entries.entrySet()) {
            String path = e.getKey();
            if (!path.equalsIgnoreCase("steam_appid.txt") && !path.equalsIgnoreCase(source.prefix + "steam_appid.txt")) continue;
            // Read by document id: for MV/MZ the file sits next to www/, outside source.prefix.
            android.net.Uri uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(source.tree, e.getValue().documentId());
            try (InputStream in = source.context.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                return appIdFromFile(new String(GameSource.readBounded(in, 64), StandardCharsets.US_ASCII));
            } catch (IOException | SecurityException ignored) { return null; }
        }
        return null;
    }

    /** Downloads a wide image for the card; throws NotFound when Steam has none. */
    static byte[] download(GameSource source) throws IOException {
        Long last = attempts.get(source.id);
        if (last != null && System.currentTimeMillis() - last < RETRY_MS) throw new IOException("Steam artwork recently attempted");
        attempts.put(source.id, System.currentTimeMillis());
        String app = localAppId(source);
        if (app == null) {
            String query = URLEncoder.encode(source.title, "UTF-8");
            JSONObject search = json("https://store.steampowered.com/api/storesearch/?l=english&cc=US&term=" + query);
            app = pickApp(search, source.title);
            if (app == null) throw new NotFound("No Steam game named " + source.title);
        }
        String base = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" + app + "/";
        for (String name : new String[]{"library_hero.jpg", "capsule_616x353.jpg", "header.jpg"}) {
            try { return get(base + name); } catch (FileNotFoundException ignored) { /* next size */ }
        }
        // Newer apps use hashed file names; the store API lists the header image.
        JSONObject details = json("https://store.steampowered.com/api/appdetails?filters=basic&appids=" + app).optJSONObject(app);
        JSONObject data = details == null ? null : details.optJSONObject("data");
        String header = data == null ? "" : data.optString("header_image", "");
        if (header.startsWith("https://")) return get(header);
        throw new NotFound("No Steam artwork for app " + app);
    }

    private static JSONObject json(String url) throws IOException {
        try { return new JSONObject(new String(get(url), StandardCharsets.UTF_8)); }
        catch (org.json.JSONException e) { throw new IOException("Invalid Steam response", e); }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "PadPort");
        try {
            int status = connection.getResponseCode();
            if (status == 404) throw new FileNotFoundException(url);
            if (status != 200) throw new IOException("HTTP " + status + " " + url);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > MAX_BYTES) throw new IOException("Steam image too large");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally { connection.disconnect(); }
    }
}
