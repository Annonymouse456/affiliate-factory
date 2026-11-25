package th.car.display;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * One still frame of the screen, used to show Claude what the dashcam app is displaying.
 * The dashcam cannot be read directly on this head unit, so the picture is taken from the screen.
 * Android asks the driver for permission once per app start.
 */
final class ScreenGrab {

    private static MediaProjection projection;

    static boolean ready() { return projection != null; }

    /** Call with the result of the system's screen-capture consent dialog. */
    static void allow(Context c, int resultCode, Intent data) {
        try {
            MediaProjectionManager m = (MediaProjectionManager) c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(resultCode, data);
        } catch (Throwable e) { projection = null; }
    }

    /** Blocking, off the main thread. Returns a base64 JPEG about 768 px wide, or null if no frame arrived. */
    static String jpegBase64(Context c) throws Exception {
        if (projection == null) return null;
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int w = dm.widthPixels, h = dm.heightPixels;
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        VirtualDisplay display = projection.createVirtualDisplay("car-look", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
        try {
            Image image = null;
            for (int i = 0; i < 40 && image == null; i++) {      // up to two seconds for the first frame
                Thread.sleep(50);
                image = reader.acquireLatestImage();
            }
            if (image == null) return null;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer pixels = plane.getBuffer();
            int rowWidth = plane.getRowStride() / plane.getPixelStride();      // rows may be padded wider than the screen
            Bitmap padded = Bitmap.createBitmap(rowWidth, h, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(pixels);
            image.close();
            Bitmap screen = Bitmap.createBitmap(padded, 0, 0, w, h);
            int outW = Math.min(768, w), outH = Math.round(h * (outW / (float) w));
            Bitmap small = Bitmap.createScaledBitmap(screen, outW, outH, true);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 72, jpeg);
            return Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP);
        } finally {
            display.release();
            reader.close();
        }
    }

    private ScreenGrab() { }
}
