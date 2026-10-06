package pl.padport.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Loopback HTTP server for engines without request interception (GeckoView).
 * Serves the read-only SAF game folder exactly like {@link GameWebClient}:
 * case-insensitive names, MV/MZ audio fallback, byte ranges, injected bridge.
 *
 * Only 127.0.0.1 is bound. Every URL carries a random per-session token, so other
 * apps on the device cannot read the game or talk to the bridge. The port is
 * derived from the game id, which keeps the web origin (and with it the saves in
 * localStorage/IndexedDB) stable between launches.
 */
final class GameHttpServer implements Closeable {
    private static final String IDLE = "{\"revision\":0,\"paused\":false,\"pads\":[]}";
    private final Context context;
    private final GameSource source;
    private final Consumer<String> log;
    private final ServerSocket socket;
    private final String token;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PadPort-http"); t.setDaemon(true); return t;
    });
    private final Set<BlockingQueue<String>> listeners = ConcurrentHashMap.newKeySet();
    private volatile String snapshot = IDLE;
    private volatile boolean closed;
    Consumer<String> exported = payload -> {}, saveError = message -> {};
    Runnable imported = () -> {};
    volatile java.util.function.Supplier<String> dualPoll=()->"{\"active\":false}";
    volatile Consumer<String> dualSnapshot=payload->{};

    GameHttpServer(Context context, GameSource source, Consumer<String> log) throws IOException {
        this.context = context; this.source = source; this.log = log;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder t = new StringBuilder("t");
        for (byte b : random) t.append(String.format(Locale.ROOT, "%02x", b));
        token = t.toString();
        socket = bind(portFor(source.id));
        pool.execute(this::acceptLoop);
    }

    static int portFor(String id) {
        return 20000 + (int)(Long.parseLong(id.substring(0, 8), 16) % 30000);
    }

    private static ServerSocket bind(int preferred) throws IOException {
        IOException last = null;
        for (int i = 0; i < 20; i++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferred + i), 64);
                return s;
            } catch (IOException e) { last = e; }
        }
        throw last;
    }

    String url(String path) { return "http://127.0.0.1:" + socket.getLocalPort() + "/" + token + "/" + path; }
    String origin() { return "http://127.0.0.1:" + socket.getLocalPort(); }

    /** New controller state for the page (Server-Sent Events). */
    void publish(String state) { snapshot = state; broadcast("data: " + state.replace("\n", "") + "\n\n"); }
    /** pause / resume / exportSaves / restore:<json> */
    void command(String command) { broadcast("event: cmd\ndata: " + command.replace("\n", " ") + "\n\n"); }

    private void broadcast(String message) { for (BlockingQueue<String> q : listeners) q.offer(message); }

    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
        broadcast("");
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = socket.accept();
                pool.execute(() -> serve(client));
            } catch (IOException e) {
                if (!closed) log.accept("HTTP accept: " + e);
            }
        }
    }

    // ---- HTTP -----------------------------------------------------------------

    private static final class Request {
        String method, path;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    private void serve(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            while (!closed) {
                Request request = read(in);
                if (request == null) return;
                boolean keepAlive = !"close".equalsIgnoreCase(request.headers.get("connection"));
                if (!handle(request, out)) return; // streaming responses own the socket
                out.flush();
                if (!keepAlive) return;
            }
        } catch (SocketTimeoutException | SocketException ignored) {
            // idle keep-alive connection closed
        } catch (IOException e) {
            if (!closed) log.accept("HTTP: " + e);
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("Header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static Request read(InputStream in) throws IOException {
        String first = line(in);
        if (first == null || first.isEmpty()) return null;
        String[] parts = first.split(" ");
        if (parts.length < 2) throw new IOException("Bad request line");
        Request r = new Request();
        r.method = parts[0];
        r.path = parts[1];
        for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
            int colon = h.indexOf(':');
            if (colon > 0) r.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        int length = 0;
        try { length = Integer.parseInt(r.headers.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) { }
        if (length < 0 || length > 64 * 1024 * 1024) throw new IOException("Body too large");
        if (length > 0) {
            r.body = new byte[length];
            int off = 0;
            while (off < length) { int n = in.read(r.body, off, length - off); if (n < 0) throw new EOFException(); off += n; }
        }
        return r;
    }

    private static void head(OutputStream out, int status, String reason, String mime, long length, Map<String, String> extra) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        if (length >= 0) h.append("Content-Length: ").append(length).append("\r\n");
        h.append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n");
        for (Map.Entry<String, String> e : extra.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private void bytes(OutputStream out, Request r, String mime, byte[] data) throws IOException {
        head(out, 200, "OK", mime, data.length, Map.of());
        if (!"HEAD".equals(r.method)) out.write(data);
    }

    private void error(OutputStream out, Request r, int status, String reason) throws IOException {
        if (status != 404 || !r.path.contains("favicon")) log.accept(status + " " + r.path);
        writeError(out, r.method, status, reason);
    }

    /**
     * The real status matters: MZ decrypts any image with status < 400 (a "200 Not found" body
     * is shorter than the 16-byte header: "RangeError: attempting to construct out-of-bounds
     * Uint8Array"), and the read-only fs shim treats HEAD 200 as "file exists" - e.g. Look
     * Outside's monsterImageExists() then switched the Grinning Beast to a missing pose.
     */
    static void writeError(OutputStream out, String method, int status, String reason) throws IOException {
        byte[] body = reason.getBytes(StandardCharsets.UTF_8);
        head(out, status, reason, "text/plain; charset=utf-8", body.length, Map.of());
        if (!"HEAD".equals(method)) out.write(body);
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) { return GameSource.readBounded(context, in, 1024 * 1024); }
    }

    /** @return false when the response keeps the connection (event stream). */
    private boolean handle(Request r, OutputStream out) throws IOException {
        String prefix = "/" + token + "/";
        String raw = r.path;
        int query = raw.indexOf('?');
        if (query >= 0) raw = raw.substring(0, query);
        if (!raw.startsWith(prefix)) { error(out, r, 403, "Forbidden"); return true; }
        String path;
        try { path = AssetPaths.normalize(URLDecoder.decode(raw.substring(prefix.length()).replace("+", "%2B"), "UTF-8")); }
        catch (IllegalArgumentException e) { error(out, r, 403, "Invalid path"); return true; }
        if (path.isEmpty()) path = "index.html";
        try {
            switch (path) {
                case "__padport__/dual-poll": bytes(out,r,"application/json",dualPoll.get().getBytes(StandardCharsets.UTF_8));return true;
                case "__padport__/dual-snapshot": dualSnapshot.accept(new String(r.body,StandardCharsets.UTF_8));bytes(out,r,"text/plain",new byte[0]);return true;
                case "__padport__/events": return events(out);
                case "__padport__/log": log.accept(new String(r.body, StandardCharsets.UTF_8)); bytes(out, r, "text/plain", new byte[0]); return true;
                case "__padport__/export": exported.accept(new String(r.body, StandardCharsets.UTF_8)); bytes(out, r, "text/plain", new byte[0]); return true;
                case "__padport__/imported": imported.run(); bytes(out, r, "text/plain", new byte[0]); return true;
                case "__padport__/save-error": saveError.accept(new String(r.body, StandardCharsets.UTF_8)); bytes(out, r, "text/plain", new byte[0]); return true;
                case "__padport__/bridge.js": {
                    JSONObject config = new JSONObject().put("title", source.title).put("engine", source.engine)
                        .put("language", AppLanguage.code(context)).put("renderer", "gecko")
                        .put("dualScreenProfile",DualScreenProfile.identify(source.title,source.engine));
                    GameCompat.config(config,source);
                    String js = "window.__PADPORT_CONFIG__=" + config + ";\n" + new String(asset("gecko-host.js"), StandardCharsets.UTF_8) + "\n"
                        + new String(asset("ui-strings.js"), StandardCharsets.UTF_8) + "\n" + new String(asset("bridge.js"), StandardCharsets.UTF_8)
                        + "\n" + new String(asset("game-compat.js"),StandardCharsets.UTF_8)
                        + "\n" + new String(asset("look-outside-dual.js"),StandardCharsets.UTF_8)
                        + "\n" + new String(asset("fear-and-hunger-dual.js"),StandardCharsets.UTF_8)
                        + "\n" + new String(asset("elderfield-dual.js"),StandardCharsets.UTF_8);
                    bytes(out, r, "application/javascript; charset=utf-8", js.getBytes(StandardCharsets.UTF_8));
                    return true;
                }
                case "index.html": {
                    String html = GameCompat.entry(source, new String(source.read(path, 4 * 1024 * 1024), StandardCharsets.UTF_8));
                    bytes(out, r, "text/html; charset=utf-8", inject(html).getBytes(StandardCharsets.UTF_8));
                    return true;
                }
                default: return file(r, out, path);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.accept(e.toString());
            error(out, r, 500, "Read failed");
            return true;
        }
    }

    /** Relative bridge path (the token prefix is part of the URL) and a mobile viewport. */
    static String inject(String html) {
        String script = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">"
            + "<script src=\"__padport__/bridge.js\"></script>";
        java.util.regex.Matcher head = java.util.regex.Pattern.compile("<head\\b[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (head.find()) return html.substring(0, head.end()) + script + html.substring(head.end());
        return script + html;
    }

    private boolean file(Request r, OutputStream out, String path) throws IOException {
        GameSource.Entry entry = source.entry(path);
        if (entry == null) { error(out, r, 404, "Not found"); return true; }
        String mime = GameWebClient.mime(source.mimePath(path));
        if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) mime += "; charset=utf-8";
        String range = r.headers.get("range");
        Map<String, String> extra = new HashMap<>();
        extra.put("Accept-Ranges", "bytes");
        long start = 0, length = entry.size();
        int status = 200;
        if (range != null && entry.size() >= 0) {
            long[] span = AssetPaths.range(range, entry.size());
            if (span == null) {
                head(out, 416, "Range Not Satisfiable", "text/plain", 0, Map.of("Content-Range", "bytes */" + entry.size()));
                return true;
            }
            start = span[0]; length = span[1] - span[0] + 1; status = 206;
            extra.put("Content-Range", "bytes " + span[0] + "-" + span[1] + "/" + entry.size());
        }
        try (InputStream in = source.open(path)) {
            long skip = start;
            while (skip > 0) { long n = in.skip(skip); if (n <= 0) { if (in.read() < 0) throw new EOFException(); n = 1; } skip -= n; }
            head(out, status, status == 206 ? "Partial Content" : "OK", mime, length, extra);
            if ("HEAD".equals(r.method)) return true;
            byte[] buffer = new byte[65536];
            long left = length < 0 ? Long.MAX_VALUE : length;
            while (left > 0) {
                int n = in.read(buffer, 0, (int)Math.min(buffer.length, left));
                if (n < 0) break;
                out.write(buffer, 0, n);
                left -= n;
            }
        }
        return length >= 0; // unknown length: close the connection to end the body
    }

    private boolean events(OutputStream out) throws IOException {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        listeners.add(queue);
        try {
            head(out, 200, "OK", "text/event-stream; charset=utf-8", -1, Map.of("Connection", "keep-alive"));
            out.write(("retry: 500\ndata: " + snapshot + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (!closed) {
                String message = queue.poll(15, TimeUnit.SECONDS);
                if (closed) break;
                out.write((message == null ? ": ping\n\n" : message).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            listeners.remove(queue);
        }
        return false;
    }
}
