package mx.nico.sharkhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Marco de fotos: pasa las imágenes de una carpeta (jpg/png) con fundido, cada pocos segundos.
 * Framework puro. Decodifica reducido para no gastar memoria en la troca.
 */
public class PhotoSlideshow extends FrameLayout {
    long everyMs = 10000;     // tiempo por imagen (0 = foto estática, no cambia)

    final ImageView imgA, imgB;
    ImageView front;                 // la que se ve ahora
    final TextView empty;
    final Handler h = new Handler(Looper.getMainLooper());
    final List<String> files = new ArrayList<String>();
    int idx = -1;
    boolean running = false;
    String dir;

    public PhotoSlideshow(Context c) {
        super(c);
        setBackgroundColor(0xFF0C0E12);
        imgA = newImg(c); imgB = newImg(c);
        addView(imgA); addView(imgB);
        imgB.setAlpha(0f);
        front = imgA;
        empty = new TextView(c);
        empty.setText("Pon tus fotos en:\n/sdcard/SharkHome/fotos");
        empty.setTextColor(0xFFAAB0B8);
        empty.setTextSize(16);
        empty.setGravity(android.view.Gravity.CENTER);
        addView(empty, new FrameLayout.LayoutParams(-1, -1));
        empty.setVisibility(GONE);
        setOnClickListener(new OnClickListener() {
            public void onClick(View v) { showNext(); }   // tocar = siguiente foto
        });
    }

    ImageView newImg(Context c) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addViewParams(iv);
        return iv;
    }
    void addViewParams(View v) { v.setLayoutParams(new FrameLayout.LayoutParams(-1, -1)); }

    /** Carpeta con las imágenes. */
    public void setFolder(String path) {
        dir = path;
        scan();
    }

    /** Segundos por imagen; 0 = foto estática (no cambia). */
    public void setIntervalSec(int s) {
        everyMs = s <= 0 ? 0 : s * 1000L;
        h.removeCallbacks(tick);
        if (running && everyMs > 0 && files.size() > 1) h.postDelayed(tick, everyMs);
    }

    void scan() {
        files.clear();
        try {
            File d = new File(dir);
            File[] fs = d.listFiles();
            if (fs != null) {
                Arrays.sort(fs);
                for (File f : fs) {
                    String n = f.getName().toLowerCase();
                    if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg")
                            || n.endsWith(".png") || n.endsWith(".webp")))
                        files.add(f.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) {}
        idx = -1;
        empty.setVisibility(files.isEmpty() ? VISIBLE : GONE);
    }

    public void start() {
        if (running) return;
        running = true;
        scan();                 // re-escanear por si agregó fotos
        if (!files.isEmpty()) showNext();
    }

    public void stop() {
        running = false;
        h.removeCallbacks(tick);
    }

    final Runnable tick = new Runnable() { public void run() { showNext(); } };

    void showNext() {
        if (files.isEmpty()) { empty.setVisibility(VISIBLE); return; }
        idx = (idx + 1) % files.size();
        Bitmap bmp = decode(files.get(idx));
        if (bmp == null) { // archivo malo: saltar
            h.postDelayed(tick, 300);
            return;
        }
        ImageView back = (front == imgA) ? imgB : imgA;
        back.setImageBitmap(bmp);
        back.setAlpha(0f);
        back.animate().alpha(1f).setDuration(650).start();
        final ImageView old = front;
        old.animate().alpha(0f).setDuration(650).start();
        front = back;
        h.removeCallbacks(tick);
        if (running && everyMs > 0 && files.size() > 1) h.postDelayed(tick, everyMs); // 0 = estática
    }

    /** Decodifica reducido al ancho de la vista (ahorra memoria). */
    Bitmap decode(String path) {
        try {
            int w = getWidth() > 0 ? getWidth() : 1280;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) { return null; }
    }
}
