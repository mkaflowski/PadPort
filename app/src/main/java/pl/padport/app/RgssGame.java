package pl.padport.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.*;
import java.util.*;

/**
 * RPG Maker XP / VX / VX Ace (RGSS1-3) game detection and mkxp-z configuration.
 * Pure logic: no Android APIs, so it is covered by JVM unit tests.
 */
final class RgssGame {
    interface Files {
        /** Case-preserving relative paths of all game files. */
        Collection<String> paths();
        /** Reads a small file (ini, conf). */
        byte[] read(String path) throws IOException;
    }

    record Info(String exec, String title, int version, String ini) {
        String engine() { return version == 3 ? "VX Ace" : version == 2 ? "VX" : "XP"; }
    }

    static final String[] ARCHIVES = {".rgssad", ".rgss2a", ".rgss3a"};

    /** Returns null if the folder is not an RGSS game. */
    static Info detect(Files files) throws IOException {
        Map<String, String> top = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String path : files.paths()) if (!path.contains("/")) top.put(path, path);
        Map<String, String> conf = top.containsKey("mkxp.conf") ? mkxpConf(text(files.read(top.get("mkxp.conf")))) : Map.of();
        List<String> order = new ArrayList<>();
        String configured = conf.get("execName");
        if (configured != null && !configured.isBlank()) order.add(configured.trim());
        for (String name : top.values()) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String ext : ARCHIVES) if (lower.endsWith(ext)) order.add(name.substring(0, name.length() - ext.length()));
        }
        order.add("Game");
        for (String name : top.values()) if (name.toLowerCase(Locale.ROOT).endsWith(".ini")) order.add(name.substring(0, name.length() - 4));
        for (String exec : new LinkedHashSet<>(order)) {
            String ini = top.get(exec + ".ini");
            if (ini == null) continue;
            Map<String, String> game = ini(files.read(ini), "Game");
            int version = version(game.get("Library"), game.get("Scripts"), top, exec);
            if (version == 0) continue;
            boolean archive = false;
            for (String ext : ARCHIVES) archive |= top.containsKey(exec + ext);
            String scripts = game.getOrDefault("Scripts", "").replace('\\', '/');
            boolean loose = !scripts.isEmpty() && contains(files.paths(), scripts);
            if (!archive && !loose) continue;
            String title = game.getOrDefault("Title", "").trim();
            if (title.isEmpty() || title.equalsIgnoreCase("Untitled")) title = exec.equalsIgnoreCase("Game") ? "RPG Maker" : exec;
            return new Info(exec, title, version, ini);
        }
        return null;
    }

    private static boolean contains(Collection<String> paths, String wanted) {
        for (String path : paths) if (path.equalsIgnoreCase(wanted)) return true;
        return false;
    }

    static int version(String library, String scripts, Map<String, String> top, String exec) {
        String lib = library == null ? "" : library.toUpperCase(Locale.ROOT);
        if (lib.startsWith("RGSS3")) return 3;
        if (lib.startsWith("RGSS2")) return 2;
        if (lib.startsWith("RGSS1") || lib.startsWith("RGSS10")) return 1;
        String s = scripts == null ? "" : scripts.toLowerCase(Locale.ROOT);
        if (s.endsWith(".rvdata2")) return 3;
        if (s.endsWith(".rvdata")) return 2;
        if (s.endsWith(".rxdata")) return 1;
        if (top.containsKey(exec + ".rgss3a")) return 3;
        if (top.containsKey(exec + ".rgss2a")) return 2;
        if (top.containsKey(exec + ".rgssad")) return 1;
        return 0;
    }

    /** Game.ini style file, values of one section (keys case-insensitive). */
    static Map<String, String> ini(byte[] bytes, String section) {
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean inside = false;
        for (String raw : text(bytes).split("\r?\n|\r")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) { inside = line.substring(1, line.length() - 1).trim().equalsIgnoreCase(section); continue; }
            int eq = line.indexOf('=');
            if (inside && eq > 0) values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return values;
    }

    /** Decodes UTF-8 (with BOM), otherwise Shift_JIS for Japanese games, otherwise Windows-1252. */
    static String text(byte[] bytes) {
        int start = bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, start, bytes.length - start);
        for (String name : new String[]{"UTF-8", "Shift_JIS", "windows-1252"}) {
            try {
                return Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer.duplicate()).toString();
            } catch (CharacterCodingException | UnsupportedCharsetException ignored) { /* next */ }
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.ISO_8859_1);
    }

    // ---- mkxp (Ancurio) mkxp.conf -> mkxp-z mkxp.json -------------------------

    static final Set<String> ARRAY_KEYS = Set.of("RTP", "fontSub", "preloadScript", "rubyLoadpath");
    private static final Map<String, String> RENAMED = Map.of(
        "midi.soundFont", "midiSoundFont", "midi.chorus", "midiChorus", "midi.reverb", "midiReverb",
        "SE.sourceCount", "SESourceCount");

    /** Last value of every single key; array keys are joined with '\n'. */
    static Map<String, String> mkxpConf(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : text.split("\r?\n|\r")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
            key = RENAMED.getOrDefault(key, key);
            if (ARRAY_KEYS.contains(key) && values.containsKey(key)) values.put(key, values.get(key) + "\n" + value);
            else values.put(key, value);
        }
        return values;
    }

    /**
     * mkxp builds shipped with commercial ports (e.g. Freebird Games) load every
     * preload/*.rb script although mkxp.conf does not list them.
     */
    static Map<String, String> withDefaultPreloads(Map<String, String> conf, Collection<String> preloadFolderScripts) {
        if (conf == null || conf.containsKey("preloadScript") || preloadFolderScripts.isEmpty()) return conf;
        List<String> scripts = new ArrayList<>(preloadFolderScripts);
        scripts.sort(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> result = new LinkedHashMap<>(conf);
        result.put("preloadScript", String.join("\n", scripts));
        return result;
    }

    static JSONObject confToJson(Map<String, String> conf) throws Exception {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> e : conf.entrySet()) {
            String key = e.getKey(), value = e.getValue();
            if (ARRAY_KEYS.contains(key)) {
                JSONArray list = new JSONArray();
                for (String item : value.split("\n")) if (!item.isBlank()) list.put(item.trim());
                json.put(key, list);
            } else if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) json.put(key, Boolean.parseBoolean(value.toLowerCase(Locale.ROOT)));
            else if (value.matches("-?\\d{1,9}")) json.put(key, Integer.parseInt(value));
            else json.put(key, value);
        }
        return json;
    }

    /** mkxp-z JSON5 (comments, trailing commas) -> JSONObject; null when unreadable. */
    static JSONObject json5(String text) {
        StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && next != 0) { out.append(next); i++; }
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') { quote = c; out.append(c); }
            else if (c == '/' && next == '/') { while (i < text.length() && text.charAt(i) != '\n') i++; out.append('\n'); }
            else if (c == '/' && next == '*') { int end = text.indexOf("*/", i + 2); i = end < 0 ? text.length() : end + 1; }
            else out.append(c);
        }
        try { return new JSONObject(out.toString().replaceAll(",(\\s*[}\\]])", "$1")); }
        catch (Exception e) { return null; }
    }

    /**
     * Final mkxp-z configuration. Game-provided mkxp.conf / mkxp.json values are kept,
     * PadPort only sets the paths and prepends its compatibility preload script.
     */
    static JSONObject config(Info info, String gameFolder, String padportPreload, JSONObject gameJson, Map<String, String> gameConf) throws Exception {
        JSONObject config = new JSONObject();
        config.put("rgssVersion", info.version());
        config.put("fixedAspectRatio", true);
        config.put("pathCache", true);
        config.put("enableSettings", false);
        if (gameConf != null) merge(config, confToJson(gameConf));
        if (gameJson != null) merge(config, gameJson);
        config.put("gameFolder", gameFolder);
        config.put("execName", info.exec());
        config.put("fullscreen", true);
        config.put("anyAltToggleFS", false);
        config.put("winResizable", true);
        config.put("customScript", config.optString("customScript", ""));
        JSONArray preload = new JSONArray().put(padportPreload);
        JSONArray existing = config.optJSONArray("preloadScript");
        if (existing != null) for (int i = 0; i < existing.length(); i++) {
            String script = existing.optString(i, "");
            if (!script.isEmpty() && !script.equals(padportPreload)) preload.put(script);
        }
        config.put("preloadScript", preload);
        return config;
    }

    private static void merge(JSONObject target, JSONObject source) throws Exception {
        for (Iterator<String> it = source.keys(); it.hasNext(); ) {
            String key = it.next();
            target.put(key, source.get(key));
        }
    }

    // ---- RGSSAD archives (read-only listing, used for card artwork) ------------

    record ArchiveEntry(String name, long offset, int size, int key) {}

    /** Lists an RGSSAD v1 (XP/VX) or v3 (VX Ace) archive. */
    static List<ArchiveEntry> archiveEntries(byte[] header, Reader reader) throws IOException {
        if (header.length < 8 || !new String(header, 0, 6, StandardCharsets.US_ASCII).equals("RGSSAD") || header[6] != 0)
            throw new IOException("Not an RGSS archive");
        List<ArchiveEntry> entries = new ArrayList<>();
        int version = header[7];
        if (version == 1) {
            long position = 8;
            int key = 0xDEADCAFE;
            while (position + 4 <= reader.length()) {
                int nameLength = reader.int32(position) ^ key; key = key * 7 + 3; position += 4;
                if (nameLength <= 0 || nameLength > 4096) break;
                byte[] name = reader.bytes(position, nameLength);
                for (int i = 0; i < name.length; i++) { name[i] ^= (byte)key; key = key * 7 + 3; }
                position += nameLength;
                int size = reader.int32(position) ^ key; key = key * 7 + 3; position += 4;
                if (size < 0 || position + size > reader.length()) break;
                entries.add(new ArchiveEntry(new String(name, StandardCharsets.UTF_8).replace('\\', '/'), position, size, key));
                position += size;
            }
        } else if (version == 3) {
            long position = 8;
            int key = reader.int32(position) * 9 + 3; position += 4;
            while (position + 16 <= reader.length()) {
                int offset = reader.int32(position) ^ key;
                int size = reader.int32(position + 4) ^ key;
                int fileKey = reader.int32(position + 8) ^ key;
                int nameLength = reader.int32(position + 12) ^ key;
                position += 16;
                if (offset == 0) break;
                if (nameLength <= 0 || nameLength > 4096) break;
                byte[] name = reader.bytes(position, nameLength);
                for (int i = 0; i < name.length; i++) name[i] ^= (byte)(key >>> (8 * (i % 4)));
                position += nameLength;
                entries.add(new ArchiveEntry(new String(name, StandardCharsets.UTF_8).replace('\\', '/'), offset & 0xffffffffL, size, fileKey));
            }
        } else throw new IOException("Unsupported RGSS archive version " + version);
        return entries;
    }

    static byte[] archiveData(ArchiveEntry entry, Reader reader) throws IOException {
        byte[] data = reader.bytes(entry.offset(), entry.size());
        int key = entry.key();
        for (int i = 0; i < data.length; i += 4) {
            for (int j = 0; j < 4 && i + j < data.length; j++) data[i + j] ^= (byte)(key >>> (8 * j));
            key = key * 7 + 3;
        }
        return data;
    }

    /** Random access view of an archive. */
    interface Reader {
        long length();
        byte[] bytes(long position, int length) throws IOException;
        default int int32(long position) throws IOException {
            return ByteBuffer.wrap(bytes(position, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
    }

    static Reader memory(byte[] data) {
        return new Reader() {
            public long length() { return data.length; }
            public byte[] bytes(long position, int length) throws IOException {
                if (position < 0 || position + length > data.length) throw new IOException("Truncated RGSS archive");
                return Arrays.copyOfRange(data, (int)position, (int)position + length);
            }
        };
    }

    static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > limit) throw new IOException("File too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Candidate title-screen images, loose files first, then archive entries. */
    static List<String> titleImages(Collection<String> names) {
        List<String> result = new ArrayList<>();
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT).replace('\\', '/');
            if (lower.startsWith("graphics/titles/") && lower.matches(".*\\.(png|jpg|jpeg|bmp)$")) result.add(name);
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }
}
