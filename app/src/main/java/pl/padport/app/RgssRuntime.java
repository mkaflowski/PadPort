package pl.padport.app;

import android.content.Context;
import android.os.StatFs;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * mkxp-z needs POSIX paths, SAF only offers streams: the original folder is mirrored
 * (read-only on the source side) into private app storage. Saves live next to it and
 * are never removed by a refresh.
 */
final class RgssRuntime {
    interface Progress { void update(long done, long total); }

    private static final Set<String> SKIPPED = Set.of(".ds_store", "thumbs.db", "desktop.ini");
    final File base, game, saves;

    RgssRuntime(Context context, String id) {
        if (!id.matches("[0-9a-f]{24}")) throw new IllegalArgumentException("Invalid game identifier");
        base = new File(context.getFilesDir(), "rgss/" + id);
        game = new File(base, "game");
        saves = new File(base, "saves");
    }

    static boolean skipped(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return SKIPPED.contains(name) || name.endsWith(".exe") || name.endsWith(".dll");
    }

    private File manifestFile() { return new File(base, "files.json"); }

    private Map<String, Long> manifest() {
        Map<String, Long> files = new HashMap<>();
        try (InputStream in = new FileInputStream(manifestFile())) {
            JSONObject json = new JSONObject(new String(RgssGame.readAll(in, 64 << 20), StandardCharsets.UTF_8));
            for (Iterator<String> it = json.keys(); it.hasNext(); ) { String k = it.next(); files.put(k, json.getLong(k)); }
        } catch (Exception ignored) { /* first launch */ }
        return files;
    }

    private void saveManifest(Map<String, Long> files) throws Exception {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, Long> e : files.entrySet()) json.put(e.getKey(), e.getValue());
        writeAtomic(manifestFile(), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    static File inside(File root, String relative) throws IOException {
        File target = new File(root, relative);
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(rootPath)) throw new IOException("Unsafe path: " + relative);
        return target;
    }

