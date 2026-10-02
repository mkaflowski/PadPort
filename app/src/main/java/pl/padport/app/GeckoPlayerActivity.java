package pl.padport.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.mozilla.geckoview.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MV/MZ player on GeckoView - the default engine (see {@link GameEngine}). Its
 * content process belongs to this app and is 64-bit, unlike the system WebView
 * renderer on some devices (AYN Thor: WebView 109, armeabi-v7a renderer with
 * ~3-4 GB of address space).
 *
 * Feature parity with {@link PlayerActivity}: app menu with controller mapping,
 * save export/import, diagnostics; on-screen controller; keyboard output mode;
 * auto-hiding menu button; dual screen. GeckoView has no request interception or
 * JS interface, so the game is served by {@link GameHttpServer} and the page talks
 * to us through gecko-host.js (Server-Sent Events + POST).
 */
public class GeckoPlayerActivity extends LocalizedActivity {
    private static final String TAG = "PadPort-Gecko";
    private static final int EXPORT = 201, IMPORT = 202;
    private static final long MENU_IDLE_MS = 5000;
    private static final float MENU_ALPHA = .4f;
    private static GeckoRuntime runtime; // one per process

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private ControllerHub controllers;
    private GameHttpServer server;
    private GameSource source;
    private DualScreenSession dualScreen;
    private GeckoView view;
    private GeckoSession session;
    private KeyboardDispatcher keyboard;
    private VirtualControllerView virtualControls;
    private Button menuButton;
    private FrameLayout root;
    private AlertDialog menu;
    private boolean foreground, pageReady, touchingScreen;
    private volatile boolean exporting;
    private String exportPayload;
    private final Handler menuHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideMenuButton = () -> {
        if (menuButton != null && menu == null && !touchingScreen) menuButton.setVisibility(View.INVISIBLE);
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        DisplayRate.apply(this);
        TextView loading = Ui.text(this, R.string.opening_game, 20, Ui.TEXT);
        loading.setGravity(Gravity.CENTER);
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        controllers = new ControllerHub(this);
        controllers.start();
        String id = getIntent().getStringExtra("game");
        worker.execute(() -> {
            try {
                JSONObject metadata = id == null ? null : Library.get(this, id);
                if (metadata == null) throw new IOException(getString(R.string.game_not_in_library));
                GameSource game = GameSource.restore(this, metadata);
                GameHttpServer http = new GameHttpServer(getApplicationContext(), game, this::log);
                runOnUiThread(() -> { if (isDestroyed()) http.close(); else start(game, http); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) new AlertDialog.Builder(this).setTitle(R.string.launch_error)
                        .setMessage(getString(R.string.launch_error_details, e.toString()))
                        .setPositiveButton(R.string.back, (d, n) -> finish()).setOnCancelListener(d -> finish()).show();
                });
            }
        });
    }

    private void start(GameSource game, GameHttpServer http) {
        source = game;
        server = http;
        pageReady = false;
        if (keyboard != null) keyboard.clear();
        if(dualScreen!=null){dualScreen.close();dualScreen=null;}
        if(!DualScreenProfile.identify(game.title,game.engine).isEmpty()){
            dualScreen=new DualScreenSession(this,game);
            DualScreenSession current=dualScreen;
            server.dualPoll=current.channel::poll;server.dualSnapshot=current::snapshot;
            if(foreground)dualScreen.resume();
        }
        // Save backup callbacks arrive on HTTP threads (gecko-host.js POSTs).
        server.exported = this::exportReady;
        server.imported = () -> runOnUiThread(() -> {
            if (session != null && !isDestroyed()) {
                Toast.makeText(this, R.string.saves_restored, Toast.LENGTH_LONG).show();
                pageReady = false;
                session.reload();
            }
        });
        server.saveError = message -> { exporting = false; runOnUiThread(() -> { if (!isDestroyed()) error(message); }); };
        controllers.onChange = () -> { server.publish(controllers.snapshot()); updateKeyboard(); };
        server.publish(controllers.snapshot());
        if (runtime == null) {
            boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            GeckoRuntimeSettings.Builder settings = new GeckoRuntimeSettings.Builder()
                .consoleOutput(true).remoteDebuggingEnabled(debuggable).javaScriptEnabled(true);
            String config = writeRuntimeConfig();
            if (config != null) settings.configFilePath(config);
            runtime = GeckoRuntime.create(getApplicationContext(), settings.build());
        }
        session = new GeckoSession(new GeckoSessionSettings.Builder()
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .usePrivateMode(false).build());
        GeckoSession current = session;
        // Keyboard mode: generated keys go straight into Gecko, never back through the hub.
        keyboard = new KeyboardDispatcher(event -> {
            if (session != current) return;
            SessionTextInput input = current.getTextInput();
            if (event.getAction() == KeyEvent.ACTION_DOWN) input.onKeyDown(event.getKeyCode(), event);
            else input.onKeyUp(event.getKeyCode(), event);
        });
        String allowed = server.url("");
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                boolean ok = request.uri.startsWith(allowed) || request.uri.startsWith("about:");
                if (!ok) log("Blocked navigation: " + request.uri);
                return GeckoResult.fromValue(ok ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession s, String url) { pageReady = false; updateKeyboard(); }
            @Override public void onPageStop(GeckoSession s, boolean success) {
                pageReady = true;
                if (foreground && menu == null) resumeGame();
            }
        });
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission permission) {
                boolean autoplay = permission.permission == PERMISSION_AUTOPLAY_AUDIBLE || permission.permission == PERMISSION_AUTOPLAY_INAUDIBLE;
                return GeckoResult.fromValue(autoplay ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onCrash(GeckoSession s) { gone(true); }
            @Override public void onKill(GeckoSession s) { gone(false); }
        });
        session.open(runtime);
        root.removeAllViews();
        view = new GeckoView(this);
        view.setSession(session);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        virtualControls = new VirtualControllerView(this, controllers);
        root.addView(virtualControls, new FrameLayout.LayoutParams(-1, -1));
        menuButton = Ui.button(this, "⋮", this::openMenu);
        menuButton.setTextSize(24);
        menuButton.setAlpha(MENU_ALPHA);
        menuButton.setContentDescription(getString(R.string.app_menu));
        menuButton.setFocusable(false);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 48), Gravity.TOP | Gravity.END);
        bp.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 10), 0);
        root.addView(menuButton, bp);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int right = 0, left = 0;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                DisplayCutout cut = insets.getDisplayCutout();
                if (cut != null) { right = cut.getSafeInsetRight(); left = cut.getSafeInsetLeft(); }
            }
            v.setPadding(left, 0, right, 0);
            return insets;
        });
        immersive();
        view.requestFocus();
        session.loadUri(server.url("index.html"));
        refreshVirtualController();
        showMenuButton();
        log("GeckoView " + org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION + " · " + server.origin());
    }

    /**
     * Gecko preferences that GeckoRuntimeSettings has no setter for, in the YAML
     * format of GeckoView's DebugConfig. An explicit configFilePath is read in
     * release builds too (only the default /data/local/tmp path is debug-only).
     *
     * Autoplay: like Firefox, Gecko refuses to start an AudioContext before a
     * "user gesture", and gamepad input is not one - with a controller the game
     * stayed silent ("An AudioContext was prevented from starting automatically").
     * The WebView player has the same switch: setMediaPlaybackRequiresUserGesture(false).
     */
    static final String RUNTIME_CONFIG = "# Written by PadPort on every start - edits are overwritten.\n"
        + "prefs:\n"
        + "  media.autoplay.default: 0\n"
        + "  media.autoplay.block-webaudio: false\n";

    private String writeRuntimeConfig() {
        java.io.File file = new java.io.File(getFilesDir(), "geckoview-config.yaml");
        java.io.File tmp = new java.io.File(file + ".tmp");
        try {
            java.nio.file.Files.write(tmp.toPath(), RUNTIME_CONFIG.getBytes(StandardCharsets.UTF_8));
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return file.getAbsolutePath();
        } catch (IOException e) {
            log("GeckoView config not written, game audio may need a touch: " + e);
            return null;
        } finally {
            tmp.delete();
        }
    }

    /** The Gecko content process crashed or was killed. Keep the app, offer a restart. */
    private void gone(boolean crashed) {
        if(dualScreen!=null)dualScreen.pause();
        log("Gecko content process " + (crashed ? "crashed" : "was killed by the system"));
        pageReady = false;
        if (keyboard != null) keyboard.clear();
        if (session != null) { session.close(); session = null; }
        if (view != null) { root.removeView(view); view = null; }
        if (menu != null) { menu.dismiss(); menu = null; }
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(R.string.renderer_gone_title)
            .setMessage(crashed ? R.string.renderer_crashed_gecko : R.string.renderer_killed)
            .setPositiveButton(R.string.renderer_restart, (d, n) -> start(source, server))
            .setNegativeButton(R.string.back_to_library, (d, n) -> finish()).setCancelable(false).show();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (controllers != null && controllers.key(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) { if (menu != null) menu.dismiss(); else openMenu(); }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return controllers != null && controllers.motion(event) || super.dispatchGenericMotionEvent(event);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Deliver DOWN before revealing the button: a tap on its hidden area still
        // belongs to the game, rather than accidentally opening the menu.
        boolean handled = super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) { touchingScreen = true; showMenuButton(); }
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { touchingScreen = false; showMenuButton(); }
        return handled;
    }

    private void showMenuButton() {
        menuHandler.removeCallbacks(hideMenuButton);
        if (menuButton == null || !foreground || menu != null || isFinishing()) return;
        menuButton.setVisibility(View.VISIBLE);
        if (!touchingScreen) menuHandler.postDelayed(hideMenuButton, MENU_IDLE_MS);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (controllers != null) controllers.refresh();
        if (menu == null) resumeGame();
    }

    @Override protected void onPause() {
        foreground = false;
        pauseGame();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && foreground && menu == null) resumeGame();
        else if (!focus) {
            if (virtualControls != null) virtualControls.suspend();
            if (controllers != null) controllers.clear(true);
            updateKeyboard();
        }
    }

    private void updateKeyboard() {
        if (keyboard == null) return;
        if (session != null && pageReady && foreground && menu == null && hasWindowFocus() && controllers.keyboardMode())
            keyboard.update(controllers.keyboardKeys());
        else keyboard.clear();
    }

    private void refreshVirtualController() {
        if (virtualControls != null)
            virtualControls.configure(VirtualControllerSettings.enabled(this), foreground && menu == null && hasWindowFocus());
    }

    private void pauseGame() {
        if(dualScreen!=null)dualScreen.pause();
        if(server!=null)server.command("dual-disable");
        menuHandler.removeCallbacks(hideMenuButton);
        touchingScreen = false;
        if (virtualControls != null) virtualControls.suspend();
        if (controllers != null) controllers.clear(true);
        if (keyboard != null) keyboard.clear();
        if (server != null) server.command("pause");
        if (session != null) session.setActive(false);
    }

    private void resumeGame() {
        if(dualScreen!=null&&session!=null)dualScreen.resume();
        if (controllers != null) controllers.clear(false);
        if (session != null) session.setActive(true);
        if (server != null) server.command("resume");
        immersive();
        if (view != null) view.requestFocus();
        refreshVirtualController();
        showMenuButton();
        updateKeyboard();
    }

    private void openMenu() {
        if (menu != null || source == null || session == null) return;
        pauseGame();
        String[] items = {getString(R.string.back_to_game), getString(R.string.controller_mapping), getString(R.string.export_saves),
            getString(R.string.import_saves), getString(R.string.diagnostics_copy_log), getString(R.string.back_to_library)};
        LinearLayout settings = Ui.column(this);
        settings.addView(VirtualControllerSettings.checkbox(this, enabled -> refreshVirtualController()));
        settings.addView(DisplayRate.checkbox(this));
        settings.addView(DualScreenOptions.controls(this, Library.get(this, source.id), () -> {}));
        menu = Ui.menuDialog(this, source.title + " · GeckoView", items, settings, (d, n) -> {
            if (n == 1) startActivity(new Intent(this, ControllerActivity.class));
            else if (n == 2) { exporting = true; if (server != null) server.command("exportSaves"); }
            else if (n == 3) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, IMPORT);
            }
            else if (n == 4) showLog();
            else if (n == 5) finish();
        });
        menu.setOnDismissListener(d -> { menu = null; if (foreground && !isFinishing()) resumeGame(); });
        menu.show();
    }

    /** gecko-host.js posted the collected saves (HTTP thread). */
    private void exportReady(String payload) {
        if (!exporting) return;
        exporting = false;
        if (payload.length() > 32 * 1024 * 1024) { runOnUiThread(() -> { if (!isDestroyed()) error(getString(R.string.backup_too_large)); }); return; }
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            exportPayload = payload;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_TITLE, "PadPort-" + (source == null ? "game" : source.title.replaceAll("[^\\p{L}\\p{N} ._-]", "_")) + "-saves.json");
            startActivityForResult(intent, EXPORT);
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) { if (request == EXPORT) exportPayload = null; return; }
        Uri uri = data.getData();
        if (request == EXPORT && exportPayload != null) {
            String payload = exportPayload;
            exportPayload = null;
            worker.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException(getString(R.string.file_access_error));
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(() -> Toast.makeText(this, R.string.backup_exported, Toast.LENGTH_LONG).show());
                } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) error(e.toString()); }); }
            });
        } else if (request == IMPORT) {
            worker.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException(getString(R.string.backup_access_error));
                    JSONObject backup = new JSONObject(new String(GameSource.readBounded(this, in, 32 * 1024 * 1024), StandardCharsets.UTF_8));
                    runOnUiThread(() -> {
                        if (!isDestroyed()) new AlertDialog.Builder(this).setTitle(R.string.restore_question)
                            .setMessage(R.string.restore_details)
                            .setPositiveButton(R.string.restore, (d, n) -> { if (server != null) server.command("restore:" + backup); })
                            .setNegativeButton(R.string.cancel, null).show();
                    });
                } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) error(e.toString()); }); }
            });
        }
    }

    private synchronized void log(String text) {
        Log.i(TAG, text);
        logs.add(text.length() > 2000 ? text.substring(0, 2000) : text);
        while (logs.size() > 150) logs.remove();
    }

    private void showLog() {
        String content;
        synchronized (this) { content = String.join("\n", logs); }
        TextView text = Ui.text(this, content, 12, Ui.TEXT);
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle(R.string.game_diagnostics).setView(scroll).setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy, (d, n) -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PadPort log", content)))
            .show();
    }

    private void error(String message) {
        log(message);
        new AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage(message).setPositiveButton(R.string.ok, null).show();
    }

    @Override protected void onDestroy() {
        if(dualScreen!=null){dualScreen.close();dualScreen=null;}
        menuHandler.removeCallbacks(hideMenuButton);
        pageReady = false;
        if (keyboard != null) keyboard.clear();
        if (virtualControls != null) virtualControls.suspend();
        if (controllers != null) controllers.close();
        if (session != null) { session.close(); session = null; }
        if (server != null) server.close();
        worker.shutdownNow();
        super.onDestroy();
    }
}
