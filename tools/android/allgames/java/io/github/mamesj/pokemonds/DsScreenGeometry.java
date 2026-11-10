package io.github.mamesj.pokemonds;

// Coordinates shared by the secondary view and host-side tests. Black borders never touch the DS.
final class DsScreenGeometry {
    static final int WIDTH = 256, HEIGHT = 192;
    static float scale(int width, int height) {
        return scale(width, height, WIDTH, HEIGHT);
    }
    // As large as fits (the Thor's lower screen, 1240 x 1080: 4.84, its whole width); the
    // view keeps it sharp by scaling in two steps (SecondScreen.ScreenView).
    static float scale(int width, int height, int sourceWidth, int sourceHeight) {
        return Math.min(width / (float) sourceWidth, height / (float) sourceHeight);
    }
    static int touch(int width, int height, float x, float y) {
        return touch(width,height,x,y,WIDTH,HEIGHT);
    }
    static int touch(int width,int height,float x,float y,int sourceWidth,int sourceHeight) {
        float scale = scale(width, height, sourceWidth, sourceHeight);
        if (scale <= 0) return 0;
        float left = (width - sourceWidth * scale) / 2f;
        float top = (height - sourceHeight * scale) / 2f;
        if (x < left || x >= left + sourceWidth * scale || y < top || y >= top + sourceHeight * scale) return 0;
        int tx = Math.min(sourceWidth - 1, (int) ((x - left) / scale));
        int ty = Math.min(sourceHeight - 1, (int) ((y - top) / scale));
        return (1 << 24) | (ty << 12) | tx;
    }
}
