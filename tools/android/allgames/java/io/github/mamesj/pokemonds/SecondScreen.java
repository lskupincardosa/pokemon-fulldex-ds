package io.github.mamesj.pokemonds;

import android.app.Presentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

// One DS session, two displays. The UI owns windows; the SDL thread only submits a bounded
// latest-frame mailbox and polls an atomic input snapshot. No game or save data is stored here.
final class SecondScreen implements DisplayManager.DisplayListener {
    static final int OFF = 0, MERGED_TOUCH = 1, PHYSICAL_DS = 2, SWAPPED_DS = 3, COMPANION = 4;
    private final GameActivity host;
    private final DisplayManager displays;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object pixelsLock = new Object();
    private int[] pixels = new int[256 * 192];
    private volatile int sourceWidth=256,sourceHeight=192;
    private volatile int mode;
    private volatile int touch;
    private volatile boolean ready, haveFrame;
    private volatile ScreenView view;
    private boolean running, resumed, closed;
    private Presentation presentation;
    private int failedDisplay = -1;

    SecondScreen(GameActivity host) {
        this.host = host;
        displays = (DisplayManager) host.getSystemService(GameActivity.DISPLAY_SERVICE);
        // (Default EXTRA TOUCH: the Thor build always uses it, and sets it from the game too.)
        mode = host.getSharedPreferences("settings", 0).getInt("ds_second_screen", MERGED_TOUCH);
        if (mode < OFF || mode > COMPANION) mode = OFF;
        if (displays != null) displays.registerDisplayListener(this, ui);
    }
    int mode() { return mode; }
    long state() { return ((long) mode << 28) | (ready ? 1L << 32 : 0) | (view != null ? 1L << 33 : 0) | touch; }
    void setMode(int next) {
        touch=0;
        mode = Math.max(OFF, Math.min(COMPANION, next));
        ui.post(() -> {
            if (closed) return;
            host.getSharedPreferences("settings", 0).edit().putInt("ds_second_screen", mode).apply();
            failedDisplay = -1;
            sync();
        });
    }
    void running(boolean on) {
        ui.post(() -> { if (!closed) { running = on; touch = 0; sync(); } });
    }
    void resumed(boolean on) {
        resumed = on;
        if (!on) touch = 0;
        sync();
    }
    void submit(int[] frame,int width,int height) {
        ScreenView target = view;
        if (target == null || frame == null || width<1 || height<1 || width>1024 || height>768 || frame.length!=width*height) return;
        synchronized (pixelsLock) {
            if (target != view) return;
            if(pixels.length!=frame.length)pixels=new int[frame.length];
            sourceWidth=width;sourceHeight=height;
            System.arraycopy(frame, 0, pixels, 0, pixels.length);
            haveFrame = true;
        }
        target.postInvalidateOnAnimation();
    }
    void refresh() { touch = 0; sync(); }
    void close() {
        closed = true;
        if (displays != null) displays.unregisterDisplayListener(this);
        dismiss();
        ui.removeCallbacksAndMessages(null);
    }
    private int hostDisplayId() {
        return Build.VERSION.SDK_INT >= 30 && host.getDisplay() != null
            ? host.getDisplay().getDisplayId() : host.getWindowManager().getDefaultDisplay().getDisplayId();
    }
    private boolean usable(Display display) {
        return display.isValid() && display.getDisplayId() != hostDisplayId()
            && (display.getFlags() & Display.FLAG_PRIVATE) == 0;
    }
    private Display target() {
        if (displays == null) return null;
        for (Display display : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
            if (usable(display)) return display;
        for (Display display : displays.getDisplays()) if (usable(display)) return display;
        return null;
    }
    private void dismiss() {
        ready = false;
        touch = 0;
        view = null;
        synchronized (pixelsLock) { haveFrame = false; java.util.Arrays.fill(pixels, Color.BLACK); }
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
    }
    private void sync() {
        Display target = !closed && running && resumed && mode != OFF ? target() : null;
        if (target == null) { dismiss(); return; }
        if (presentation != null && presentation.isShowing()
            && presentation.getDisplay().getDisplayId() == target.getDisplayId()) return;
        dismiss();
        if (target.getDisplayId() == failedDisplay) return;
        try {
            Presentation next = new Presentation(host, target) {
                @Override protected void onCreate(Bundle state) {
                    super.onCreate(state);
                    ScreenView screen = new ScreenView(getContext());
                    setContentView(screen);
                    Window window = getWindow();
                    if (window != null) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                        GameActivity.preferSixtyHz(window, getDisplay());
                        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                    }
                    view = screen;
                }
                @Override public boolean dispatchKeyEvent(KeyEvent event) { return host.dispatchKeyEvent(event); }
                @Override protected void onStop() {
                    ready = false;
                    touch = 0;
                    view = null;
                    super.onStop();
                }
            };
            presentation = next;
            next.show();
            Log.i("FulldexDisplays", "DS touchscreen on " + target.getName() + " (" + target.getDisplayId() + ")");
        } catch (RuntimeException error) {
            failedDisplay = target.getDisplayId();
            dismiss();
            Log.w("FulldexDisplays", "Second display unavailable; retaining the normal layout", error);
        }
    }
    @Override public void onDisplayAdded(int id) { failedDisplay = -1; sync(); }
    @Override public void onDisplayRemoved(int id) { failedDisplay = -1; touch = 0; sync(); }
    @Override public void onDisplayChanged(int id) { failedDisplay = -1; touch = 0; sync(); }

