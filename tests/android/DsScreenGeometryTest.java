package io.github.mamesj.pokemonds;

public final class DsScreenGeometryTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        int[][] displays = {{1080,1240},{1240,1080},{1280,960},{720,1280},{1920,1080},{256,192},{640,480}};
        for (int[] size : displays) {
            int w=size[0],h=size[1];
            float s=DsScreenGeometry.scale(w,h),left=(w-256*s)/2,top=(h-192*s)/2;
            check(DsScreenGeometry.touch(w,h,left,top)==(1<<24));
            check(DsScreenGeometry.touch(w,h,left+256*s,top)==0);
            check(DsScreenGeometry.touch(w,h,left,top+192*s)==0);
            check(DsScreenGeometry.touch(w,h,left-0.01f,top)==0);
            check(DsScreenGeometry.touch(w,h,left,top-0.01f)==0);
            // Every emulated pixel is independently addressable at its centre.
            for(int y=0;y<192;y++) for(int x=0;x<256;x++) {
                int got=DsScreenGeometry.touch(w,h,left+(x+0.5f)*s,top+(y+0.5f)*s);
                check(got==((1<<24)|(y<<12)|x));
            }
        }
        for(int[] size:displays) {
            int w=size[0],h=size[1];
            float scale=DsScreenGeometry.scale(w,h,640,480);
            float left=(w-640*scale)/2,top=(h-480*scale)/2;
            for(int y=0;y<480;y++)for(int x=0;x<640;x++)
                check(DsScreenGeometry.touch(w,h,left+(x+.5f)*scale,top+(y+.5f)*scale,640,480)==((1<<24)|(y<<12)|x));
            check(DsScreenGeometry.touch(w,h,left+640*scale,top,640,480)==0);
            check(DsScreenGeometry.touch(w,h,left,top+480*scale,640,480)==0);
        }
        // The Thor's lower display (landscape, 1240 x 1080): the DS screen fills its width.
        check(DsScreenGeometry.scale(1240,1080)==1240f/256);
        check(DsScreenGeometry.touch(0,0,0,0)==0);
        check(DsScreenGeometry.touch(256,0,0,0)==0);
        System.out.println("PASS: DS/companion pixel centres across seven display sizes; borders, corners and zero sizes");
    }
}
