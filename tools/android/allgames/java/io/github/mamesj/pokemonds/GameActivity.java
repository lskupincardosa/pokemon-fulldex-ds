// The Android all-games app's activity: SDL's own, plus what the game list needs from Android
// (frontend/pc_launcher.cpp calls these static methods through JNI):
//  - the folder the player chose for their ROMs, picked once with Android's folder picker;
//  - access to files ("All files access"), so that the program reads the ROMs and writes the
//    saves in that folder with ordinary file calls, as on a PC;
//  - starting the app again (QUIT in the pause menu goes back to the list in a new process,
//    as on the PC and the Switch).
package io.github.mamesj.pokemonds;

import android.Manifest;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import android.system.Os;
import android.system.OsConstants;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;

import org.libsdl.app.SDLActivity;

public class GameActivity extends SDLActivity {
    private static final String TAG = "PokemonDS";
    private static final int PICK_FOLDER = 4201;
    private static final int ASK_FILES = 4202;
    private static volatile GameActivity self;
    private SecondScreen secondScreen;
    // True from chooseFolder() until the picker (and the access question before it) is done.
    private static volatile boolean picking;
    private boolean waitingForAccess;

    @Override
    protected void onCreate(Bundle state) {
        self = this;
        startupTrace("Starting Android " + Build.VERSION.SDK_INT + " ABI " + java.util.Arrays.toString(Build.SUPPORTED_ABIS), false);
        try { startupTrace("Memory page size " + Os.sysconf(OsConstants._SC_PAGESIZE), true); }
        catch (Exception ignored) { }
        super.onCreate(state);
        startupTrace("Activity created", true);
        getContentResolver().registerContentObserver(Settings.System.getUriFor(
            Settings.System.ACCELEROMETER_ROTATION), false, rotationObserver);
        secondScreen = new SecondScreen(this);
        applyScreenOrientation();
        preferSixtyHz(getWindow(), getWindowManager().getDefaultDisplay());
    }

