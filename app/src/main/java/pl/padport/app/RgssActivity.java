package pl.padport.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.system.Os;
import android.util.Log;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.libsdl.app.SDLActivity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native player for RPG Maker XP/VX/VX Ace (mkxp-z). Runs in its own process
 * (":rgss"): the embedded Ruby VM cannot be initialised twice, so the process
 * exits together with the game, while the library process keeps running.
 */
public class RgssActivity extends SDLActivity {
    private static final String TAG = "PadPort-RGSS";
    private static final int EXPORT = 301, IMPORT = 302;
    private static final long MENU_IDLE_MS = 5000;

    /** Read through JNI by libmkxp-z: working directory with the generated mkxp.json. */
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    private static String GAME_PATH = "/";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> held = new TreeSet<>();
    private ControllerHub controllers;
    private RgssRuntime runtime;
    private DualScreenSession dualScreen;
    private RgssCompanionBridge companionBridge;
    private static boolean webDataPrepared;
    private String gameId, title = "PadPort";
    private TextView status;
    private Button menuButton;
    private VirtualControllerView virtualControls;
    private AlertDialog menu;
    private boolean prepared, started, guideDown, touching, nativeRunning;
    private final Runnable hideMenuButton = () -> { if (menuButton != null && menu == null && !touching) menuButton.setVisibility(View.INVISIBLE); };

