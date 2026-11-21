package mx.nico.sharkhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Muestra la letra sincronizada y resalta la línea que va sonando, centrándola con un
 * desplazamiento suave. Framework puro, sin librerías. No scroll táctil: sigue a la canción.
 */
public class LyricsView extends View {

    final TextPaint active = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint idle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint hint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final float d;

    List<Lyrics.Line> lines = new ArrayList<Lyrics.Line>();
    List<StaticLayout> layouts = new ArrayList<StaticLayout>();
    int activeIdx = -1;
    boolean synced = false;
    String message = "";       // cuando no hay letra
    float scrollY = 0, targetY = 0;
    int lineGap;
    int activeColor = 0xFFFFFFFF, idleColor = 0xFF6E7278;
    static final float FOCUS = 0.22f;   // la línea que canta queda casi arriba (22% desde el tope)

    public LyricsView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        lineGap = Math.round(12 * d);
        active.setColor(activeColor);
        active.setTextSize(26 * d);  // grande: ocupa todo el panel izquierdo
        active.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        idle.setColor(idleColor);
        idle.setTextSize(22 * d);
        hint.setColor(0xFF8A8D93);
        hint.setTextSize(16 * d);
        hint.setTextAlign(Paint.Align.CENTER);
        message = "";
    }

    /** Ajusta los colores al tema claro/oscuro. */
    public void setLight(boolean lightTheme) {
        activeColor = lightTheme ? 0xFF14151A : 0xFFFFFFFF;
        idleColor = lightTheme ? 0xFF787E86 : 0xFF6E7278;
        hint.setColor(lightTheme ? 0xFF6B6F76 : 0xFF8A8D93);
        invalidate();
    }

    public void setMessage(String m) {
        message = m == null ? "" : m;
        lines = new ArrayList<Lyrics.Line>();
        layouts = new ArrayList<StaticLayout>();
        activeIdx = -1;
        scrollY = targetY = 0;
        invalidate();
    }

    public void setLines(List<Lyrics.Line> l, boolean syncedFlag) {
        lines = (l == null) ? new ArrayList<Lyrics.Line>() : l;
        synced = syncedFlag;
        activeIdx = -1;
        scrollY = targetY = 0;
        message = lines.isEmpty() ? "Sin letra para esta canción" : "";
        buildLayouts();
        invalidate();
    }

    public boolean isSynced() { return synced && !lines.isEmpty(); }
    public boolean hasLyrics() { return !lines.isEmpty(); }

    public void setActiveIndex(int idx) {
        if (idx == activeIdx) return;
        activeIdx = idx;
        if (idx >= 0 && idx < layouts.size()) targetY = centerOf(idx);
        postInvalidateOnAnimation();
    }

    void buildLayouts() {
        layouts = new ArrayList<StaticLayout>();
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        if (w <= 0) return; // se reconstruye en onSizeChanged
        for (Lyrics.Line ln : lines) {
            String t = ln.text == null ? "" : ln.text;
            if (t.isEmpty()) t = "•"; // pausa instrumental: un puntito
            layouts.add(makeLayout(t, w));
        }
    }

    StaticLayout makeLayout(String text, int w) {
        // Un tamaño para todos (el activo solo cambia color/negrita) para no recalcular al cambiar de línea.
        return new StaticLayout(text, active, w, Layout.Alignment.ALIGN_CENTER, 1.0f, 0f, false);
    }

    float heightOf(int i) { return layouts.get(i).getHeight() + lineGap; }

    /** Desplazamiento para que la línea i quede casi arriba (en FOCUS de la altura). */
    float centerOf(int i) {
        float y = 0;
        for (int k = 0; k < i; k++) y += heightOf(k);
        y += layouts.get(i).getHeight() / 2f;
        return y - getHeight() * FOCUS;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        buildLayouts();
        if (activeIdx >= 0 && activeIdx < layouts.size()) scrollY = targetY = centerOf(activeIdx);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (layouts.isEmpty()) {
            String m = message.isEmpty() ? "" : message;
            if (!m.isEmpty()) c.drawText(m, w / 2f, h / 2f, hint);
            return;
        }
        // Desplazamiento suave hacia la línea activa
        float diff = targetY - scrollY;
        if (Math.abs(diff) > 0.5f) {
            scrollY += diff * 0.18f;
            postInvalidateOnAnimation();
        } else {
            scrollY = targetY;
        }

        float y = getPaddingTop() - scrollY;
        for (int i = 0; i < layouts.size(); i++) {
            StaticLayout L = layouts.get(i);
            float lh = L.getHeight();
            if (y + lh >= 0 && y <= h) {
                boolean isActive = (i == activeIdx);
                // Difuminar las lejanas: alfa por distancia al punto de foco (casi arriba)
                float focusY = h * FOCUS;
                float centerDist = Math.abs((y + lh / 2f) - focusY) / Math.max(1f, h * 0.55f);
                int alpha = (int) (255 * Math.max(0.25f, 1f - centerDist * 0.7f));
                c.save();
                c.translate(getPaddingLeft(), y);
                if (isActive && synced) {
                    // La línea que canta: más grande (escala) + negrita de verdad.
                    float sc = 1.2f, pivot = (w - getPaddingLeft() - getPaddingRight()) / 2f;
                    c.scale(sc, sc, pivot, lh / 2f);
                    L.getPaint().setColor(activeColor);
                    L.getPaint().setFakeBoldText(true);
                    L.draw(c);
                    L.getPaint().setFakeBoldText(false);
                } else {
                    int a = synced ? alpha : 210;
                    L.getPaint().setColor((idleColor & 0x00FFFFFF) | (a << 24));
                    L.draw(c);
                }
                c.restore();
            }
            y += lh + lineGap;
        }
    }
}
