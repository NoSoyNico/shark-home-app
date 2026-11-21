package mx.nico.sharkhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/**
 * Capa de efectos de clima sobre el tablero (transparente, NO intercepta toques).
 * Lluvia/llovizna/chubascos (gotas inclinadas según la velocidad), nieve, niebla, nubes,
 * sol (resplandor cálido) y de noche despejado unas estrellas. Tormenta = lluvia + relámpagos.
 * Dibuja con Canvas y se anima con Choreographer; se puede apagar desde Ajustes.
 */
public class WeatherFx extends View {
    public static final int NONE = 0, RAIN = 1, SNOW = 2, SUN = 3, CLOUDS = 4, FOG = 5, STARS = 6;

    final Random rnd = new Random();
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final float dens;

    int mode = NONE;
    float intensity = 1f;     // 0.4 llovizna … 1.4 chubasco/tormenta
    float userDensity = 1f;   // control del usuario: 0.55 baja · 1.0 media · 1.6 alta
    boolean storm = false;    // relámpagos
    volatile float speedKmh = 0f;
    boolean running = false;
    long lastFrame = 0L;

    // Partículas (lluvia/nieve). Reusamos un arreglo grande y usamos sólo las que tocan según intensidad.
    static final int MAX = 340;
    final float[] px = new float[MAX], py = new float[MAX], pz = new float[MAX]; // pz = tamaño/profundidad
    final float[] ph = new float[MAX];                                           // fase (nieve: vaivén)
    int count = 0;

    // Nubes
    final float[] cx = new float[4], cy = new float[4], cr = new float[4], cs = new float[4];
    int clouds = 0;

    // Estrellas
    final float[] sx = new float[60], sy = new float[60], stw = new float[60];
    int stars = 0;

    // Gotas pegadas al "vidrio" del panel (se quedan y escurren sobre el reloj/letras). Solo en modo panel.
    public boolean glass = false;
    /** Fondo claro (tema claro): nubes/niebla se pintan gris azulado oscuro para que SÍ se vean. */
    public boolean lightBg = false;
    static final int GMAX = 46;
    final float[] gx = new float[GMAX], gy = new float[GMAX], gr = new float[GMAX],
            gv = new float[GMAX], gtrail = new float[GMAX];
    int gCount = 0;

    // Relámpago
    long flashUntil = 0L;
    long nextFlash = 0L;

    public WeatherFx(Context c) {
        super(c);
        dens = getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
    }

    /** No consumir toques: dejar pasar todo al mapa/dock/panel de abajo. */
    @Override public boolean onTouchEvent(android.view.MotionEvent e) { return false; }

    /** Modo según el código WMO de Open-Meteo y si es de día. */
    public void setWeather(int wmoCode, boolean day) {
        int m; float inten = 1f; boolean st = false;
        if (wmoCode == 0)                         { m = day ? SUN : STARS; }
        else if (wmoCode == 1 || wmoCode == 2)    { m = day ? SUN : STARS; }   // algo nublado: sol/estrellas tenue
        else if (wmoCode == 3)                    { m = CLOUDS; }
        else if (wmoCode == 45 || wmoCode == 48)  { m = FOG; }
        else if (wmoCode >= 51 && wmoCode <= 57)  { m = RAIN; inten = 0.5f; }  // llovizna
        else if (wmoCode >= 61 && wmoCode <= 67)  { m = RAIN; inten = 1f; }    // lluvia
        else if (wmoCode >= 71 && wmoCode <= 77)  { m = SNOW; inten = 1f; }    // nieve
        else if (wmoCode >= 80 && wmoCode <= 82)  { m = RAIN; inten = 1.3f; }  // chubascos
        else if (wmoCode >= 95)                   { m = RAIN; inten = 1.3f; st = true; } // tormenta
        else                                      { m = NONE; }
        applyMode(m, inten, st);
    }