    // The games show at most 60 frames a second, so a display at 120 Hz (the Thor's two run at
    // 120 by default) only composes every frame twice: ask for the display's 60 Hz mode at the
    // same size, where it has one.
    static void preferSixtyHz(android.view.Window window, android.view.Display display) {
        if (window == null || display == null || Build.VERSION.SDK_INT < 23) return;
        android.view.Display.Mode now = display.getMode();
        for (android.view.Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() == now.getPhysicalWidth() && m.getPhysicalHeight() == now.getPhysicalHeight()
                && Math.abs(m.getRefreshRate() - 60f) < 1f) {
                android.view.WindowManager.LayoutParams lp = window.getAttributes();
                lp.preferredDisplayModeId = m.getModeId();
                window.setAttributes(lp);
                return;
            }
        }
    }

    private void startupTrace(String message, boolean append) {
        Log.i(TAG, message);
        try {
            File folder = getExternalFilesDir(null);
            if (folder == null) folder = getFilesDir();
            try (FileOutputStream out = new FileOutputStream(new File(folder, "startup.log"), append)) {
                out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not write startup.log", error);
        }
    }

    @Override
    public void loadLibraries() {
        startupTrace("Loading native libraries", true);
        try {
            super.loadLibraries();
            startupTrace("Native libraries loaded", true);
        } catch (UnsatisfiedLinkError error) {
            startupTrace("Native load failed: " + error.getMessage(), true);
            throw error;
        } catch (RuntimeException error) {
            startupTrace("Native load failed: " + error.getMessage(), true);
            throw error;
        }
    }

    // The launcher and both game engines share one activity. Keep its orientation tied to
    // the current screen, including when SDL recreates its window with a stale size/hint.
    private static volatile boolean playingGame;
    // A launcher page (Settings, Launcher, ...) is shown sideways, but it is still the launcher: it
    // turns the screen, not the camera cutout, to the game's way.
    private static volatile boolean landscapeOnly;
    // Launcher > Camera Cutout: ON keeps the launcher's screens clear of the camera hole.
    private static volatile boolean avoidCutout = true;
    private final ContentObserver rotationObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { applyScreenOrientation(); }
    };

    // The launcher keeps clear of a camera hole or notch (the system leaves that strip black); a
    // game runs across the whole screen. SDL asks for the whole screen itself, so this is applied
    // again whenever the window gains focus.
    private void applyCutoutMode() {
        if (Build.VERSION.SDK_INT < 28) return;
        runOnUiThread(() -> {
            try {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                int want = playingGame || !avoidCutout
                    ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
                if (lp.layoutInDisplayCutoutMode != want) {
                    lp.layoutInDisplayCutoutMode = want;
                    getWindow().setAttributes(lp);
                }
            } catch (Exception ignored) { }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyCutoutMode();
    }

    private void applyScreenOrientation() {
        applyCutoutMode();
        runOnUiThread(() -> {
            boolean autoRotate = Settings.System.getInt(getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0) != 0;
            setRequestedOrientation(playingGame || landscapeOnly
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : autoRotate ? ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                             : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        });
    }

    public static void showLauncherOrientation() {
        playingGame = false;
        landscapeOnly = false;
        setDsRunning(false);
        if (self != null) self.applyScreenOrientation();
    }

    public static void showGameOrientation() {
        playingGame = true;
        if (self != null) self.applyScreenOrientation();
    }

    // A page of the launcher opened on an upright phone: sideways, with the cutout kept as the launcher has it.
    public static void showPageOrientation() {
        playingGame = false;
        landscapeOnly = true;
        if (self != null) self.applyScreenOrientation();
    }

    public static void setAvoidCutout(boolean avoid) {
        avoidCutout = avoid;
        if (self != null) self.applyCutoutMode();
    }

    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        applyScreenOrientation();
    }

    // CHECK FOR UPDATES on the game list's settings page (Updates).
    public static void checkUpdates() {
        if (self != null) Updates.check(self);
    }

    // At launch: a dialog only when a new version is available.
    public static void checkUpdatesQuiet() {
        if (self != null) Updates.check(self, true);
    }

    private static SharedPreferences prefs() {
        return self.getSharedPreferences("settings", MODE_PRIVATE);
    }

    // The ROM folder's path, "" until one is chosen.
    public static String romFolder() {
        return self == null ? "" : prefs().getString("roms", "");
    }

    public static boolean canUseFiles() {
        if (self == null) return false;
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return self.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean picking() {
        return picking;
    }

    // Asks for access to files if the app does not have it yet, then shows the folder picker.
    public static void chooseFolder() {
        if (self == null || picking) return;
        picking = true;
        self.runOnUiThread(() -> self.nextStep());
    }

    private void nextStep() {
        if (!canUseFiles()) {
            if (waitingForAccess) {
                // (Came back without allowing it.)
                waitingForAccess = false;
                picking = false;
                return;
            }
            waitingForAccess = true;
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } else {
                    requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE }, ASK_FILES);
                }
            } catch (Exception e) {
                Log.e(TAG, "asking for access to files", e);
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            return;
        }
        waitingForAccess = false;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        pick.putExtra("android.content.extra.SHOW_ADVANCED", true);
        try {
            startActivityForResult(pick, PICK_FOLDER);
        } catch (Exception e) {
            Log.e(TAG, "no folder picker", e);
            picking = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (secondScreen != null) secondScreen.resumed(true);
        applyScreenOrientation();
        // Back from Android's "All files access" page.
        if (picking && waitingForAccess) nextStep();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (secondScreen != null) secondScreen.refresh();
    }

    @Override
    protected void onPause() {
        if (secondScreen != null) secondScreen.resumed(false);
        super.onPause();
    }

    public static long secondScreenState() {
        GameActivity activity = self;
        return activity == null || activity.secondScreen == null ? 0 : activity.secondScreen.state();
    }
    public static void setSecondScreenMode(int mode) {
        GameActivity activity = self;
        if (activity != null && activity.secondScreen != null) activity.secondScreen.setMode(mode);
    }
    public static void setDsRunning(boolean running) {
        GameActivity activity = self;
        if (activity != null && activity.secondScreen != null) activity.secondScreen.running(running);
    }
    public static void submitDsFrame(int[] pixels,int width,int height) {
        GameActivity activity = self;
        if (activity != null && activity.secondScreen != null) activity.secondScreen.submit(pixels,width,height);
    }

    @Override
    protected void onDestroy() {
        if (secondScreen != null) secondScreen.close();
        getContentResolver().unregisterContentObserver(rotationObserver);
        if (self == this) self = null;
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == ASK_FILES) {
            if (picking && waitingForAccess) nextStep();
            return;
        }
        super.onRequestPermissionsResult(request, permissions, results);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == PICK_FOLDER) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                String path = pathOf(data.getData());
                Log.i(TAG, "ROM folder: " + path);
                if (path != null) prefs().edit().putString("roms", path).apply();
            }
            picking = false;
            return;
        }
        super.onActivityResult(request, result, data);
    }

    // The file path of a folder chosen in the picker: its tree id is "<volume>:<path>", e.g.
    // "primary:Download/ROMs" (the phone's own storage) or "1A2B-3C4D:ROMs" (an SD card).
    static String pathOf(Uri tree) {
        if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) return null;
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception e) {
            return null;
        }
        int colon = id.indexOf(':');
        String volume = colon < 0 ? id : id.substring(0, colon);
        String rel = colon < 0 ? "" : id.substring(colon + 1);
        String base = volume.equalsIgnoreCase("primary") ? Environment.getExternalStorageDirectory().getPath()
                : "/storage/" + volume;
        try {
            java.io.File root = new java.io.File(base).getCanonicalFile();
            java.io.File folder = new java.io.File(root, rel).getCanonicalFile();
            if (!folder.equals(root) && !folder.getPath().startsWith(root.getPath() + "/")) return null;
            return folder.isDirectory() && folder.canRead() ? folder.getPath() : null;
        } catch (java.io.IOException e) { return null; }
    }

    // Starts the app again in a new process (Restart, in a process of its own, ends this one
    // and opens the app).
    public static void restart() {
        if (self == null) return;
        Intent i = new Intent(self, Restart.class);
        i.putExtra("pid", Process.myPid());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        self.startActivity(i);
    }
}
