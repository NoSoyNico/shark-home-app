package mx.nico.sharkhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Modelo 3D de la Shark, leído en tiempo de ejecución de la app 360 de fábrica (solo lectura; no se copia
 * nada a Shark Home). Formato .mesh de BYD (descifrado 4 nov 2026):
 *  car.mesh   = registros [u32 tamaño][u32 tipo]; tipo 1 = material: nombre[76], u32 n, pos[3n] uvw[3n] normal[3n]
 *  wheel.mesh = registros tipo 0: nombre[32] material[32] pivote[3f] u32 n, pos[3n] uvw[3n] normal[3n] extra[n]
 * Las ruedas giran según la velocidad del GPS.
 */
public class Truck3DView extends GLSurfaceView implements GLSurfaceView.Renderer {
    static final String TAG = "Shark3D";
    static final String APK = "/system/app/AutoVideo/AutoVideo.apk";
    static final String DIR = "assets/resource_pi/paha/CarModel/"; // paha = Shark (largo 5.448 m)
    static final float WHEEL_RADIUS = 0.40f;

    // Color de carrocería: verde salvia/olivo grisáceo de la Shark de Nico (foto ≈ #B4BBA3)
    float[] paint = {0.57f, 0.60f, 0.53f};

    /** Cambia el color de pintura de la troca (0xRRGGBB). */
    public void setPaint(int c) {
        paint[0] = ((c >> 16) & 0xFF) / 255f;
        paint[1] = ((c >> 8) & 0xFF) / 255f;
        paint[2] = (c & 0xFF) / 255f;
        requestRender();
    }

    static class Part {
        String name, mat;
        int n;
        FloatBuffer data;          // interleaved pos3 nor3 uv2
        int vbo;
        float[] pivot;             // null = carrocería
        String door;               // null = no es puerta; "door_lf","trunk","hood"... = puerta/cofre/cajuela
        boolean openVariant;       // true = geometría de "abierto" (se muestra al abrir); false = "cerrado"
        boolean lampOn;            // true = foco encendido (se muestra solo cuando la luz está prendida)
        String lightKey;           // "main" o "turn"
    }

    // Qué está abierto según la nube de BYD (door_lf, door_rf, door_lr, door_rr, trunk, hood).
    volatile java.util.Set<String> openKeys = new java.util.HashSet<String>();

    public void setOpen(java.util.Set<String> keys) {
        openKeys = keys;
        requestRender();
    }

    // Qué luces están encendidas: "main" (bajas/cuartos) y/o "turn" (direccionales).
    volatile java.util.Set<String> lightKeys = new java.util.HashSet<String>();

    public void setLights(java.util.Set<String> keys) {
        lightKeys = keys;
        requestRender();
    }

    static final java.util.regex.Pattern DOOR_PIECE =
            java.util.regex.Pattern.compile("^(door_?[a-z]{2}|hood|bonnet|trunk|tailgate|backdoor)(_open)?$");

    /** Por prefijo del nombre real del mesh: doorlf_paint -> door_lf, etc. null si no es puerta.
     *  APAGADO: este modelo no separa limpio las piezas de puerta (se mueven paneles de más).
     *  Dejar el código para afinar luego; por ahora no se reconocen puertas (la troca se ve normal). */
    static String doorKeyFromName(String pn) {
        if (pn.startsWith("doorlf")) return "door_lf";
        if (pn.startsWith("doorrf")) return "door_rf";
        if (pn.startsWith("doorlr")) return "door_lr";
        if (pn.startsWith("doorrr")) return "door_rr";
        if (pn.startsWith("trunk") || pn.startsWith("backdoor") || pn.startsWith("tailgate")) return "trunk";
        if (pn.startsWith("hood") || pn.startsWith("bonnet")) return "hood";
        return null;
    }