    /** Forzar un modo para probar (desde am start --es demofx rain|snow|sun|clouds|fog|stars|storm). */
    public void setDemo(String name) {
        if (name == null) return;
        name = name.toLowerCase();
        if (name.equals("rain"))        applyMode(RAIN, 1f, false);
        else if (name.equals("drizzle"))applyMode(RAIN, 0.5f, false);
        else if (name.equals("storm"))  applyMode(RAIN, 1.3f, true);
        else if (name.equals("snow"))   applyMode(SNOW, 1f, false);
        else if (name.equals("sun"))    applyMode(SUN, 1f, false);
        else if (name.equals("clouds")) applyMode(CLOUDS, 1f, false);
        else if (name.equals("fog"))    applyMode(FOG, 1f, false);
        else if (name.equals("stars"))  applyMode(STARS, 1f, false);
    }

    void applyMode(int m, float inten, boolean st) {
        this.mode = m; this.intensity = inten; this.storm = st;
        if (getWidth() > 0) seed();
        maybeRun();
        invalidate();
    }

    public void setSpeed(float kmh) { this.speedKmh = kmh < 0 ? 0 : kmh; }

    public void setDensity(float d) {
        this.userDensity = d < 0.2f ? 0.2f : (d > 2f ? 2f : d);
        if (getWidth() > 0) seed();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        seed();
    }