    @Override protected void onCreate(Bundle state) {
        if(Build.VERSION.SDK_INT>=28&&!webDataPrepared){
            // The library/Look Outside WebView may still be alive in the main process.
            android.webkit.WebView.setDataDirectorySuffix("rgss-companion");webDataPrepared=true;
        }
        try {
            // Controllers go through PadPort's ControllerHub (profiles, learned keys).
            // SDL must not open them again through HIDAPI/USB, or input would be doubled.
            Os.setenv("SDL_JOYSTICK_HIDAPI", "0", true);
        } catch (Exception e) { Log.w(TAG, "Unable to set SDL hints", e); }
        super.onCreate(state);
        if (mBrokenLibraries) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        gameId = getIntent().getStringExtra("game");
        controllers = new ControllerHub(this);
        controllers.onChange = this::padChanged;
        controllers.start();

        status = Ui.text(this, R.string.rgss_preparing, 18, Ui.TEXT);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(Ui.BG);
        status.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        mLayout.addView(status, new RelativeLayout.LayoutParams(-1, -1));

        virtualControls = new VirtualControllerView(this, controllers);
        mLayout.addView(virtualControls, new RelativeLayout.LayoutParams(-1, -1));

        menuButton = Ui.button(this, "⋮", this::openMenu);
        menuButton.setTextSize(24);
        menuButton.setAlpha(.4f);
        menuButton.setFocusable(false);
        menuButton.setContentDescription(getString(R.string.app_menu));
        RelativeLayout.LayoutParams bp = new RelativeLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 48));
        bp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        bp.addRule(RelativeLayout.ALIGN_PARENT_END);
        bp.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 10), 0);
        mLayout.addView(menuButton, bp);
        menuButton.setVisibility(View.INVISIBLE);

        worker.execute(this::prepare);
    }

    private void prepare() {
        try {
            JSONObject metadata = gameId == null ? null : Library.get(this, gameId);
            if (metadata == null) throw new IOException(getString(R.string.game_not_in_library));
            GameSource source = GameSource.restore(this, metadata);
            if (!source.isRgss()) throw new IOException(getString(R.string.rgss_wrong_engine));
            title = source.title;
            runtime = new RgssRuntime(this, gameId);
            long[] last = {0};
            runtime.sync(this, source, (done, total) -> {
                long now = System.currentTimeMillis();
                if (now - last[0] < 250 && done < total) return;
                last[0] = now;
                int percent = total <= 0 ? 100 : (int)(done * 100 / total);
                String text = getString(R.string.rgss_copying, percent, (int)(done >> 20), (int)(total >> 20));
                runOnUiThread(() -> { if (status != null) status.setText(text); });
            });
            File config = runtime.configure(this, source);
            boolean companion=DualScreenProfile.TO_THE_MOON.equals(DualScreenProfile.identify(source.title,source.engine));
            if(companion)Os.setenv("PADPORT_TTM_COMPANION",new File(runtime.base,"companion").getAbsolutePath(),true);
            Os.setenv("PADPORT_SAVE_DIR", runtime.saves.getAbsolutePath(), true);
            GAME_PATH = config.getAbsolutePath();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                mLayout.removeView(status);
                status = null;
                prepared = true;
                if(companion){
                    dualScreen=new DualScreenSession(this,gameId);
                    try{companionBridge=new RgssCompanionBridge(new File(runtime.base,"companion"),dualScreen.channel,dualScreen::snapshot);}
                    catch(IOException e){Log.w(TAG,"Cannot start To the Moon companion",e);dualScreen.close();dualScreen=null;}
                }
                startIfReady();
                showMenuButton();
            });
        } catch (Exception e) {
            Log.e(TAG, "Unable to prepare RGSS game", e);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle(R.string.launch_error)
                    .setMessage(getString(R.string.launch_error_details, e.getMessage() == null ? e.toString() : e.getMessage()))
                    .setPositiveButton(R.string.back, (d, n) -> finish()).setOnCancelListener(d -> finish()).show();
            });
        }
    }

    private void startIfReady() {
        if (prepared && started && menu == null) {
            resumeNativeThread();
            nativeRunning = true;
            controllers.clear(false);
            hideSystemBars();
            refreshVirtualController();
            if(dualScreen!=null)dualScreen.resume();
        }
    }

    /** Status/navigation bars stay hidden whatever window style SDL requests. */
    private final Runnable immersive = () -> {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (Build.VERSION.SDK_INT >= 28 && attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    };

    private void hideSystemBars() {
        handler.removeCallbacks(immersive);
        handler.post(immersive);
        // SDL applies its own window style asynchronously; enforce again afterwards.
        handler.postDelayed(immersive, 500);
    }

    @Override public void onSystemUiVisibilityChange(int visibility) {
        super.onSystemUiVisibilityChange(visibility);
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 && menu == null) {
            handler.removeCallbacks(immersive);
            handler.postDelayed(immersive, 1500);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        hideSystemBars();
        if (controllers != null) controllers.refresh();
        startIfReady();
    }

    @Override protected void onStop() {
        if(dualScreen!=null)dualScreen.pause();
        started = false;
        nativeRunning = false;
        if (virtualControls != null) virtualControls.suspend();
        releaseKeys();
        if (controllers != null) controllers.clear(true);
        super.onStop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String requested = intent.getStringExtra("game");
        if (requested != null && !requested.equals(gameId)) Toast.makeText(this, R.string.rgss_another_game, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        if(companionBridge!=null)companionBridge.close();
        if(dualScreen!=null)dualScreen.close();
        handler.removeCallbacksAndMessages(null);
        if (virtualControls != null) virtualControls.suspend();
        if (controllers != null) controllers.close();
        worker.shutdownNow();
        super.onDestroy();
        // The Ruby VM inside libmkxp-z cannot be restarted in the same process.
        if (!mBrokenLibraries) System.exit(0);
    }

    // ---- input ------------------------------------------------------------------

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (controllers != null && controllers.key(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) openMenu();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (controllers != null && controllers.motion(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        boolean handled = super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) { touching = true; showMenuButton(); }
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { touching = false; showMenuButton(); }
        return handled;
    }

    private void refreshVirtualController() {
        if (virtualControls != null) virtualControls.configure(prepared && VirtualControllerSettings.enabled(this), nativeRunning && menu == null && hasWindowFocus());
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideSystemBars();
        if (!focus) {
            if (virtualControls != null) virtualControls.suspend();
            if (controllers != null) controllers.clear(true);
        } else if (nativeRunning && menu == null) {
            controllers.clear(false);
            refreshVirtualController();
        }
    }

    private void showMenuButton() {
        handler.removeCallbacks(hideMenuButton);
        if (menuButton == null || !prepared || menu != null || isFinishing()) return;
        menuButton.setVisibility(View.VISIBLE);
        if (!touching) handler.postDelayed(hideMenuButton, MENU_IDLE_MS);
    }

    private void padChanged() {
        boolean guide = (!controllers.keyboardMode() || controllers.keyboardBinding(RgssKeys.GUIDE)==0)
            && controllers.button(RgssKeys.GUIDE) > .5f;
        if (guide && !guideDown) handler.post(this::openMenu);
        guideDown = guide;
        Set<Integer> wanted = !nativeRunning ? Set.of() : controllers.keyboardMode() ? controllers.keyboardKeys() : RgssKeys.pressed(new RgssKeys.State() {
            public float button(int index) { return controllers.button(index); }
            public float axis(int index) { return controllers.axis(index); }
        });
        for (int key : KeyboardMapping.ordered(held,false)) if (!wanted.contains(key)) { SDLActivity.onNativeKeyUp(key); held.remove(key); }
        for (int key : KeyboardMapping.ordered(wanted,true)) if (held.add(key)) SDLActivity.onNativeKeyDown(key);
    }

    private void releaseKeys() {
        for (int key : KeyboardMapping.ordered(held,false)) if (prepared) SDLActivity.onNativeKeyUp(key);
        held.clear();
    }

    // ---- PadPort menu ---------------------------------------------------------

    private void openMenu() {
        if (menu != null || isFinishing() || !prepared) return;
        if(dualScreen!=null)dualScreen.pause();
        releaseKeys();
        controllers.clear(true);
        if (virtualControls != null) virtualControls.suspend();
        if (nativeRunning) { pauseNativeThread(); nativeRunning = false; }
        handler.removeCallbacks(hideMenuButton);
        menuButton.setVisibility(View.INVISIBLE);
        String[] items = {getString(R.string.back_to_game), getString(R.string.export_saves), getString(R.string.import_saves),
            getString(R.string.diagnostics_copy_log), getString(R.string.rgss_exit_game)};
        LinearLayout settings=Ui.column(this);
        settings.addView(VirtualControllerSettings.checkbox(this,enabled->refreshVirtualController()));
        settings.addView(DualScreenOptions.controls(this,Library.get(this,gameId),()->{}));
        menu = Ui.menuDialog(this,title,items,settings, (d, n) -> {
            if (n == 1) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
                i.putExtra(Intent.EXTRA_TITLE, "PadPort-" + title.replaceAll("[^\\p{L}\\p{N} ._-]", "_") + "-saves.zip");
                startActivityForResult(i, EXPORT);
            } else if (n == 2) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                startActivityForResult(i, IMPORT);
            } else if (n == 3) showLog();
            else if (n == 4) finish();
        });
        menu.setOnDismissListener(d -> {
            menu = null;
            if (!isFinishing()) { startIfReady(); showMenuButton(); }
        });
        menu.show();
    }

    private void showLog() {
        String content;
        try {
            java.lang.Process logcat = new ProcessBuilder("logcat", "-d", "-v", "brief", "--pid=" + Process.myPid()).redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcat.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.contains("EGL_emulation") || line.contains("app_time_stats")) continue;
                    lines.add(line);
                    if (lines.size() > 400) lines.remove(0);
                }
            }
            content = String.join("\n", lines);
        } catch (IOException e) { content = e.toString(); }
        String log = content;
        TextView text = Ui.text(this, log, 11, Ui.TEXT);
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        new AlertDialog.Builder(this).setTitle(R.string.game_diagnostics).setView(scroll).setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy, (d, n) -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PadPort RGSS log", log)))
            .show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || runtime == null) return;
        Uri uri = data.getData();
        if (request == EXPORT) worker.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException(getString(R.string.file_access_error));
                int count = runtime.exportSaves(out);
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.rgss_saves_exported, count), Toast.LENGTH_LONG).show());
            } catch (Exception e) { error(e); }
        });
        else if (request == IMPORT) new AlertDialog.Builder(this).setTitle(R.string.restore_question).setMessage(R.string.rgss_import_details)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.restore, (d, n) -> worker.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException(getString(R.string.backup_access_error));
                    int count = runtime.importSaves(in);
                    runOnUiThread(() -> Toast.makeText(this, getString(R.string.rgss_saves_imported, count), Toast.LENGTH_LONG).show());
                } catch (Exception e) { error(e); }
            })).show();
    }

    private void error(Exception e) {
        Log.e(TAG, "Save backup failed", e);
        runOnUiThread(() -> {
            if (!isDestroyed()) new AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage(e.toString()).setPositiveButton(R.string.ok, null).show();
        });
    }

    // ---- called from libmkxp-z through JNI -----------------------------------

    @SuppressWarnings("unused") private static String getSystemLanguage() { return Locale.getDefault().toString(); }

    @SuppressWarnings("unused") private static boolean hasVibrator() {
        Vibrator vibrator = (Vibrator)getContext().getSystemService(Context.VIBRATOR_SERVICE);
        return vibrator != null && vibrator.hasVibrator();
    }

    @SuppressWarnings("unused") private static void vibrate(int duration) {
        Vibrator vibrator = (Vibrator)getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(Math.max(1, duration), VibrationEffect.DEFAULT_AMPLITUDE));
    }

    @SuppressWarnings("unused") private static void vibrateStop() {
        Vibrator vibrator = (Vibrator)getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) vibrator.cancel();
    }

    @SuppressWarnings("unused") private static boolean inMultiWindow(android.app.Activity activity) {
        return activity.isInMultiWindowMode();
    }

    @Override protected String[] getArguments() { return new String[0]; }
}