    /** "doorlf" / "door_fl" / "hood" / "trunk" -> llave común. null si no es una pieza que se abre. */
    static String doorKey(String base) {
        if (base.startsWith("door")) {
            String code = base.substring(4).replace("_", "");
            String side = code.indexOf('l') >= 0 ? "l" : "r";
            String pos = code.indexOf('f') >= 0 ? "f" : "r";
            return "door_" + side + pos;
        }
        if (base.equals("hood") || base.equals("bonnet")) return "hood";
        return "trunk";
    }

    final List<Part> parts = new ArrayList<>();
    final String[] texNames = {"car_texture", "car_texture1", "car_door", "car_control", "car_lamp", "wheel_texture"};
    final Bitmap[] texBmp = new Bitmap[texNames.length];
    final int[] tex = new int[texNames.length];
    volatile boolean loaded, uploaded;
    int prog, aPos, aNor, aUv, uMvp, uModel, uColor, uMode, uTex, uAlpha, uEye;

    final float[] proj = new float[16], view = new float[16], vp = new float[16], model = new float[16], mvp = new float[16];
    float yaw = 35f, wheelAngle = 0f, pitch = 13.5f; // yaw = giro horizontal (360° libre); pitch = inclinación vertical
    volatile float speedMps = 0f;
    long lastFrame = 0;
    final Handler ui = new Handler(Looper.getMainLooper());
    Runnable onFail;