    /** Coloca las partículas iniciales según el modo y el tamaño de la vista. */
    void seed() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (mode == RAIN || mode == SNOW) {
            // Cantidad PROPORCIONAL AL ÁREA de la vista: así el panel (más chico) lleva menos gotas y
            // no se ve 3x más saturado que a pantalla completa. Por el control de densidad del usuario.
            float areaRatio = (w * (float) h) / (1920f * 1080f);
            int base = (mode == SNOW ? 110 : 210);
            count = Math.round(base * areaRatio * clampI(intensity) * userDensity);
            if (count < 8) count = 8;
            if (count > MAX) count = MAX;
            for (int i = 0; i < count; i++) {
                px[i] = rnd.nextFloat() * w;
                py[i] = rnd.nextFloat() * h;
                pz[i] = 0.5f + rnd.nextFloat();        // profundidad: afecta tamaño y velocidad
                ph[i] = rnd.nextFloat() * 6.28f;
            }
            if (glass && mode == RAIN) {
                gCount = Math.round(22 * clampI(intensity) * userDensity);
                if (gCount < 6) gCount = 6;
                if (gCount > GMAX) gCount = GMAX;
                for (int i = 0; i < gCount; i++) {
                    gx[i] = rnd.nextFloat() * w;
                    gy[i] = rnd.nextFloat() * h;
                    gr[i] = (2.4f + rnd.nextFloat() * 4.5f) * dens;
                    gv[i] = 0f; gtrail[i] = 0f;
                }
            } else gCount = 0;
        } else if (mode == CLOUDS) {
            clouds = 4;
            for (int i = 0; i < clouds; i++) {
                cr[i] = (0.22f + rnd.nextFloat() * 0.22f) * w;
                cx[i] = rnd.nextFloat() * w;
                cy[i] = (0.08f + rnd.nextFloat() * 0.33f) * h;
                cs[i] = (0.2f + rnd.nextFloat() * 0.5f) * dens; // deriva base px/s
            }
        } else if (mode == STARS) {
            stars = 48;
            for (int i = 0; i < stars; i++) {
                sx[i] = rnd.nextFloat() * w;
                sy[i] = rnd.nextFloat() * h * 0.6f;
                stw[i] = rnd.nextFloat() * 6.28f;
            }
        }
    }

    static float clampI(float v) { return v < 0.4f ? 0.4f : (v > 1.4f ? 1.4f : v); }

    // ---------- animación ----------
    void maybeRun() {
        boolean want = mode != NONE && isShown() && getVisibility() == VISIBLE;
        if (want && !running) { running = true; lastFrame = 0L; Choreographer.getInstance().postFrameCallback(frame); }
        else if (!want) { running = false; }
    }

    final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        public void doFrame(long now) {
            if (!running) return;
            // Limitar a ~30 fps: suave y más ligero para el SoC de la troca (SD665).
            if (lastFrame != 0L && (now - lastFrame) < 30_000_000L) {
                Choreographer.getInstance().postFrameCallback(this);
                return;
            }
            float dt;
            if (lastFrame == 0L) dt = 0.033f; else dt = (now - lastFrame) / 1_000_000_000f;
            if (dt > 0.08f) dt = 0.08f; // evitar saltos tras pausa
            lastFrame = now;
            step(dt);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); maybeRun(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); running = false; }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); maybeRun(); }
    @Override public void setVisibility(int v) { super.setVisibility(v); maybeRun(); }

    // Inclinación de la lluvia según la velocidad: 0 = vertical, sube con km/h (tope ~68°).
    float tilt() {
        double t = Math.atan(speedKmh / 45.0);   // 45 km/h ≈ 45°
        double max = Math.toRadians(68);
        return (float) Math.min(t, max);
    }

    void step(float dt) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (mode == RAIN) {
            float tilt = tilt();
            float speedBoost = 1f + Math.min(speedKmh / 60f, 1.6f);     // cae más rápido al avanzar
            float vy = (900f * dens) * speedBoost * (0.8f + 0.5f * intensity) / 1000f; // px por frame-ish
            float tan = (float) Math.tan(tilt);
            for (int i = 0; i < count; i++) {
                float sp = vy * (0.7f + pz[i]) * dt * 60f;
                py[i] += sp;
                px[i] += sp * tan;
                // Envoltura toroidal: la gota que sale vuelve a entrar por el lado opuesto.
                // Mantiene la densidad PAREJA en TODA la pantalla a cualquier velocidad/inclinación.
                if (py[i] > h) py[i] -= (h + 1);
                if (px[i] > w) px[i] -= (w + 1);
                else if (px[i] < 0) px[i] += (w + 1);
            }
            stepGlass(dt, w, h);
        } else if (mode == SNOW) {
            float driftBase = Math.min(speedKmh / 50f, 1.4f);
            for (int i = 0; i < count; i++) {
                ph[i] += dt * (1.2f + pz[i]);
                float sp = (70f * dens) * (0.5f + pz[i]) * dt * (0.7f + 0.4f * intensity);
                py[i] += sp;
                px[i] += ((float) Math.sin(ph[i]) * 0.6f + driftBase) * (0.5f + pz[i]) * dens * dt * 60f;
                if (py[i] > h) py[i] -= (h + 1);
                if (px[i] > w) px[i] -= (w + 1);
                else if (px[i] < 0) px[i] += (w + 1);
            }
        } else if (mode == CLOUDS) {
            float push = (0.3f + Math.min(speedKmh / 40f, 2.0f)) * dens * 14f;
            for (int i = 0; i < clouds; i++) {
                cx[i] += (cs[i] + push * 0.15f) * dt;
                if (cx[i] - cr[i] > w) cx[i] = -cr[i];
            }
        }
        // Relámpagos (tormenta)
        if (storm) {
            long t = System.currentTimeMillis();
            if (nextFlash == 0L) nextFlash = t + 3000 + rnd.nextInt(6000);
            if (t > nextFlash) {
                flashUntil = t + 120 + rnd.nextInt(120);
                nextFlash = t + 4000 + rnd.nextInt(9000);
            }
        }
    }

    /** Gotas pegadas al vidrio: casi siempre quietas; de pronto una escurre (acelera, deja rastro, encoge). */
    void stepGlass(float dt, int w, int h) {
        if (!glass || gCount == 0) return;
        for (int i = 0; i < gCount; i++) {
            if (gv[i] <= 0f) {
                // clavada: pequeña probabilidad de empezar a escurrir (más con lluvia fuerte)
                if (rnd.nextFloat() < 0.010f * (0.5f + intensity) && gr[i] > 3f * dens)
                    gv[i] = (18f + rnd.nextFloat() * 30f) * dens;
            } else {
                gv[i] += 260f * dens * dt;          // acelera al escurrir
                float d = gv[i] * dt;
                gy[i] += d;
                gtrail[i] = Math.min(gtrail[i] + d, 140f * dens);
                gr[i] *= (1f - 0.12f * dt);         // adelgaza
                if (gy[i] - gtrail[i] > h || gr[i] < 1.2f * dens) {
                    gx[i] = rnd.nextFloat() * w;
                    gy[i] = rnd.nextFloat() * h * 0.55f;
                    gr[i] = (2.4f + rnd.nextFloat() * 4.5f) * dens;
                    gv[i] = 0f; gtrail[i] = 0f;
                }
            }
        }
    }

    void drawGlass(Canvas c) {
        if (!glass || gCount == 0) return;
        for (int i = 0; i < gCount; i++) {
            float r = gr[i];
            // rastro del escurrimiento
            if (gtrail[i] > 2f) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(r * 0.9f);
                p.setColor(Color.argb(34, 205, 222, 240));
                c.drawLine(gx[i], gy[i] - gtrail[i], gx[i], gy[i], p);
            }
            // cuerpo de la gota (traslúcida, con brillo) sobre el reloj/letras del panel
            p.setStyle(Paint.Style.FILL);
            RadialGradient bead = new RadialGradient(gx[i] - r * 0.3f, gy[i] - r * 0.3f, r * 1.6f,
                    new int[]{Color.argb(150, 235, 245, 255), Color.argb(70, 180, 205, 230), Color.argb(26, 150, 180, 210)},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            p.setShader(bead);
            c.drawCircle(gx[i], gy[i], r, p);
            p.setShader(null);
            // puntito de brillo
            p.setColor(Color.argb(170, 255, 255, 255));
            c.drawCircle(gx[i] - r * 0.35f, gy[i] - r * 0.35f, r * 0.28f, p);
        }
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || mode == NONE) return;
        switch (mode) {
            case RAIN: drawRain(c, w, h); break;
            case SNOW: drawSnow(c, w, h); break;
            case SUN:  drawSun(c, w, h); break;
            case CLOUDS: drawClouds(c, w, h); break;
            case FOG:  drawFog(c, w, h); break;
            case STARS: drawStars(c, w, h); break;
        }
    }

    void drawRain(Canvas c, int w, int h) {
        float tilt = tilt();
        float tan = (float) Math.tan(tilt);
        // largo de la raya crece con la velocidad (sensación de rapidez)
        float baseLen = (12f + Math.min(speedKmh, 120f) * 0.42f) * dens * (0.7f + 0.4f * intensity);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < count; i++) {
            float len = baseLen * (0.6f + pz[i]);
            float dx = len * tan, dy = len;
            float a = 70 + pz[i] * 90;           // más opaca la de "adelante"
            p.setStrokeWidth((0.9f + pz[i] * 1.1f) * dens);
            p.setColor(Color.argb((int) Math.min(a, 200), 190, 214, 240));
            c.drawLine(px[i], py[i], px[i] - dx, py[i] - dy, p);
        }
        drawGlass(c);
        drawLightning(c, w, h);
    }

    void drawSnow(Canvas c, int w, int h) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            float r = (1.4f + pz[i] * 2.4f) * dens;
            int a = (int) (120 + pz[i] * 110);
            p.setColor(Color.argb(Math.min(a, 235), 255, 255, 255));
            c.drawCircle(px[i], py[i], r, p);
        }
    }

    void drawSun(Canvas c, int w, int h) {
        // Sol cálido en la esquina sup. derecha, mezcla NORMAL (se ve en tema claro y oscuro).
        // Intensidad controlada por la densidad de Ajustes (baja/media/alta). SIN parpadeo.
        float vis = userDensity < 0.6f ? 0.7f : (userDensity > 1.4f ? 1.5f : 1.0f);
        p.setStyle(Paint.Style.FILL);
        float cxp = w * 0.9f, cyp = h * 0.06f;
        // resplandor cálido acotado (tinte dorado)
        RadialGradient g = new RadialGradient(cxp, cyp, Math.max(w, h) * 0.58f,
                new int[]{Color.argb(a(150, vis), 255, 184, 88), Color.argb(a(60, vis), 255, 196, 110), Color.argb(0, 255, 200, 120)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        p.setShader(g);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // disco del sol (halo cálido, FIJO: sin pulso de "notificación")
        RadialGradient core = new RadialGradient(cxp, cyp, 62 * dens,
                new int[]{Color.argb(a(235, vis), 255, 216, 142), Color.argb(a(140, vis), 255, 190, 108),
                        Color.argb(0, 255, 190, 110)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        p.setShader(core);
        c.drawCircle(cxp, cyp, 62 * dens, p);
        p.setShader(null);
        // destellos de lente suaves hacia la diagonal opuesta (fijos)
        drawFlare(c, cxp - w * 0.30f, cyp + h * 0.40f, 28 * dens, a(72, vis), 255, 196, 120);
        drawFlare(c, cxp - w * 0.56f, cyp + h * 0.80f, 20 * dens, a(52, vis), 255, 206, 140);
        // chispitas cálidas flotando muy lento (día soleado, con vida; brillo suave, nada que parpadee feo)
        long t = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) {
            float fx = (0.16f + 0.74f * frac(i * 0.37f)) * w;
            float base = (0.12f + 0.64f * frac(i * 0.613f)) * h;
            float fy = base + 9f * dens * (float) Math.sin(t / 2600.0 + i);
            float tw = 0.7f + 0.3f * (float) Math.sin(t / 1800.0 + i * 1.3);
            p.setColor(Color.argb(a((int) (110 * tw), vis), 255, 204, 128));
            c.drawCircle(fx, fy, (0.9f + tw * 1.5f) * dens, p);
        }
    }

    static int a(int base, float vis) { int v = (int) (base * vis); return v < 0 ? 0 : (v > 255 ? 255 : v); }

    static float frac(float v) { return v - (float) Math.floor(v); }

    void drawFlare(Canvas c, float x, float y, float r, int alpha, int cr, int cg, int cb) {
        RadialGradient f = new RadialGradient(x, y, r,
                new int[]{Color.argb(Math.min(alpha, 140), cr, cg, cb), Color.argb(0, cr, cg, cb)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        p.setShader(f);
        c.drawCircle(x, y, r, p);
        p.setShader(null);
    }

    void drawClouds(Canvas c, int w, int h) {
        p.setStyle(Paint.Style.FILL);
        float vis = userDensity < 0.6f ? 0.75f : (userDensity > 1.4f ? 1.4f : 1.0f);
        // Tema claro: gris azulado (sobre blanco). Tema oscuro: casi blanco (sobre carbón).
        int r = lightBg ? 104 : 220, g = lightBg ? 116 : 226, b = lightBg ? 134 : 234;
        int core = a(lightBg ? 62 : 58, vis);
        // Cada nube = 4 lóbulos encimados (se ve como nube, no como mancha redonda).
        float[] ox = {-0.55f, -0.1f, 0.35f, 0.75f}, oy = {0.12f, -0.18f, -0.05f, 0.15f}, os = {0.55f, 0.75f, 0.65f, 0.45f};
        for (int i = 0; i < clouds; i++) {
            for (int k = 0; k < 4; k++) {
                float lx = cx[i] + ox[k] * cr[i], ly = cy[i] + oy[k] * cr[i], lr = os[k] * cr[i];
                RadialGradient gr = new RadialGradient(lx, ly, lr,
                        new int[]{Color.argb(core, r, g, b), Color.argb(core / 2, r, g, b), Color.argb(0, r, g, b)},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                p.setShader(gr);
                c.drawCircle(lx, ly, lr, p);
            }
            p.setShader(null);
        }
    }

    void drawFog(Canvas c, int w, int h) {
        long t = System.currentTimeMillis();
        float a = 46 + 16f * (float) Math.sin(t / 2600.0);
        LinearGradient g = new LinearGradient(0, 0, 0, h,
                lightBg ? new int[]{Color.argb((int) (a + 10), 120, 128, 140),
                        Color.argb((int) (a - 8), 112, 120, 132),
                        Color.argb((int) (a + 16), 124, 132, 144)}
                      : new int[]{Color.argb((int) (a + 24), 198, 202, 208),
                        Color.argb((int) a, 188, 192, 200),
                        Color.argb((int) (a + 30), 200, 204, 210)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        p.setStyle(Paint.Style.FILL);
        p.setShader(g);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    void drawStars(Canvas c, int w, int h) {
        long t = System.currentTimeMillis();
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < stars; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(t / 600.0 + stw[i]);
            int a = (int) (60 + 150 * tw);
            p.setColor(Color.argb(a, 235, 240, 255));
            c.drawCircle(sx[i], sy[i], (0.8f + tw * 1.1f) * dens, p);
        }
    }

    void drawLightning(Canvas c, int w, int h) {
        if (!storm) return;
        long t = System.currentTimeMillis();
        if (t < flashUntil) {
            float k = (flashUntil - t) / 160f;          // se desvanece
            int a = (int) Math.min(90 * k, 90);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(a, 230, 236, 255));
            c.drawRect(0, 0, w, h, p);
        }
    }
}
