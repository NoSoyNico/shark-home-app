package mx.nico.sharkhome;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Prueba de diagnóstico TEMPORAL: muestra la cámara indicada a pantalla completa unos segundos,
 * mide el brillo de los cuadros y guarda uno como JPEG en la carpeta privada de la app; luego suelta
 * la cámara y quita la vista. Solo corre con `am start ... --ei camtest <id>`.
 */
public class CamTest {
    static final String TAG = "SharkCam";

    static void run(final Activity act, final int camId) {
        final FrameLayout root = (FrameLayout) act.getWindow().getDecorView().findViewById(android.R.id.content);
        final SurfaceView sv = new SurfaceView(act);
        root.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            Camera cam;

            public void surfaceCreated(SurfaceHolder h) {
                try {
                    Log.i(TAG, "abriendo id=" + camId);
                    cam = Camera.open(camId);
                    final Camera.Size s = cam.getParameters().getPreviewSize();
                    Log.i(TAG, "preview " + s.width + "x" + s.height);
                    cam.setPreviewDisplay(h);
                    final int[] n = {0};
                    cam.setPreviewCallback(new Camera.PreviewCallback() {
                        public void onPreviewFrame(byte[] data, Camera c) {
                            n[0]++;
                            int w = s.width, hh = s.height;
                            if (n[0] % 30 == 0 && n[0] <= 150) {
                                long sum = 0, sq = 0; int min = 255, max = 0, cnt = 0;
                                for (int y = 0; y < hh; y += 4) for (int x = 0; x < w; x += 4) {
                                    int v = data[y * w + x] & 0xFF;
                                    sum += v; sq += (long) v * v; cnt++;
                                    if (v < min) min = v; if (v > max) max = v;
                                }
                                double mean = sum / (double) cnt;
                                Log.i(TAG, String.format("cuadro %d: brillo=%.1f desv=%.1f min=%d max=%d",
                                        n[0], mean, Math.sqrt(sq / (double) cnt - mean * mean), min, max));
                            }
                            if (n[0] == 60) {
                                try {
                                    File f = new File(act.getExternalFilesDir(null), "cam" + camId + ".jpg");
                                    FileOutputStream o = new FileOutputStream(f);
                                    new YuvImage(data, ImageFormat.NV21, w, hh, null).compressToJpeg(new Rect(0, 0, w, hh), 85, o);
                                    o.close();
                                    Log.i(TAG, "guardado " + f.getAbsolutePath());
                                } catch (Throwable e) { Log.w(TAG, "jpeg: " + e); }
                            }
                            if (n[0] == 300) stop(); // ~10 s
                        }
                    });
                    cam.startPreview();
                    Log.i(TAG, "preview iniciado");
                    sv.postDelayed(new Runnable() { public void run() { stop(); } }, 20000); // por si no llegan cuadros
                } catch (Throwable e) {
                    Log.w(TAG, "error: " + e);
                    stop();
                }
            }

            void stop() {
                if (cam != null) {
                    try { cam.setPreviewCallback(null); cam.stopPreview(); } catch (Throwable ignored) {}
                    cam.release();
                    cam = null;
                    Log.i(TAG, "cámara liberada");
                }
                sv.post(new Runnable() { public void run() { root.removeView(sv); } });
            }

            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
            public void surfaceDestroyed(SurfaceHolder h) { stop(); }
        });
    }
}