    public Truck3DView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 4); // 4x MSAA
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        new Thread(new Runnable() { public void run() { load(); } }, "truck3d-load").start();
    }

    // ---------- Carga ----------
    void load() {
        long t0 = System.currentTimeMillis();
        try {
            ZipFile z = new ZipFile(APK);
            ByteBuffer car = read(z, DIR + "car.mesh");
            parseCar(car);
            car = null;
            parseWheels(read(z, DIR + "wheel.mesh"));
            for (int i = 0; i < texNames.length; i++) {
                ZipEntry e = z.getEntry(DIR + texNames[i] + ".png");
                if (e != null) {
                    InputStream in = z.getInputStream(e);
                    texBmp[i] = BitmapFactory.decodeStream(in);
                    in.close();
                }
            }
            z.close();
            loaded = true;
            Log.i(TAG, "modelo cargado en " + (System.currentTimeMillis() - t0) + " ms, piezas=" + parts.size());
            requestRender();
        } catch (Throwable e) {
            Log.w(TAG, "no se pudo cargar el modelo: " + e);
            if (onFail != null) ui.post(onFail);
        }
    }

    static ByteBuffer read(ZipFile z, String name) throws Exception {
        ZipEntry e = z.getEntry(name);
        InputStream in = z.getInputStream(e);
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(e.getSize(), 1024));
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        in.close();
        return ByteBuffer.wrap(bo.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
    }

    static String cstr(ByteBuffer b, int off, int max) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < max; i++) { byte c = b.get(off + i); if (c == 0) break; s.append((char) c); }
        return s.toString();
    }

    /** Pieza con nombre que se abre/cierra: material de origen + vértices que le pertenecen. */
    static class DoorPiece {
        String name, mat, key;
        boolean open;
        java.util.BitSet verts = new java.util.BitSet();
    }

    static void addSkip(java.util.Map<String, java.util.BitSet> skip, String mat, java.util.BitSet verts) {
        java.util.BitSet bs = skip.get(mat);
        if (bs == null) { bs = new java.util.BitSet(); skip.put(mat, bs); }
        bs.or(verts);
    }

    void parseCar(ByteBuffer b) {
        // 1) Piezas con nombre (tipo 2). El modelo trae variantes:
        //    doorXX_mat (cerrada) y doorXX_mat_open (abierta); focos body_lamp_N (apagado) y body_lamp_Non (encendido).
        //    Guardamos puertas (cerrada/abierta) y focos "on" aparte; el resto queda en la carrocería.
        java.util.HashMap<String, java.util.BitSet> skip = new java.util.HashMap<>();
        List<DoorPiece> doorPieces = new ArrayList<>();  // puertas/cofre/cajuela (ambas variantes)
        List<DoorPiece> lampPieces = new ArrayList<>();  // focos encendidos
        StringBuilder names = new StringBuilder();
        // Pre-escaneo: todos los nombres de pieza, para saber qué focos "...on" tienen gemelo apagado.
        java.util.HashSet<String> allNames = new java.util.HashSet<>();
        { int q = 0; while (q + 8 <= b.limit()) { int s2 = b.getInt(q), t2 = b.getInt(q + 4);
            if (t2 == 2) allNames.add(cstr(b, q + 8, 32)); if (s2 <= 0) break; q += s2; } }
        int o = 0;
        while (o + 8 <= b.limit()) {
            int sz = b.getInt(o), type = b.getInt(o + 4);
            if (type == 2) {
                int p = o + 8;
                String pn = cstr(b, p, 32), mat = cstr(b, p + 32, 32);
                names.append(pn).append(' ');
                int c = b.getInt(p + 76);
                java.util.BitSet verts = new java.util.BitSet();
                for (int i = 0; i < c; i++) verts.set(b.getInt(p + 80 + i * 4));
                String dk = doorKeyFromName(pn);
                // Piezas "selectoras" de estado abierto: doorlr_open / hood_open / trunk_open (el nombre es solo
                // la llave de la puerta + "_open", SIN material como _paint/_black). NO son geometría propia:
                // sus índices se traslapan con body_paint/lamp. Si las uso para esconder, le abro un HOYO a la
                // carrocería. Se ignoran por completo (no se esconden ni se dibujan).
                boolean selector = false;
                if (pn.endsWith("_open")) selector = pn.substring(0, pn.length() - 5).indexOf('_') < 0;
                if (selector) {
                    // ignorar: ni skip, ni dibujo
                } else if (pn.endsWith("on")) {
                    if (pn.contains("_open")) {
                        addSkip(skip, mat, verts); // foco de puerta abierta: no se muestra suelto
                    } else if (allNames.contains(pn.substring(0, pn.length() - 2))) {
                        // Foco conmutable (tiene gemelo apagado): el apagado queda en la carrocería,
                        // este "encendido" se muestra solo al prender luces.
                        DoorPiece d = new DoorPiece();
                        d.name = pn; d.mat = mat; d.key = pn.contains("turn") ? "turn" : "main"; d.verts = verts;
                        lampPieces.add(d);
                        addSkip(skip, mat, verts);
                    }
                    // "...on" SIN gemelo apagado = geometría fija (molduras/carrocería): se deja en la base.
                } else if (dk != null) { // puerta/cofre/cajuela: cerrada o abierta
                    DoorPiece d = new DoorPiece();
                    d.name = pn; d.mat = mat; d.key = dk; d.open = pn.contains("_open"); d.verts = verts;
                    doorPieces.add(d);
                    addSkip(skip, mat, verts);
                } else if (pn.contains("_open")) {
                    addSkip(skip, mat, verts);
                }
            }
            o += sz;
        }
        Log.i(TAG, "piezas con nombre: " + names);
        // 2) Materiales (tipo 1): carrocería base (sin puertas/focos) + partes de puerta + focos
        o = 0;
        while (o + 8 <= b.limit()) {
            int sz = b.getInt(o), type = b.getInt(o + 4);
            if (type == 1) {
                int p = o + 8;
                String name = cstr(b, p, 76);
                int n = b.getInt(p + 76);
                if (!name.equals("open")) {
                    Part pt = build(name, name, n, b, p + 80, null, skip.get(name), null);
                    if (pt.n > 0) parts.add(pt);
                }
                for (DoorPiece d : doorPieces) {
                    if (!d.mat.equals(name)) continue;
                    Part pt = build(d.name, name, n, b, p + 80, null, null, d.verts);
                    pt.door = d.key; pt.openVariant = d.open;
                    if (pt.n > 0) parts.add(pt);
                }
                for (DoorPiece d : lampPieces) {
                    if (!d.mat.equals(name)) continue;
                    Part pt = build(d.name, name, n, b, p + 80, null, null, d.verts);
                    pt.lampOn = true; pt.lightKey = d.key;
                    if (pt.n > 0) parts.add(pt);
                }
            }
            o += sz;
        }
        Log.i(TAG, "puertas(variantes)=" + doorPieces.size() + " focos=" + lampPieces.size());
    }

    

    void parseWheels(ByteBuffer b) {
        int o = 0;
        while (o + 8 <= b.limit()) {
            int sz = b.getInt(o), p = o + 8;
            String name = cstr(b, p, 32), mat = cstr(b, p + 32, 32);
            float[] piv = {b.getFloat(p + 64), b.getFloat(p + 68), b.getFloat(p + 72)};
            int n = b.getInt(p + 76);
            parts.add(build(name, "wheel_" + mat, n, b, p + 80, piv, null, null));
            o += sz;
        }
    }

    /**
     * Arma una pieza a partir de triángulos sueltos.
     * skip: descarta el triángulo si cualquiera de sus vértices está marcado.
     * only: si no es null, conserva SOLO los triángulos con algún vértice marcado (piezas de puerta).
     */
    static boolean keepTri(int t, int n, java.util.BitSet skip, java.util.BitSet only) {
        if (t + 2 >= n) return false;
        if (skip != null && (skip.get(t) || skip.get(t + 1) || skip.get(t + 2))) return false;
        if (only != null && !(only.get(t) || only.get(t + 1) || only.get(t + 2))) return false;
        return true;
    }

    static Part build(String name, String mat, int n, ByteBuffer b, int d, float[] pivot,
                      java.util.BitSet skip, java.util.BitSet only) {
        Part pt = new Part();
        pt.name = name; pt.mat = mat; pt.pivot = pivot;
        int keep = 0;
        for (int t = 0; t + 2 < n; t += 3)
            if (keepTri(t, n, skip, only)) keep += 3;
        pt.n = keep;
        FloatBuffer f = ByteBuffer.allocateDirect(Math.max(keep, 1) * 32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i < n; i++) {
            if (skip != null || only != null) {
                if (!keepTri(i - i % 3, n, skip, only)) continue;
            }
            int ps = d + i * 12, uv = d + n * 12 + i * 12, nr = d + n * 24 + i * 12;
            f.put(b.getFloat(ps)).put(b.getFloat(ps + 4)).put(b.getFloat(ps + 8));
            f.put(b.getFloat(nr)).put(b.getFloat(nr + 4)).put(b.getFloat(nr + 8));
            f.put(b.getFloat(uv)).put(b.getFloat(uv + 4));
        }
        f.position(0);
        pt.data = f;
        return pt;
    }

    // ---------- Velocidad / toque / rumbo ----------
    public void setSpeedKmh(float kmh) {
        speedMps = Math.max(0f, kmh / 3.6f);
        if (speedMps > 0) ui.removeCallbacks(tick);
        if (speedMps > 0) ui.post(tick); else requestRender();
    }


    final Runnable tick = new Runnable() {
        public void run() {
            requestRender();
            if (speedMps > 0) ui.postDelayed(this, 33); // ~30 fps solo mientras avanza
        }
    };

    float lastX, lastY;
    boolean rotating;
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Mitad de arriba de la troca = girarla. Mitad de abajo = dejar que el pager cambie de slide.
                if (e.getY() < getHeight() / 2f) {
                    rotating = true;
                    lastX = e.getX(); lastY = e.getY();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                rotating = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return false; // el toque sube al pager para cambiar de slide
            case MotionEvent.ACTION_MOVE:
                if (!rotating) return false;
                yaw += (e.getX() - lastX) * 0.3f;          // 360° libre en horizontal
                pitch += (e.getY() - lastY) * 0.3f;        // inclinación vertical
                if (pitch < -20f) pitch = -20f;            // tope para no voltearla de cabeza
                if (pitch > 85f) pitch = 85f;              // casi desde arriba
                lastX = e.getX(); lastY = e.getY();
                requestRender();
                return true;
            default:
                return rotating;
        }
    }

    // ---------- OpenGL ----------
    static final String VS =
        "uniform mat4 uMvp; uniform mat4 uModel;\n" +
        "attribute vec3 aPos; attribute vec3 aNor; attribute vec2 aUv;\n" +
        "varying vec3 vN; varying vec3 vW; varying vec2 vUv;\n" +
        "void main(){ vN = mat3(uModel) * aNor; vW = (uModel * vec4(aPos,1.0)).xyz; vUv = aUv;\n" +
        "  gl_Position = uMvp * vec4(aPos,1.0); }";
    static final String FS =
        "precision mediump float;\n" +
        "uniform vec3 uColor; uniform int uMode; uniform sampler2D uTex; uniform float uAlpha; uniform vec3 uEye;\n" +
        "varying vec3 vN; varying vec3 vW; varying vec2 vUv;\n" +
        "void main(){\n" +
        "  if(uMode==3){ gl_FragColor = vec4(uColor, uAlpha); return; }\n" + // foco encendido: color pleno
        "  vec3 N = normalize(vN); if(!gl_FrontFacing) N = -N;\n" +
        "  vec3 V = normalize(uEye - vW);\n" +
        "  vec3 L = normalize(vec3(0.35, 0.85, 0.45)); vec3 L2 = normalize(vec3(-0.6, 0.4, -0.5));\n" +
        "  vec3 base = uColor; if(uMode==1) base = texture2D(uTex, vUv).rgb;\n" +
        "  float d = max(dot(N,L),0.0)*0.75 + max(dot(N,L2),0.0)*0.25;\n" +
        "  float s = pow(max(dot(reflect(-L,N),V),0.0), uMode==2 ? 40.0 : 16.0) * (uMode==2 ? 0.7 : 0.25);\n" +
        "  float rim = pow(1.0 - max(dot(N,V),0.0), 3.0) * 0.25;\n" +
        "  vec3 c = base * (0.35 + d) + vec3(s) + vec3(rim) * vec3(0.55,0.65,0.9);\n" +
        "  gl_FragColor = vec4(c, uAlpha); }";

    int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.w(TAG, "shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    float bgR = 0x16 / 255f, bgG = 0x17 / 255f, bgB = 0x1A / 255f;
    public void setBgColor(int c) {
        bgR = ((c >> 16) & 0xFF) / 255f; bgG = ((c >> 8) & 0xFF) / 255f; bgB = (c & 0xFF) / 255f;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        GLES20.glClearColor(bgR, bgG, bgB, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VS));
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, FS));
        GLES20.glLinkProgram(prog);
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aNor = GLES20.glGetAttribLocation(prog, "aNor");
        aUv = GLES20.glGetAttribLocation(prog, "aUv");
        uMvp = GLES20.glGetUniformLocation(prog, "uMvp");
        uModel = GLES20.glGetUniformLocation(prog, "uModel");
        uColor = GLES20.glGetUniformLocation(prog, "uColor");
        uMode = GLES20.glGetUniformLocation(prog, "uMode");
        uTex = GLES20.glGetUniformLocation(prog, "uTex");
        uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha");
        uEye = GLES20.glGetUniformLocation(prog, "uEye");
        uploaded = false;
    }

    void upload() {
        int[] ids = new int[parts.size()];
        GLES20.glGenBuffers(ids.length, ids, 0);
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            p.vbo = ids[i];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, p.vbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, p.n * 32, p.data, GLES20.GL_STATIC_DRAW);
        }
        GLES20.glGenTextures(tex.length, tex, 0);
        for (int i = 0; i < tex.length; i++) {
            if (texBmp[i] == null) continue;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, texBmp[i], 0);
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        }
        uploaded = true;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        Matrix.perspectiveM(proj, 0, 26f, w / (float) Math.max(h, 1), 0.5f, 60f);
    }

    int texIndex(String mat) {
        switch (mat) {
            case "texture": return 0;
            case "texture1": return 1;
            case "texture2": return 2;
            case "texture3": return 3;
            case "lamp": return 4;
            case "wheel_texture": return 5;
            default: return -1;
        }
    }

    float[] colorFor(String mat) {
        switch (mat) {
            case "paint": return paint;
            case "black": return new float[]{0.05f, 0.05f, 0.055f};
            case "glass": return new float[]{0.06f, 0.08f, 0.11f};
            case "metal": case "wheel_metal": return new float[]{0.62f, 0.64f, 0.67f};
            case "interior": return new float[]{0.12f, 0.12f, 0.13f};
            default: return new float[]{0.5f, 0.5f, 0.5f};
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (!loaded) return;
        if (!uploaded) upload();

        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9f, 0.1f);
        lastFrame = now;
        wheelAngle -= (float) Math.toDegrees(speedMps / WHEEL_RADIUS) * dt;

        // Cámara 3/4 delantera izquierda; el dedo cambia el ángulo.
        // dist más grande = troca completa dentro del cuadro (antes 7.6 quedaba cortada).
        float cx = -0.1f, cy = 0.9f;
        // En vista aérea (pitch alto) la troca ocupa más alto en pantalla; alejamos la cámara para que quepa completa.
        float dist = 9.8f + Math.max(0f, pitch - 15f) * 0.065f;
        double a = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        float r = (float) (dist * Math.cos(pr));                 // radio horizontal según la inclinación
        float ex = cx + (float) (Math.cos(a) * r), ez = (float) (-Math.sin(a) * r);
        float ey = (cy - 0.15f) + (float) (dist * Math.sin(pr)); // altura de la cámara según el pitch
        Matrix.setLookAtM(view, 0, ex, ey, ez, cx, cy - 0.15f, 0f, 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        GLES20.glUseProgram(prog);
        GLES20.glUniform3f(uEye, ex, ey, ez);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        // opacos primero, vidrio al final con transparencia
        for (int pass = 0; pass < 2; pass++) {
            boolean glassPass = pass == 1;
            if (glassPass) { GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA); GLES20.glDepthMask(false); }
            java.util.Set<String> open = openKeys, lights = lightKeys;
            for (Part p : parts) {
                if (p.mat.equals("glass") != glassPass) continue;
                // puerta/cofre/cajuela: mostrar la variante abierta solo si está abierta (y viceversa)
                if (p.door != null && open.contains(p.door) != p.openVariant) continue;
                // foco encendido: mostrar solo si esa luz está prendida
                if (p.lampOn && !lights.contains(p.lightKey)) continue;
                Matrix.setIdentityM(model, 0);
                if (p.pivot != null) {
                    Matrix.translateM(model, 0, p.pivot[0], p.pivot[1], p.pivot[2]);
                    Matrix.rotateM(model, 0, wheelAngle, 0f, 0f, 1f);
                }
                Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                int ti = texIndex(p.mat);
                int mode = ti >= 0 && tex[ti] != 0 ? 1 : (p.mat.equals("paint") ? 2 : 0);
                float[] col = colorFor(p.mat);
                if (p.lampOn) { // foco encendido: brillante (blanco cálido / ámbar las direccionales)
                    mode = 3;
                    col = "turn".equals(p.lightKey) ? new float[]{1f, 0.55f, 0.05f} : new float[]{1f, 0.92f, 0.62f};
                }
                if (mode == 1) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[ti]);
                GLES20.glUniform3f(uColor, col[0], col[1], col[2]);
                GLES20.glUniform1i(uMode, mode);
                GLES20.glUniform1f(uAlpha, glassPass ? 0.55f : 1f);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, p.vbo);
                GLES20.glEnableVertexAttribArray(aPos);
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, 0);
                GLES20.glEnableVertexAttribArray(aNor);
                GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 32, 12);
                GLES20.glEnableVertexAttribArray(aUv);
                GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 32, 24);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, p.n);
            }
            if (glassPass) { GLES20.glDepthMask(true); GLES20.glDisable(GLES20.GL_BLEND); }
        }
    }
}