    private final class ScreenView extends View {
        private Bitmap bitmap = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888);
        // (No filtering: the DS's pixels scaled by a whole number stay sharp. Android's default
        // Paint filters bitmaps, which blurred them.)
        private final Paint paint = new Paint();
        { paint.setFilterBitmap(false); paint.setAntiAlias(false); }
        // "Sharp bilinear": the picture is first made a whole number of times larger with hard
        // pixels (big), then fitted to the display with filtering, so only the seams between
        // pixels are blended, by under a display pixel. It fills the display's width without
        // the uneven pixel sizes of a plain fractional scale or the blur of filtering the DS
        // picture itself.
        private final Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap big;
        private Canvas bigCanvas;
        private int pointer = -1;
        ScreenView(android.content.Context context) { super(context); setBackgroundColor(Color.BLACK); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width,height;
            synchronized (pixelsLock) {
                width=sourceWidth;height=sourceHeight;
                if(bitmap.getWidth()!=width||bitmap.getHeight()!=height)bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                bitmap.setPixels(pixels,0,width,0,0,width,height);
            }
            float scale=DsScreenGeometry.scale(getWidth(),getHeight(),width,height);
            float left=(getWidth()-width*scale)/2f,top=(getHeight()-height*scale)/2f;
            canvas.drawColor(Color.BLACK);
            final float right = left + width * scale, bottom = top + height * scale;
            final int k = (int) Math.ceil(scale - 0.001f);
            if (scale == k) canvas.drawBitmap(bitmap, null, new RectF(left, top, right, bottom), paint);
            else {
                if (big == null || big.getWidth() != width * k || big.getHeight() != height * k) {
                    big = Bitmap.createBitmap(width * k, height * k, Bitmap.Config.ARGB_8888);
                    bigCanvas = new Canvas(big);
                }
                bigCanvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, width * k, height * k), paint);
                canvas.drawBitmap(big, null, new RectF(left, top, right, bottom), smooth);
            }
            // The space around the picture (the Thor's lower screen is narrower than 4:3, and the
            // picture is a whole number of pixels per DS pixel) continues its edges instead of
            // black bars: each row's outermost pixel out to the side, the top and bottom rows up
            // and down, the corner pixels into the corners. The DS's screens mostly end in a
            // plain colour or a gentle pattern, so the display reads as one screen.
            final int W = width, H = height;
            final float cw = getWidth(), ch = getHeight();
            if (left > 0) {
                canvas.drawBitmap(bitmap, new android.graphics.Rect(0, 0, 1, H), new RectF(0, top, left, bottom), paint);
                canvas.drawBitmap(bitmap, new android.graphics.Rect(W - 1, 0, W, H), new RectF(right, top, cw, bottom), paint);
            }
            if (top > 0) {
                canvas.drawBitmap(bitmap, new android.graphics.Rect(0, 0, W, 1), new RectF(left, 0, right, top), paint);
                canvas.drawBitmap(bitmap, new android.graphics.Rect(0, H - 1, W, H), new RectF(left, bottom, right, ch), paint);
                if (left > 0) {
                    canvas.drawBitmap(bitmap, new android.graphics.Rect(0, 0, 1, 1), new RectF(0, 0, left, top), paint);
                    canvas.drawBitmap(bitmap, new android.graphics.Rect(W - 1, 0, W, 1), new RectF(right, 0, cw, top), paint);
                    canvas.drawBitmap(bitmap, new android.graphics.Rect(0, H - 1, 1, H), new RectF(0, bottom, left, ch), paint);
                    canvas.drawBitmap(bitmap, new android.graphics.Rect(W - 1, H - 1, W, H), new RectF(right, bottom, cw, ch), paint);
                }
            }
            ready = this == view && haveFrame;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (mode == SWAPPED_DS) { touch = 0; pointer = -1; return true; }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) pointer = event.getPointerId(0);
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP
                || (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) {
                touch = 0; pointer = -1; return true;
            }
            int index = event.findPointerIndex(pointer);
            touch = index < 0 ? 0 : DsScreenGeometry.touch(getWidth(), getHeight(), event.getX(index), event.getY(index),sourceWidth,sourceHeight);
            return true;
        }
        @Override protected void onDetachedFromWindow() {
            touch = 0; pointer = -1; super.onDetachedFromWindow();
        }
    }
}