    /** Copies new or changed files, removes files that disappeared from the source. */
    void sync(Context context, GameSource source, Progress progress) throws Exception {
        if (!game.isDirectory() && !game.mkdirs()) throw new IOException(context.getString(R.string.rgss_storage_error));
        if (!saves.isDirectory() && !saves.mkdirs()) throw new IOException(context.getString(R.string.rgss_storage_error));
        Map<String, Long> old = manifest(), current = new HashMap<>();
        List<String> copy = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, GameSource.Entry> e : source.entries.entrySet()) {
            String path = e.getKey();
            if (skipped(path)) continue;
            long size = e.getValue().size();
            current.put(path, size);
            File target = inside(game, path);
            Long known = old.get(path);
            if (known == null || known != size || !target.isFile() || (size >= 0 && target.length() != size)) {
                copy.add(path);
                total += Math.max(0, size);
            }
        }
        long free = new StatFs(base.getPath()).getAvailableBytes();
        if (total > free - (64L << 20)) throw new IOException(context.getString(R.string.rgss_space_error, total >> 20, free >> 20));
        long done = 0;
        progress.update(0, total);
        byte[] buffer = new byte[1 << 16];
        for (String path : copy) {
            File target = inside(game, path), tmp = new File(target.getPath() + ".padport-tmp");
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException(context.getString(R.string.rgss_storage_error));
            try (InputStream in = source.open(path); OutputStream out = new FileOutputStream(tmp)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    done += n;
                    progress.update(done, total);
                }
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            old.put(path, current.get(path));
        }
        for (String path : old.keySet()) {
            if (current.containsKey(path)) continue;
            File target = inside(game, path);
            if (target.isFile() && !target.delete()) throw new IOException(context.getString(R.string.rgss_storage_error));
        }
        saveManifest(current);
    }

    /** Writes mkxp.json and the PadPort preload; returns the mkxp-z working directory. */
    File configure(Context context, GameSource source) throws Exception {
        RgssGame.Info info = new RgssGame.Info(source.exec, source.title, source.rgssVersion(), source.exec + ".ini");
        File preload = new File(base, "padport_rgss.rb");
        try (InputStream in = context.getAssets().open("rgss/padport_rgss.rb")) { writeAtomic(preload, RgssGame.readAll(in, 1 << 20)); }
        JSONObject gameJson = null;
        Map<String, String> gameConf = null;
        File json = find(game, "mkxp.json"), conf = find(game, "mkxp.conf");
        if (json != null) gameJson = RgssGame.json5(read(json));
        if (conf != null) {
            List<String> preloads = new ArrayList<>();
            File folder = find(game, "preload");
            String[] names = folder == null ? null : folder.list();
            if (names != null) for (String name : names) if (name.toLowerCase(Locale.ROOT).endsWith(".rb")) preloads.add(folder.getName() + "/" + name);
            gameConf = RgssGame.withDefaultPreloads(RgssGame.mkxpConf(read(conf)), preloads);
        }
        JSONObject config = RgssGame.config(info, game.getAbsolutePath(), preload.getAbsolutePath(), gameJson, gameConf);
        if(DualScreenProfile.TO_THE_MOON.equals(DualScreenProfile.identify(source.title,source.engine))){
            File companion=new File(base,"padport_ttm_dual.rb");
            try(InputStream in=context.getAssets().open("rgss/padport_ttm_dual.rb")){writeAtomic(companion,RgssGame.readAll(in,1<<20));}
            config.getJSONArray("preloadScript").put(companion.getAbsolutePath());
        }
        writeAtomic(new File(base, "mkxp.json"), config.toString(2).getBytes(StandardCharsets.UTF_8));
        return base;
    }

    private static File find(File dir, String name) {
        String[] names = dir.list();
        if (names != null) for (String n : names) if (n.equalsIgnoreCase(name)) return new File(dir, n);
        return null;
    }

    private static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return RgssGame.text(RgssGame.readAll(in, 4 << 20)); }
    }

    static void writeAtomic(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) { out.write(data); }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- saves ---------------------------------------------------------------

    /** Save data = the saves directory + files created by the game inside its folder. */
    int exportSaves(OutputStream stream) throws Exception {
        Map<String, Long> original = manifest();
        int count = 0;
        try (ZipOutputStream zip = new ZipOutputStream(stream)) {
            count += zipTree(zip, saves, "saves/", null);
            count += zipTree(zip, game, "game/", original.keySet());
        }
        return count;
    }

    private static int zipTree(ZipOutputStream zip, File root, String prefix, Set<String> exclude) throws IOException {
        int count = 0;
        ArrayDeque<File> queue = new ArrayDeque<>();
        if (root.isDirectory()) queue.add(root);
        String rootPath = root.getCanonicalPath();
        while (!queue.isEmpty()) {
            File[] children = queue.remove().listFiles();
            if (children == null) continue;
            Arrays.sort(children);
            for (File child : children) {
                if (child.isDirectory()) { queue.add(child); continue; }
                String relative = child.getCanonicalPath().substring(rootPath.length() + 1).replace(File.separatorChar, '/');
                if (exclude != null && exclude.contains(relative)) continue;
                if (relative.endsWith(".padport-tmp")) continue;
                zip.putNextEntry(new ZipEntry(prefix + relative));
                Files.copy(child.toPath(), zip);
                zip.closeEntry();
                count++;
            }
        }
        return count;
    }

    int importSaves(InputStream stream) throws Exception {
        Set<String> original = manifest().keySet();
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(stream)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                File target;
                if (name.startsWith("saves/")) target = inside(saves, name.substring(6));
                else if (name.startsWith("game/") && !original.contains(name.substring(5))) target = inside(game, name.substring(5));
                else continue;
                writeAtomic(target, RgssGame.readAll(zip, 256 << 20));
                count++;
            }
        }
        return count;
    }

    /**
     * Removes the private copy of the original game files. Files the game created
     * itself (e.g. Save1.rxdata next to Game.ini) and the saves directory stay,
     * so re-adding the folder restores the progress.
     */
    void deleteGameCopy() throws IOException {
        for (String path : manifest().keySet()) Files.deleteIfExists(inside(game, path).toPath());
        prune(game);
        Files.deleteIfExists(manifestFile().toPath());
        Files.deleteIfExists(new File(base, "mkxp.json").toPath());
        Files.deleteIfExists(new File(base, "padport_rgss.rb").toPath());
        Files.deleteIfExists(new File(base,"padport_ttm_dual.rb").toPath());
        File companion=new File(base,"companion");
        for(String name:List.of("control.json","control.json.tmp","state.json","state.json.ruby-tmp"))Files.deleteIfExists(new File(companion,name).toPath());
        prune(companion);
        prune(base);
    }

    /** Deletes leftover temporary files and empty directories below (and including) dir. */
    private static void prune(File dir) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) prune(child);
            else if (child.getName().endsWith(".padport-tmp")) Files.deleteIfExists(child.toPath());
        }
        String[] left = dir.list();
        if (left != null && left.length == 0) Files.deleteIfExists(dir.toPath());
    }
}
