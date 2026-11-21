package mx.nico.sharkhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shark Home — prototipo 0.1 de launcher estilo Tesla para BYD Shark (DiLink 3.0, Android 10).
 * Todo se dibuja con código (sin librerías) para que arranque rápido.
 * Datos del vehículo: todavía de muestra ("—"); se conectan en la fase 4, solo lectura.
 */
public class MainActivity extends Activity implements LiveInfo.Ui, VehicleLog.Ui {

    LiveInfo live;
    Truck3DView truck3d;
    TextView speedTv, headingTv, gpsTv, tempTv, weatherTv, mediaTitle, mediaSub;
    TextView statLbl2, statVal3, vehicleLine, nameTv, clockKmTv, clockSpeedTv, clockMediaTv;
    LinearLayout hourlyRow;
    VehicleLog vlog;
    mx.nico.sharkhome.cloud.VehicleStatus lastVehicle;
    boolean demo3d, haveLog;
    String lastHeading = "—", lastStatus = "GPS", lastSpeedKmh = "0";
    MediaButton playBtn;
    String mediaPkg;
    String currentPhrase; // "Frase del Momento": se elige al encender (onCreate) y no cambia hasta el próximo arranque

    // Páginas del panel izquierdo (en orden dentro del pager).
    static final int PG_TRUCK = 0, PG_CLOCK = 1, PG_FRASE = 2, PG_LYRICS = 3, PG_COUNT = 4;

    SwipePager pager;
    LyricsView lyricsView;
    Dots dots;
    FrameLayout rootF;
    FrameLayout dockHolder;
    View mapPanel;  // nuestro mapa; se oculta en pantalla dividida (BYD Maps toma el lugar)
    LinearLayout contentRow; View carView, rightCol; boolean splitLayout;
    WeatherFx weatherFx, weatherFxPanel;
    int lastWmo = -1; boolean lastDay = true; String demoFx = null;
    android.content.BroadcastReceiver fxReceiver;
    java.util.List<Lyrics.Line> curLines = new java.util.ArrayList<Lyrics.Line>();
    final android.os.Handler lyricTick = new android.os.Handler();
    final Runnable lyricRun = new Runnable() {
        public void run() {
            updateLyricHighlight();
            lyricTick.postDelayed(this, 250);
        }
    };

    // Colores del tema (cambian con el botón claro/oscuro). ACCENT es igual en ambos.
    static int BG, PANEL, TEXT, MUTED, CARD, MAP, SCRIM, DOTOFF;
    static final int ACCENT = 0xFF3E6AE1;
    boolean light;

    void applyTheme(boolean lt) {
        light = lt;
        if (lt) { // claro (blanco y negro)
            BG = 0xFFEDEEF1; PANEL = 0xFFFFFFFF; TEXT = 0xFF14151A; MUTED = 0xFF6B6F76;
            CARD = 0xFFEFF1F4; MAP = 0xFFE8EAED; SCRIM = 0xF2FFFFFF; DOTOFF = 0xFFC4C8CE;
        } else {  // oscuro (negro)
            BG = 0xFF0B0B0C; PANEL = 0xFF16171A; TEXT = 0xFFF2F2F2; MUTED = 0xFF8A8D93;
            CARD = 0xFF202227; MAP = 0xFF1C1E22; SCRIM = 0xE6202227; DOTOFF = 0xFF4A4D53;
        }
    }

    void toggleTheme() {
        boolean nt = !light;
        getSharedPreferences("shark", MODE_PRIVATE).edit().putBoolean("light", nt).apply();
        recreate();
    }

    // {etiqueta, paquete, actividad (null = la principal del paquete)}
    // Mapas de fábrica BYD (TelenavHere): reemplaza la dependencia de Waze for Electro.
    static final String FACTORY_NAV = "com.telenav.app.arp";

    static final String[][] DOCK = {
        {"360°", "com.byd.avc", "com.byd.avc.AutoVideoActivity"},
        {"Mapas", FACTORY_NAV, null},
        {"Música", "com.byd.mediacenter", "com.byd.mediacenter.main.MediaActivity"},
        {"Radio", "com.byd.mediacenter", "com.byd.mediacenter.radio.view.RadioActivity"},
        {"Spotify", "com.vivid.music.byd", null},
        {"Cámaras", "com.byd.auto_camera", null},
        {"Electro", "br.com.rory.electro", null},
        {"Teléfono", "com.byd.bluetoothcall", null},
        {"CarPlay", "com.byd.carplay.ui", null},
        {"Android Auto", "com.byd.androidauto", null},
        {"Ajustes", "com.byd.carsettings", null},
    };

    float d;

    int dp(float v) { return Math.round(v * d); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        applyTheme(getSharedPreferences("shark", MODE_PRIVATE).getBoolean("light", false));
        getWindow().getDecorView().setBackgroundColor(BG);
        currentPhrase = pickPhrase(); // nueva frase cada vez que arranca el launcher (al encender la troca)
        migrateNavToFactory();        // dejar de depender de Waze for Electro -> mapas de fábrica
        live = new LiveInfo(this, this);
        vlog = new VehicleLog(this, this);
        setContentView(buildUi());
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        maybeActivateAdb(); // como Electro: si falta READ_LOGS, pide ADB local (dispara el diálogo "Permitir")
        int camTest = getIntent().getIntExtra("camtest", -1);
        if (camTest >= 0) CamTest.run(this, camTest);
        if (getIntent().getBooleanExtra("probe", false)) {
            new Thread(new Runnable() {
                public void run() { new VehicleData(MainActivity.this).probe(); }
            }).start();
        }
        // Probar efectos de clima sin que llueva de verdad:
        //   am start ... --es demofx rain|drizzle|storm|snow|sun|clouds|fog|stars  [--ei demospeed 60]
        // (Para probar sin que llueva de verdad se usa un broadcast; ver onResume: mx.nico.sharkhome.FX)

        final String demoDoors = getIntent().getStringExtra("demodoor"); // "door_lf,door_rf" o "all"
        final String demoLights = getIntent().getStringExtra("demolight"); // "main" / "turn" / "main,turn"
        if (demoDoors != null || demoLights != null) demo3d = true; // el feed en vivo no pisa el demo
        if (demoDoors != null || demoLights != null) new android.os.Handler().postDelayed(new Runnable() {
            public void run() {
                if (truck3d == null) return;
                java.util.HashSet<String> k = new java.util.HashSet<String>();
                if (demoDoors != null) {
                    if (demoDoors.contains("all")) { k.add("door_lf"); k.add("door_rf"); k.add("door_lr"); k.add("door_rr"); }
                    else for (String s : demoDoors.split(",")) k.add(s.trim());
                }
                truck3d.setOpen(k);
                java.util.HashSet<String> lk = new java.util.HashSet<String>();
                if (demoLights != null) for (String s : demoLights.split(",")) lk.add(s.trim());
                truck3d.setLights(lk);
            }
        }, 2500);
    }

    View buildUi() {
        boolean portrait = getResources().getDisplayMetrics().heightPixels
                > getResources().getDisplayMetrics().widthPixels;

        rootF = new FrameLayout(this);
        rootF.setBackgroundColor(BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        content.setPadding(dp(12), dp(12), dp(12), dp(8));

        View car = buildCarPanel();            // panel izquierdo a toda la altura
        View right = buildRightColumn();       // mapa + apps (solo bajo el mapa) + crédito
        contentRow = content; carView = car; rightCol = right;
        if (portrait) {
            content.addView(car, new LinearLayout.LayoutParams(-1, 0, 0.42f));
            content.addView(gap(), new LinearLayout.LayoutParams(1, dp(12)));
            content.addView(right, new LinearLayout.LayoutParams(-1, 0, 0.58f));
        } else {
            content.addView(car, new LinearLayout.LayoutParams(0, -1, 0.40f));
            content.addView(gap(), new LinearLayout.LayoutParams(dp(12), 1));
            content.addView(right, new LinearLayout.LayoutParams(0, -1, 0.60f));
        }
        rootF.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // Capa de efectos de clima (transparente, no intercepta toques). Encima del contenido,
        // debajo de la X de escape y de los diálogos de Ajustes.
        weatherFx = new WeatherFx(this);
        rootF.addView(weatherFx, new FrameLayout.LayoutParams(-1, -1));
        applyWeatherFx();

        // Indicador de volumen propio, chiquito (reemplaza la barra intrusiva de BYD cuando el launcher tiene el foco).
        buildVolumeChip();

        // X roja sutil en la mera esquina de arriba: escape si se traba o para salir al launcher de BYD.
        TextView kill = text("✕", 15, 0xFFE2483A, true);
        kill.setAlpha(0.6f);
        kill.setGravity(Gravity.CENTER);
        GradientDrawable kbg = new GradientDrawable();
        kbg.setShape(GradientDrawable.OVAL);
        kbg.setColor(0x22808080); // fondito tenue para ubicarla
        kill.setBackground(kbg);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP | Gravity.END);
        klp.setMargins(0, dp(4), dp(6), 0);
        kill.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exitToSystemHome(); }
        });
        rootF.addView(kill, klp);
        return rootF;
    }

    boolean isDefaultHome() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    // Abre la pantalla del sistema para elegir el launcher por defecto (ahí se marca Shark Home).
    void requestDefaultHome() {
        // El filtro HOME vive en un alias apagado (para que el explorador de BYD instale la app).
        // Lo encendemos aquí; es un componente propio, no requiere permisos.
        try {
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, "mx.nico.sharkhome.HomeAlias"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) { /* si falla, la pantalla de abajo igual se abre */ }
        // Pantalla "App de inicio predeterminada" (la que sí funciona en estos BYD).
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
            Toast.makeText(this, "Elige “Shark Home” en la lista", Toast.LENGTH_LONG).show();
            return;
        } catch (Throwable t) { /* probamos otras vías */ }
        try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); return; }
        catch (Throwable t) {}
        // Último recurso: diálogo de rol HOME (no siempre muestra UI en estos equipos).
        try {
            android.app.role.RoleManager rm =
                    (android.app.role.RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                startActivity(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME));
                return;
            }
        } catch (Throwable t) {}
        Toast.makeText(this, "Abre Ajustes > Apps > App de inicio", Toast.LENGTH_LONG).show();
    }

    // Sale de Shark Home: abre el launcher de fábrica (BYD) que no sea esta app, y cierra Shark Home.
    // Sirve de escape si la app se traba o si quedó puesta como launcher por defecto.
    void exitToSystemHome() {
        try {
            PackageManager pm = getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> homes = pm.queryIntentActivities(home, 0);
            for (ResolveInfo ri : homes) {
                if (!ri.activityInfo.packageName.equals(getPackageName())) {
                    Intent go = new Intent(Intent.ACTION_MAIN);
                    go.addCategory(Intent.CATEGORY_HOME);
                    go.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                    go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(go);
                    break;
                }
            }
        } catch (Throwable t) { /* seguimos a cerrar de todos modos */ }
        finishAffinity();
        System.exit(0);
    }

    // Columna derecha: mapa arriba, dock de apps DEBAJO del mapa, y el crédito sutil al fondo.
    View buildRightColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        mapPanel = buildMapPanel();
        col.addView(mapPanel, new LinearLayout.LayoutParams(-1, 0, 1f));
        col.addView(gap(), new LinearLayout.LayoutParams(-1, dp(10)));
        dockHolder = new FrameLayout(this);
        dockHolder.addView(buildDockRow());
        col.addView(dockHolder, new LinearLayout.LayoutParams(-1, dp(104)));
        col.addView(buildCredit(), new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    // Crédito muy sutil, abajo a la derecha.
    View buildCredit() {
        TextView c = text("ⓘ  Hecho por @El.BioNiico  ·  sugerencias por Instagram", 10, MUTED, false);
        c.setGravity(Gravity.END);
        c.setSingleLine(true);
        c.setAlpha(0.5f);
        c.setPadding(0, dp(4), dp(6), 0);
        return c;
    }

    View gap() { return new View(this); }

    GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    // ---------- Panel izquierdo: la troca ----------
    View buildCarPanel() {
        final FrameLayout panel = new FrameLayout(this);
        panel.setBackground(rounded(PANEL, 20));
        panel.setClipToOutline(true);

        // --- Capa 1: pager troca 3D <-> letras (ocupa TODO el panel) ---
        final FrameLayout truckBox = new FrameLayout(this);
        truckBox.setPadding(dp(8), dp(94), dp(8), dp(122)); // deja ver reloj arriba y controles abajo
        truck3d = new Truck3DView(this);
        truck3d.setBgColor(PANEL);
        int tc = prefs().getInt("truckColor", -1);
        if (tc != -1) truck3d.setPaint(tc);
        truckBox.addView(truck3d, new FrameLayout.LayoutParams(-1, -1));
        truck3d.onFail = new Runnable() {
            public void run() {
                truckBox.removeAllViews();
                truck3d = null;
                truckBox.addView(new TruckView(MainActivity.this), new FrameLayout.LayoutParams(-1, -1));
            }
        };

        lyricsView = new LyricsView(this);
        lyricsView.setLight(light);
        lyricsView.setMessage("Nada sonando");
        lyricsView.setPadding(dp(22), dp(26), dp(22), dp(26));

        pager = new SwipePager(this);
        pager.addView(truckBox);            // 0 = troca 3D
        pager.addView(buildClockSlide());   // 1 = reloj/fecha/km (estilo Apple TV)
        pager.addView(buildFraseSlide());   // 2 = Frase del Momento
        pager.addView(lyricsView);          // 3 = letra (solo al tocar el panel de música)
        panel.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        // --- Capa 2: controles que se DIFUMINAN al pasar a las letras ---
        final FrameLayout chrome = new FrameLayout(this);

        LinearLayout topBlock = new LinearLayout(this);
        topBlock.setOrientation(LinearLayout.VERTICAL);
        topBlock.setPadding(dp(24), dp(20), dp(24), 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        speedTv = text("—", 34, TEXT, true);
        top.addView(speedTv);
        top.addView(text(" km/h", 15, MUTED, false));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(26);
        clock.setTextColor(TEXT);
        top.addView(clock);
        ThemeToggle themeBtn = new ThemeToggle(this);
        LinearLayout.LayoutParams tbl = new LinearLayout.LayoutParams(dp(30), dp(30));
        tbl.leftMargin = dp(12);
        themeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleTheme(); }
        });
        top.addView(themeBtn, tbl);
        TextView gearBtn = text("⚙", 22, TEXT, false);
        gearBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams gbl = new LinearLayout.LayoutParams(dp(32), dp(32));
        gbl.leftMargin = dp(8);
        gearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettings(); }
        });
        top.addView(gearBtn, gbl);
        topBlock.addView(top);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE d 'de' MMMM");
        date.setFormat24Hour("EEEE d 'de' MMMM");
        date.setTextSize(15);
        date.setTextColor(MUTED);
        topBlock.addView(date, new LinearLayout.LayoutParams(-1, -2));

        nameTv = text(vehicleName(), 22, TEXT, true);
        nameTv.setLetterSpacing(0.15f);
        nameTv.setPadding(0, dp(8), 0, 0);
        topBlock.addView(nameTv);
        // Estado desde la nube de BYD: puertas abiertas / llantas (vacío si no hay cuenta configurada)
        vehicleLine = text("", 13, MUTED, false);
        vehicleLine.setPadding(0, dp(2), 0, 0);
        vehicleLine.setMaxLines(2);
        topBlock.addView(vehicleLine);
        chrome.addView(topBlock, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottomBlock = new LinearLayout(this);
        bottomBlock.setOrientation(LinearLayout.VERTICAL);
        bottomBlock.setPadding(dp(24), 0, dp(24), dp(18));

        LinearLayout stats = new LinearLayout(this);
        tempTv = text("—", 22, TEXT, true);
        weatherTv = text("Afuera", 13, MUTED, false);
        headingTv = text("—", 22, TEXT, true);
        gpsTv = text("GPS", 13, MUTED, false);
        statLbl2 = text("Rumbo", 13, MUTED, false);
        statVal3 = text("•", 22, ACCENT, true);
        View weatherStat = stat(tempTv, weatherTv);
        weatherStat.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (pager != null) pager.setPage(PG_CLOCK, true); } // tocar clima -> clima por horas
        });
        stats.addView(weatherStat, new LinearLayout.LayoutParams(0, -2, 1f));
        stats.addView(stat(headingTv, statLbl2), new LinearLayout.LayoutParams(0, -2, 1f));
        stats.addView(stat(statVal3, gpsTv), new LinearLayout.LayoutParams(0, -2, 1f));
        bottomBlock.addView(stats);

        // El bloque de datos (stats) se sube para dejar abajo la barra de música persistente.
        FrameLayout.LayoutParams bbp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bbp.bottomMargin = dp(112);
        chrome.addView(bottomBlock, bbp);

        panel.addView(chrome, new FrameLayout.LayoutParams(-1, -1));

        // BARRA DE MÚSICA PERSISTENTE: fuera del chrome (no se difumina) -> visible y funcional en TODOS los slides
        // (troca, reloj, frase y letra) para pausar/adelantar/atrasar desde cualquiera.
        FrameLayout.LayoutParams barp = new FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM);
        barp.setMargins(dp(22), 0, dp(22), dp(16));
        panel.addView(buildMediaCard(), barp);

        // Puntitos de página: capa propia, SIEMPRE arriba de la barra de música (visibles en todos los slides).
        dots = new Dots(this, PG_COUNT);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-1, dp(16), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = dp(104);
        dots.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (pager != null) pager.setPage((pager.getPage() + 1) % PG_COUNT, true); }
        });
        panel.addView(dots, dlp);

        // Difuminado: al deslizar fuera de la troca (f>=1 = reloj/frase/letra) se desvanecen SOLO los controles de la
        // troca (nombre, datos, engrane). La barra de música y los puntitos se quedan.
        pager.setOnScroll(new SwipePager.OnScroll() {
            public void onScroll(float f) {
                float cf = Math.max(0f, Math.min(1f, f));
                chrome.setAlpha(1f - cf);
                chrome.setVisibility(f > 0.98f ? View.INVISIBLE : View.VISIBLE);
            }
        });
        pager.setOnPageChange(new SwipePager.OnPageChange() {
            public void onPage(int page) { if (dots != null) dots.setCurrent(page); }
        });

        // Capa de efectos de clima SOLO del panel (encima de todo, recortada al panel redondeado).
        // La lluvia cae sobre el reloj/troca/letras y deja gotas pegadas que escurren (vidrio mojado).
        weatherFxPanel = new WeatherFx(this);
        weatherFxPanel.glass = true;
        panel.addView(weatherFxPanel, new FrameLayout.LayoutParams(-1, -1));

        return panel;
    }

    /** Nombre que se muestra grande en el panel izquierdo (editable en Ajustes). */
    String vehicleName() {
        String n = prefs().getString("vehicleName", "BYD SHARK");
        return (n == null || n.trim().isEmpty()) ? "BYD SHARK" : n;
    }


    // --- Slide reloj/fecha/km estilo Apple TV (limpio, centrado) + velocidad y música en vivo ---
    View buildClockSlide() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(PANEL); // mismo color del panel (se recorta redondeado como los demás slides)
        box.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(88);
        time.setTextColor(TEXT);
        time.setGravity(Gravity.CENTER_HORIZONTAL);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        box.addView(time, new LinearLayout.LayoutParams(-2, -2));

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE d 'de' MMMM");
        date.setFormat24Hour("EEEE d 'de' MMMM");
        date.setTextSize(18);
        date.setTextColor(MUTED);
        date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(2);
        box.addView(date, dlp);

        // Línea de velocidad · kilometraje
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        clockSpeedTv = text(lastSpeedKmh + " km/h", 18, MUTED, false);
        clockSpeedTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextView sep = text("   ·   ", 18, MUTED, false);
        clockKmTv = text("— km", 18, MUTED, false);
        clockKmTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockKmTv.setLetterSpacing(0.04f);
        row.addView(clockSpeedTv);
        row.addView(sep);
        row.addView(clockKmTv);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = dp(22);
        box.addView(row, rlp);

        // Clima por horas (como una app de clima): próximas horas con ícono y temperatura.
        hourlyRow = new LinearLayout(this);
        hourlyRow.setOrientation(LinearLayout.HORIZONTAL);
        hourlyRow.setGravity(Gravity.CENTER);
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(hourlyRow);
        // Ancho = lo que midan las horas (no a todo lo ancho) y centrado bajo el reloj.
        // Si no caben, el LinearLayout lo acota al ancho del panel y se puede deslizar.
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(34);
        box.addView(hsv, hlp);
        fillHourly();

        updateClockKm();
        return box;
    }

    // ---------- Clima por horas (slide del reloj) ----------
    java.util.List<LiveInfo.HourFc> hourly;

    public void onHourly(java.util.List<LiveInfo.HourFc> hours) {
        hourly = hours;
        fillHourly();
    }

    void fillHourly() {
        if (hourlyRow == null) return;
        hourlyRow.removeAllViews();
        if (hourly == null || hourly.isEmpty()) return;
        for (LiveInfo.HourFc h : hourly) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(4), 0, dp(4), 0);
            TextView hr = text(h.label, 12, MUTED, false);
            hr.setGravity(Gravity.CENTER);
            TextView emo = text(weatherEmoji(h.code, h.day), 24, TEXT, false);
            emo.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
            elp.topMargin = dp(6); elp.bottomMargin = dp(6);
            TextView tp = text(h.temp + "°", 17, TEXT, true);
            tp.setGravity(Gravity.CENTER);
            cell.addView(hr);
            cell.addView(emo, elp);
            cell.addView(tp);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(56), -2);
            hourlyRow.addView(cell, clp);
        }
    }

    static String weatherEmoji(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "🌤️" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 57) return "🌦️";
        if (code >= 61 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "🌨️";
        if (code >= 80 && code <= 82) return "🌧️";
        if (code >= 95) return "⛈️";
        return "🌡️";
    }

    /** Actualiza la velocidad del slide reloj. */
    void updateClockSpeed() {
        if (clockSpeedTv != null) clockSpeedTv.setText(lastSpeedKmh + " km/h");
    }

    /** Actualiza la música mostrada en el slide reloj (se oculta si no hay nada sonando). */
    void updateClockMedia(String title, String subtitle, boolean playing) {
        if (clockMediaTv == null) return;
        boolean has = playing && title != null && !title.isEmpty() && !"Nada sonando".equals(title);
        if (!has) { clockMediaTv.setVisibility(View.GONE); return; }
        String s = "♪  " + title + (subtitle == null || subtitle.isEmpty() ? "" : "  —  " + subtitle);
        clockMediaTv.setText(s);
        clockMediaTv.setVisibility(View.VISIBLE);
    }

    // --- Slide "Frase del Momento" (frase random elegida al encender) ---
    View buildFraseSlide() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(PANEL); // mismo color del panel (redondeado como los demás)
        box.setPadding(dp(34), dp(24), dp(34), dp(24));

        TextView label = text("FRASE DEL MOMENTO", 12, MUTED, true);
        label.setLetterSpacing(0.22f);
        label.setGravity(Gravity.CENTER);
        box.addView(label, new LinearLayout.LayoutParams(-1, -2));

        TextView phrase = text("“" + currentPhrase + "”", 26, TEXT, false);
        phrase.setGravity(Gravity.CENTER);
        phrase.setLineSpacing(dp(5), 1.1f);
        phrase.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(18);
        box.addView(phrase, plp);
        return box;
    }

    // Frases propias (no citas de terceros). Cambia al reiniciar el launcher (al encender la troca).
    static final String[] PHRASES = {
        "Hoy es un buen día para comerte el mundo.",
        "El camino se hace manejando.",
        "Respira hondo: lo tienes.",
        "Los grandes viajes empiezan por encender el motor.",
        "Tú mandas, el resto se acomoda.",
        "Disfruta el trayecto, no solo el destino.",
        "Que nada te apague las ganas.",
        "Un día a la vez, un kilómetro a la vez.",
        "La actitud es tu mejor accesorio.",
        "El que avanza, aunque sea despacio, llega.",
        "Confía en ti y arranca.",
        "Lo difícil también se disfruta.",
        "Haz que valga la pena el viaje.",
        "Buenas vibras y camino libre.",
        "Tú decides el rumbo de hoy.",
        "Sé la energía que quieres encontrar.",
        "Nada que un buen camino no arregle.",
        "Avanza con calma, pero avanza.",
        "El mejor momento para empezar es ahora.",
        "Maneja tu vida a tu propio ritmo.",
        "Pequeños pasos, grandes destinos.",
        "Que el volante te lleve a cosas buenas.",
        "Hoy vas por todo.",
        "Donde pongas el foco, va la troca."
    };

    String pickPhrase() { return PHRASES[new java.util.Random().nextInt(PHRASES.length)]; }

    // Botón de tema: círculo medio relleno (icono de contraste). Toca para claro/oscuro.
    static class ThemeToggle extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ThemeToggle(Context c) { super(c); setClickable(true); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - dp2(2), cx = w / 2f, cy = h / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp2(2));
            p.setColor(TEXT);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
            RectF rf = new RectF(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rf, -90, 180, true, p); // mitad derecha rellena
        }
        float dp2(float v) { return v * getResources().getDisplayMetrics().density; }
    }

    View stat(TextView value, TextView label) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.addView(value);
        s.addView(label);
        return s;
    }

    // ---------- Tarjeta de música ----------
    View buildMediaCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(CARD, 14));
        card.setPadding(dp(16), 0, dp(8), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(76));
        card.setLayoutParams(lp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        mediaTitle = text("Nada sonando", 16, TEXT, true);
        mediaTitle.setSingleLine(true);
        mediaTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mediaSub = text("", 13, MUTED, false);
        mediaSub.setSingleLine(true);
        mediaSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(mediaTitle);
        texts.addView(mediaSub);
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        // Tocar el panel de música muestra la LETRA (slide dedicado). Ya no se muestra sola.
        texts.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (pager != null) pager.setPage(PG_LYRICS, true); }
        });

        MediaButton prev = new MediaButton(this, MediaButton.PREV);
        playBtn = new MediaButton(this, MediaButton.PLAY);
        MediaButton next = new MediaButton(this, MediaButton.NEXT);
        prev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { live.prev(); } });
        playBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { live.playPause(); } });
        next.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { live.next(); } });
        card.addView(prev, new LinearLayout.LayoutParams(dp(56), dp(56)));
        card.addView(playBtn, new LinearLayout.LayoutParams(dp(56), dp(56)));
        card.addView(next, new LinearLayout.LayoutParams(dp(56), dp(56)));
        return card;
    }

    // ---------- Datos en vivo (LiveInfo.Ui) ----------
    public void onGps(String speed, String heading, String status) {
        speedTv.setText(speed);
        if (truck3d != null) {
            int demo = getIntent().getIntExtra("demospeed", 0); // solo para probar el giro de ruedas sin manejar
            if (demo > 0) truck3d.setSpeedKmh(demo);
            else try { truck3d.setSpeedKmh(Float.parseFloat(speed)); } catch (NumberFormatException e) { truck3d.setSpeedKmh(0); }
        }
        lastHeading = heading; lastStatus = status; lastSpeedKmh = speed;
        renderStatsRow();
        updateClockSpeed();
    }

    // ---------- Datos del vehículo: LOG (puertas/luces), solo lectura ----------
    mx.nico.sharkhome.cloud.VehicleStatus merged() {
        if (lastVehicle == null) lastVehicle = new mx.nico.sharkhome.cloud.VehicleStatus();
        return lastVehicle;
    }

    // ---------- Acceso a datos del vehículo vía ADB local (como Electro) ----------
    /** ¿Está habilitado nuestro listener de notificaciones (para ver qué suena)? */
    boolean mediaListenerEnabled() {
        String flat = android.provider.Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName() + "/");
    }

    /** ¿Tenemos TODO lo que da el ADB (READ_LOGS para datos + listener para música)? */
    boolean adbGrantsComplete() {
        return vlog != null && vlog.canRead() && mediaListenerEnabled();
    }

    /** Al abrir: si falta READ_LOGS O el listener de música, y el usuario no lo rechazó, pedir ADB local. */
    void maybeActivateAdb() {
        if (adbGrantsComplete()) return;                      // ya tenemos ambos permisos
        if (prefs().getBoolean("adbDeclined", false)) return; // lo rechazó; usar el botón de Ajustes
        activateVehicleAccess(false);
    }

    /** Corre el flujo ADB en segundo plano: autentica (diálogo la 1a vez) y otorga READ_LOGS + listener de música. */
    void activateVehicleAccess(final boolean manual) {
        final android.os.Handler h = new android.os.Handler();
        new Thread(new Runnable() {
            public void run() {
                new AdbClient(MainActivity.this).grantAll(new AdbClient.Progress() {
                    public void line(final String s) {
                        if (manual) h.post(new Runnable() { public void run() {
                            Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); } });
                    }
                });
                h.post(new Runnable() { public void run() {
                    boolean ok = vlog != null && vlog.canRead(); // al menos leer datos quedó
                    if (ok) {
                        vlog.start(); // empezar a leer puertas/luces/odómetro
                        if (live != null) live.retryMedia(); // ya hay acceso a notificaciones: enganchar música
                        if (adbGrantsComplete()) prefs().edit().putBoolean("adbDeclined", false).apply();
                        if (manual) Toast.makeText(MainActivity.this, "Datos del vehículo activados ✔", Toast.LENGTH_LONG).show();
                    } else {
                        prefs().edit().putBoolean("adbDeclined", true).apply();
                        if (manual) Toast.makeText(MainActivity.this,
                                "No se activó. Toca “Permitir” en la troca y reintenta.", Toast.LENGTH_LONG).show();
                    }
                } });
            }
        }).start();
    }

    /** ¿La detección de puertas y luces está encendida? (se puede apagar en Ajustes si se traba "abierto"). */
    boolean doorsOn() { return prefs().getBoolean("doorsOn", true); }

    void updateDoorsBtn(TextView b) {
        boolean on = doorsOn();
        b.setText(on ? "Puertas y luces: ENCENDIDA  ·  tocar para apagar"
                     : "Puertas y luces: APAGADA  ·  tocar para encender");
        b.setTextColor(on ? ACCENT : 0xFFE2A13A);
    }

    // ---------- Asistente de voz BYD (despertar por voz) ----------
    // Se enciende/apaga con el ajuste del sistema KEY_AUDIO_WAKEUP. Una app normal puede LEERLO,
    // pero NO escribirlo (BYD lo bloquea); por eso se escribe por el puente ADB local.
    static final String VOICE_WAKE = "KEY_AUDIO_WAKEUP";

    /** ¿El despertar por voz está encendido? (por defecto sí, como viene de fábrica). */
    boolean voiceWakeOn() {
        String v = android.provider.Settings.System.getString(getContentResolver(), VOICE_WAKE);
        return !"false".equalsIgnoreCase(v == null ? "" : v.trim()) && !"0".equals(v == null ? "" : v.trim());
    }

    void updateVoiceBtn(TextView b) {
        boolean on = voiceWakeOn();
        b.setText(on ? "Asistente de voz: ENCENDIDO  ·  tocar para apagar"
                     : "Asistente de voz: APAGADO  ·  tocar para encender");
        b.setTextColor(on ? ACCENT : 0xFFE2A13A);
    }

    /** Escribe KEY_AUDIO_WAKEUP por el puente ADB (dispara el diálogo la 1a vez si hace falta). */
    void setVoiceWake(final boolean on, final TextView btn) {
        final android.os.Handler h = new android.os.Handler();
        Toast.makeText(this, on ? "Encendiendo asistente…" : "Apagando asistente…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                final String out = new AdbClient(MainActivity.this).runShell(new String[] {
                        "settings put system " + VOICE_WAKE + " " + (on ? "true" : "false")
                                + " 2>&1; echo VW=$?\n"
                }, new AdbClient.Progress() { public void line(String s) {
                    final String ss = s;
                    h.post(new Runnable() { public void run() {
                        Toast.makeText(MainActivity.this, ss, Toast.LENGTH_SHORT).show(); } });
                } });
                h.post(new Runnable() { public void run() {
                    boolean now = voiceWakeOn();
                    updateVoiceBtn(btn);
                    if (now == on) Toast.makeText(MainActivity.this,
                            on ? "Asistente de voz encendido ✔" : "Asistente de voz apagado ✔", Toast.LENGTH_LONG).show();
                    else Toast.makeText(MainActivity.this,
                            "No se pudo cambiar. Toca “Permitir” en la troca y reintenta.", Toast.LENGTH_LONG).show();
                } });
            }
        }).start();
    }

    /** Cierra todas las puertas/luces en el modelo y limpia el aviso (al apagar la detección). */
    void clearDoorsLights() {
        if (lastVehicle != null) {
            lastVehicle.doorLF = lastVehicle.doorRF = lastVehicle.doorLR = lastVehicle.doorRR
                    = lastVehicle.trunk = lastVehicle.hood = Boolean.FALSE;
            lastVehicle.lowBeam = lastVehicle.sideLight = lastVehicle.turnSignal = Boolean.FALSE;
            renderVehicle();
        } else if (truck3d != null) {
            truck3d.setOpen(new java.util.HashSet<String>());
            truck3d.setLights(new java.util.HashSet<String>());
        }
    }

    public void onVehicleLog(mx.nico.sharkhome.cloud.VehicleStatus s) { // LOG (instantáneo)
        haveLog = true;
        mx.nico.sharkhome.cloud.VehicleStatus v = merged();
        if (doorsOn()) {
            v.doorLF = s.doorLF; v.doorRF = s.doorRF; v.doorLR = s.doorLR; v.doorRR = s.doorRR;
            v.trunk = s.trunk; v.hood = s.hood;
            v.lowBeam = s.lowBeam; v.sideLight = s.sideLight; v.turnSignal = s.turnSignal;
        } else {
            // Detección apagada: todo cerrado y sin luces (evita que se quede trabada "abierta").
            v.doorLF = v.doorRF = v.doorLR = v.doorRR = v.trunk = v.hood = Boolean.FALSE;
            v.lowBeam = v.sideLight = v.turnSignal = Boolean.FALSE;
        }
        if (s.online != null) v.online = s.online;
        if (s.odometerKm != null) v.odometerKm = s.odometerKm; // km del tablero (si el log los manda)
        renderVehicle();
        updateClockKm();
    }

    /** Actualiza los km del slide reloj (si tenemos odómetro; si no, queda en "—"). */
    void updateClockKm() {
        if (clockKmTv == null) return;
        Double km = lastVehicle == null ? null : lastVehicle.odometerKm;
        clockKmTv.setText(km == null ? "— km" : String.format(java.util.Locale.US, "%,d km", Math.round(km)));
    }

    /** Fila de datos (celdas 2 y 3): rumbo + GPS. */
    void renderStatsRow() {
        headingTv.setText(lastHeading);
        statLbl2.setText("Rumbo");
        statVal3.setText("•");
        statVal3.setTextColor(ACCENT);
        gpsTv.setText(lastStatus);
    }

    void renderVehicle() {
        mx.nico.sharkhome.cloud.VehicleStatus v = lastVehicle;
        if (v == null) return;
        renderStatsRow();
        String open = v.openSummary(), lights = v.lightsSummary();
        StringBuilder line = new StringBuilder(open);
        if (!lights.isEmpty()) line.append(line.length() > 0 ? "  ·  " : "").append(lights);
        if (v.online != null && !v.online) line.append(line.length() > 0 ? "  ·  " : "").append("troca sin conexión");
        vehicleLine.setText(line.toString());
        vehicleLine.setTextColor(v.anyOpen() || v.tireWarning ? 0xFFE2A13A : MUTED);
        if (truck3d != null && !demo3d) {
            java.util.HashSet<String> k = new java.util.HashSet<String>();
            if (Boolean.TRUE.equals(v.doorLF)) k.add("door_lf");
            if (Boolean.TRUE.equals(v.doorRF)) k.add("door_rf");
            if (Boolean.TRUE.equals(v.doorLR)) k.add("door_lr");
            if (Boolean.TRUE.equals(v.doorRR)) k.add("door_rr");
            if (Boolean.TRUE.equals(v.trunk)) k.add("trunk");
            if (Boolean.TRUE.equals(v.hood)) k.add("hood");
            truck3d.setOpen(k);
            java.util.HashSet<String> lk = new java.util.HashSet<String>();
            if (Boolean.TRUE.equals(v.lowBeam) || Boolean.TRUE.equals(v.sideLight)) lk.add("main");
            if (Boolean.TRUE.equals(v.turnSignal)) lk.add("turn");
            truck3d.setLights(lk);
        }
    }

    public void onLogError(String msg) { onCloudError(msg); }

    public void onCloudError(String msg) {
        // Si ya teníamos datos, se dejan en pantalla y solo se avisa abajo del nombre.
        vehicleLine.setText("Datos BYD: " + msg);
        vehicleLine.setTextColor(MUTED);
    }

    public void onWeather(String temp, String desc) {
        tempTv.setText(temp);
        weatherTv.setText(desc);
    }

    public void onWeatherFx(int wmoCode, boolean day) {
        lastWmo = wmoCode; lastDay = day;
        applyWeatherFx();
    }

    /** ¿Están encendidos los efectos de clima? (se apaga en Ajustes). */
    boolean weatherFxOn() { return prefs().getBoolean("weatherFx", true); }

    /** ¿El alcance es solo el panel izquierdo? (si no, pantalla completa). */
    boolean fxPanelOnly() { return !"full".equals(prefs().getString("fxScope", "panel")); }

    /** Multiplicador de densidad elegido en Ajustes. */
    float fxDensityMult() {
        String d = prefs().getString("fxDensity", "media");
        if ("baja".equals(d)) return 0.55f;
        if ("alta".equals(d)) return 1.6f;
        return 1f; // media
    }

    /** Aplica el clima (o el demo) a la capa activa según el alcance, respetando el interruptor. */
    void applyWeatherFx() {
        boolean on = weatherFxOn();
        boolean panelOnly = fxPanelOnly();
        float dens = fxDensityMult();
        if (weatherFx != null) { weatherFx.setDensity(dens); weatherFx.lightBg = light; }
        if (weatherFxPanel != null) { weatherFxPanel.setDensity(dens); weatherFxPanel.lightBg = light; }
        WeatherFx active = panelOnly ? weatherFxPanel : weatherFx;
        WeatherFx other = panelOnly ? weatherFx : weatherFxPanel;
        if (other != null) other.setWeather(-1, true);   // apagar la que no se usa
        if (active == null) return;
        if (!on) { active.setWeather(-1, true); return; }
        if (demoFx != null) active.setDemo(demoFx);
        else if (lastWmo >= 0) active.setWeather(lastWmo, lastDay);
    }

    void fxSetSpeed(float kmh) {
        if (weatherFx != null) weatherFx.setSpeed(kmh);
        if (weatherFxPanel != null) weatherFxPanel.setSpeed(kmh);
    }

    void updateWeatherFxBtn(TextView b) {
        boolean on = weatherFxOn();
        b.setText(on ? "Efectos de clima: ENCENDIDOS  ·  tocar para apagar"
                     : "Efectos de clima: APAGADOS  ·  tocar para encender");
        b.setTextColor(on ? ACCENT : 0xFFE2A13A);
    }

    void updateFxScopeBtn(TextView b) {
        boolean panel = fxPanelOnly();
        b.setText(panel ? "Alcance: SOLO PANEL IZQUIERDO  ·  tocar para pantalla completa"
                        : "Alcance: PANTALLA COMPLETA  ·  tocar para solo panel");
        b.setTextColor(ACCENT);
    }

    void updateFxDensityBtn(TextView b) {
        String d = prefs().getString("fxDensity", "media");
        String label = "baja".equals(d) ? "BAJA" : ("alta".equals(d) ? "ALTA" : "MEDIA");
        b.setText("Densidad de lluvia/nieve: " + label + "  ·  tocar para cambiar");
        b.setTextColor(ACCENT);
    }

    /** baja -> media -> alta -> baja */
    String nextDensity() {
        String d = prefs().getString("fxDensity", "media");
        if ("baja".equals(d)) return "media";
        if ("media".equals(d)) return "alta";
        return "baja";
    }

    public void onMedia(String title, String subtitle, boolean playing, String pkg) {
        mediaTitle.setText(title);
        mediaSub.setText(subtitle);
        playBtn.setKind(playing ? MediaButton.PAUSE : MediaButton.PLAY);
        mediaPkg = pkg;
        updateClockMedia(title, subtitle, playing);
    }

    public void onLyrics(java.util.List<Lyrics.Line> lines, boolean synced, boolean playing) {
        curLines = (lines != null) ? lines : new java.util.ArrayList<Lyrics.Line>();
        if (lyricsView == null) return;
        if (curLines.isEmpty()) {
            lyricsView.setMessage(playing ? "Sin letra para esta canción" : "Nada sonando");
            // Ya NO cambiamos de slide solo: la letra se ve únicamente cuando Nico toca el panel de música.
        } else {
            lyricsView.setLines(curLines, synced);
            updateLyricHighlight();
        }
    }

    void updateLyricHighlight() {
        if (lyricsView == null || !lyricsView.isSynced()) return;
        long pos = live.positionMs();
        if (pos >= 0) lyricsView.setActiveIndex(Lyrics.indexAt(curLines, pos));
    }

    // ---------- Indicador de volumen propio (chiquito) ----------
    View volChip; android.widget.ProgressBar volBar; TextView volTxt, volIcon;
    android.media.AudioManager audioMgr;
    final android.os.Handler volHandler = new android.os.Handler();
    final Runnable volHide = new Runnable() { public void run() {
        if (volChip != null) volChip.animate().alpha(0f).setDuration(250).start();
    } };

    void buildVolumeChip() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(rounded(0xE6121519, 22));
        pill.setPadding(dp(18), dp(12), dp(20), dp(12));
        volIcon = text("🔊", 20, 0xFFFFFFFF, false);
        pill.addView(volIcon, new LinearLayout.LayoutParams(-2, -2));
        volBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        volBar.setMax(100);
        volBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        volBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x40FFFFFF));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(180), dp(8));
        blp.setMargins(dp(14), 0, dp(14), 0);
        pill.addView(volBar, blp);
        volTxt = text("0", 15, 0xFFFFFFFF, true);
        pill.addView(volTxt, new LinearLayout.LayoutParams(-2, -2));
        volChip = pill;
        volChip.setAlpha(0f);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        vlp.topMargin = dp(70);
        rootF.addView(volChip, vlp);
    }

    /** Sube/baja el volumen de música sin la barra de BYD y muestra el indicador chiquito. */
    boolean handleVolumeKey(int keyCode) {
        if (keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (audioMgr == null) audioMgr = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int dir = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER;
        audioMgr.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, 0); // 0 = SIN UI del sistema
        int max = audioMgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int cur = audioMgr.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int pct = max > 0 ? Math.round(cur * 100f / max) : 0;
        if (volBar != null) volBar.setProgress(pct);
        if (volTxt != null) volTxt.setText(String.valueOf(cur));
        if (volIcon != null) volIcon.setText(cur == 0 ? "🔇" : "🔊");
        if (volChip != null) { volChip.animate().cancel(); volChip.setAlpha(1f); }
        volHandler.removeCallbacks(volHide);
        volHandler.postDelayed(volHide, 1400);
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent e) {
        if (handleVolumeKey(keyCode)) return true;
        return super.onKeyDown(keyCode, e);
    }

    @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent e) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return true; // consumir (sin barra BYD)
        return super.onKeyUp(keyCode, e);
    }

    // ---------- Pantalla dividida con BYD Maps ----------
    /**
     * En pantalla dividida (multiventana) acomodamos el launcher COMPACTO: panel arriba (a toda la altura)
     * y el dock abajo, ocultando NUESTRO mapa (BYD Maps va al lado y hace de mapa).
     */
    void applyMultiWindow(boolean inMulti) {
        if (contentRow == null) return;
        splitLayout = inMulti;
        if (mapPanel != null) mapPanel.setVisibility(inMulti ? View.GONE : View.VISIBLE);
        View gap = contentRow.getChildCount() > 1 ? contentRow.getChildAt(1) : null;
        if (inMulti) {
            contentRow.setOrientation(LinearLayout.VERTICAL);
            contentRow.setPadding(dp(10), dp(8), dp(10), dp(6));
            if (carView != null) carView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            if (gap != null) gap.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(6)));
            if (rightCol != null) rightCol.setLayoutParams(new LinearLayout.LayoutParams(-1, -2, 0f));
        } else {
            contentRow.setOrientation(LinearLayout.HORIZONTAL);
            contentRow.setPadding(dp(12), dp(12), dp(12), dp(8));
            if (carView != null) carView.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 0.40f));
            if (gap != null) gap.setLayoutParams(new LinearLayout.LayoutParams(dp(12), 1));
            if (rightCol != null) rightCol.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 0.60f));
        }
        contentRow.requestLayout();
    }

    @Override
    public void onMultiWindowModeChanged(boolean inMulti) {
        super.onMultiWindowModeChanged(inMulti);
        applyMultiWindow(inMulti);
    }

    /** Abre BYD Maps en pantalla dividida (adyacente) y deja el launcher compacto en el otro lado. */
    void openBydMapSplit() {
        try {
            Intent i = new Intent();
            i.setClassName(FACTORY_NAV, "com.telenav.arp.module.map.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "No se pudo abrir en pantalla dividida", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyMultiWindow(isInMultiWindowMode()); // por si arrancamos ya en dividido
        if (truck3d != null) truck3d.onResume();
        applyRightMode();   // arranca el slideshow si está en modo fotos
        live.start();
        if (vlog.canRead()) vlog.start();      // log local (puertas/luces al instante, como Electro)
        lyricTick.postDelayed(lyricRun, 250);
        // Receptor para probar efectos de clima sin que llueva de verdad:
        //   adb shell am broadcast -a mx.nico.sharkhome.FX --es fx rain|snow|sun|clouds|fog|stars|storm --ei speed 60
        if (fxReceiver == null) {
            fxReceiver = new android.content.BroadcastReceiver() {
                public void onReceive(android.content.Context c, Intent i) {
                    String sc = i.getStringExtra("scope");
                    if (sc != null) prefs().edit().putString("fxScope", sc.equals("panel") ? "panel" : "full").apply();
                    String den = i.getStringExtra("density");
                    if (den != null) prefs().edit().putString("fxDensity", den).apply();
                    String fx = i.getStringExtra("fx");
                    if (fx != null) demoFx = fx.equalsIgnoreCase("auto") ? null : fx;
                    if (sc != null || fx != null || den != null) applyWeatherFx();
                    if (i.hasExtra("speed")) fxSetSpeed(i.getIntExtra("speed", 0));
                }
            };
            registerReceiver(fxReceiver, new android.content.IntentFilter("mx.nico.sharkhome.FX"));
        }
    }

    @Override
    protected void onPause() {
        live.stop();
        vlog.stop();
        lyricTick.removeCallbacks(lyricRun);
        if (truck3d != null) { truck3d.setSpeedKmh(0); truck3d.onPause(); }
        if (photos != null) photos.stop();
        if (fxReceiver != null) { try { unregisterReceiver(fxReceiver); } catch (Throwable ignored) {} fxReceiver = null; }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        live.stop();
        live.start();
    }

    static class MediaButton extends View {
        static final int PREV = 0, PLAY = 1, PAUSE = 2, NEXT = 3;
        int kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        MediaButton(Context c, int k) {
            super(c);
            kind = k;
            p.setColor(TEXT);
            setClickable(true);
        }

        void setKind(int k) { if (k != kind) { kind = k; invalidate(); } }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h) * 0.32f;
            float cx = w / 2f, cy = h / 2f;
            Path t = new Path();
            switch (kind) {
                case PLAY:
                    t.moveTo(cx - s * 0.6f, cy - s); t.lineTo(cx + s, cy); t.lineTo(cx - s * 0.6f, cy + s); t.close();
                    c.drawPath(t, p);
                    break;
                case PAUSE:
                    c.drawRect(cx - s * 0.75f, cy - s, cx - s * 0.2f, cy + s, p);
                    c.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.75f, cy + s, p);
                    break;
                case PREV:
                case NEXT:
                    float dir = kind == NEXT ? 1 : -1;
                    float bx = cx + dir * s * 0.8f;
                    t.moveTo(cx - dir * s * 0.8f, cy - s * 0.8f); t.lineTo(bx - dir * s * 0.15f, cy);
                    t.lineTo(cx - dir * s * 0.8f, cy + s * 0.8f); t.close();
                    c.drawPath(t, p);
                    c.drawRect(Math.min(bx, bx + dir * s * 0.25f), cy - s * 0.8f, Math.max(bx, bx + dir * s * 0.25f), cy + s * 0.8f, p);
                    break;
            }
        }
    }

    // Indicador de página (puntitos) debajo del pager troca/letras.
    static class Dots extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int count, current;
        Dots(Context c, int n) { super(c); count = n; setClickable(true); }
        void setCurrent(int i) { if (i != current) { current = i; invalidate(); } }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), cy = h / 2f, r = h * 0.18f, gap = h * 0.9f;
            float total = (count - 1) * gap;
            float x = getWidth() / 2f - total / 2f;
            for (int i = 0; i < count; i++) {
                p.setColor(i == current ? TEXT : DOTOFF);
                c.drawCircle(x + i * gap, cy, i == current ? r * 1.15f : r, p);
            }
        }
    }

    // ---------- Panel derecho: mapa (abre Waze) ----------
    View buildMapPanel() {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(rounded(MAP, 20));
        f.setClipToOutline(true);

        // Mapa en vivo: Waze Live Map (embed oficial de Waze) dentro de un WebView.
        mapWeb = new android.webkit.WebView(this);
        mapWeb.setBackgroundColor(MAP);
        android.webkit.WebSettings ws = mapWeb.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        mapWeb.setWebViewClient(new android.webkit.WebViewClient());
        f.addView(mapWeb, new FrameLayout.LayoutParams(-1, -1));
        double[] start = lastKnown();
        if (start == null) start = new double[]{19.4326, -99.1332};
        curLat = start[0]; curLon = start[1];
        mapWeb.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface public void ready() {
                runOnUiThread(new Runnable() { public void run() { mapReady = true; pushPos(); maybeRotateDemo(); } });
            }
            @android.webkit.JavascriptInterface public void pick(final double la, final double lo) {
                runOnUiThread(new Runnable() { public void run() { onDestPicked(la, lo); } });
            }
            // El mapa vectorial no cargó (sin WebGL / sin internet): ya regresó a Calles.
            @android.webkit.JavascriptInterface public void styleFail() {
                runOnUiThread(new Runnable() { public void run() {
                    prefs().edit().putInt("mapStyle", 0).apply();
                    if (styleBtn != null) styleBtn.setText(MAP_STYLES[0]);
                    Toast.makeText(MainActivity.this, "El mapa Vector no carga aquí; regresé a Calles", Toast.LENGTH_LONG).show();
                } });
            }
        }, "Shark");
        mapWeb.loadDataWithBaseURL("https://sharkhome.local/", mapHtml(start[0], start[1], light, mapStyle()), "text/html", "utf-8", null);

        final String navPkg = navPkg();
        String navLabel = navPkg == null ? "" : navLabel(navPkg);
        TextView go = text(navPkg == null ? "Navegar" : "Navegar con " + navLabel, 16, 0xFFFFFFFF, true);
        go.setBackground(rounded(ACCENT, 14));
        go.setPadding(dp(20), dp(12), dp(20), dp(12));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        gp.setMargins(0, 0, dp(16), dp(16));
        f.addView(go, gp);
        final String navLabelF = navLabel;
        navGo = go;
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (hasDest) { handoffToNav(navPkg, navLabelF); return; } // mandar el destino a BYD Maps (voz)
                if (navPkg == null) { showSettings(); return; } // sin app: ve a elegir una
                open(navPkg, null, navLabelF);
            }
        });

        // Botón "Ruta": elegir destino en el mapa; con ruta puesta, lo quita.
        routeBtn = text("Ruta", 15, TEXT, true);
        routeBtn.setBackground(rounded(SCRIM, 14));
        routeBtn.setPadding(dp(18), dp(12), dp(18), dp(12));
        FrameLayout.LayoutParams rtp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        rtp.setMargins(dp(16), 0, 0, dp(70));
        f.addView(routeBtn, rtp);
        routeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (hasDest) { clearRoute(); return; }
                if (!mapReady || mapWeb == null) return;
                mapWeb.evaluateJavascript("routePick();", null);
                Toast.makeText(MainActivity.this, "Toca el destino en el mapa", Toast.LENGTH_LONG).show();
            }
        });

        // Acceso directo a la vista 360 de fábrica (se abre en ~0.6 s)
        TextView pano = text("360°", 20, TEXT, true);
        pano.setGravity(Gravity.CENTER);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setShape(GradientDrawable.OVAL);
        pbg.setColor(SCRIM);
        pbg.setStroke(dp(2), ACCENT);
        pano.setBackground(pbg);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.TOP | Gravity.END);
        pp.setMargins(0, dp(16), dp(16), 0);
        f.addView(pano, pp);
        pano.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { open("com.byd.avc", "com.byd.avc.AutoVideoActivity", "la vista 360"); }
        });

        // Capa del mapa (prueba v0.40): Calles -> Topo -> Vector -> Calles. Se recuerda (pref mapStyle).
        styleBtn = text(MAP_STYLES[mapStyle()], 15, TEXT, true);
        styleBtn.setBackground(rounded(SCRIM, 14));
        styleBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sp.setMargins(0, dp(104), dp(16), 0);
        f.addView(styleBtn, sp);
        styleBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = (mapStyle() + 1) % MAP_STYLES.length;
                prefs().edit().putInt("mapStyle", n).apply();
                styleBtn.setText(MAP_STYLES[n]);
                if (mapReady && mapWeb != null) mapWeb.evaluateJavascript("setStyle(" + n + ");", null);
                if (n == 2) Toast.makeText(MainActivity.this, "Cargando mapa Vector…", Toast.LENGTH_SHORT).show();
            }
        });

        TextView center = text("Centrar", 15, TEXT, false);
        center.setBackground(rounded(SCRIM, 14));
        center.setPadding(dp(18), dp(12), dp(18), dp(12));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        cp.setMargins(dp(16), 0, 0, dp(16));
        f.addView(center, cp);
        center.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mapReady) mapWeb.evaluateJavascript("follow=true;setPos(" + curLat + "," + curLon + ");", null);
            }
        });

        // Buscar destino por dirección/nombre (geocodifica y traza la ruta) — arriba a la izquierda.
        TextView buscar = text("🔎  Buscar destino", 15, TEXT, true);
        buscar.setBackground(rounded(SCRIM, 14));
        buscar.setPadding(dp(16), dp(12), dp(18), dp(12));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        bp.setMargins(dp(16), dp(16), 0, 0);
        f.addView(buscar, bp);
        buscar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openRouteSearch(); }
        });

        // Marco de fotos: capa que CUBRE el mapa cuando el modo del panel derecho es "fotos".
        photos = new PhotoSlideshow(this);
        photos.setFolder(photosDir());
        f.addView(photos, new FrameLayout.LayoutParams(-1, -1));
        photos.setVisibility(View.GONE);
        applyRightMode();
        return f;
    }

    // ---------- Panel derecho: Mapa o Fotos ----------
    PhotoSlideshow photos;

    /** Carpeta donde el usuario pone sus fotos. */
    String photosDir() {
        return new java.io.File(android.os.Environment.getExternalStorageDirectory(), "SharkHome/fotos").getAbsolutePath();
    }

    boolean showPhotos() { return "fotos".equals(prefs().getString("rightMode", "map")); }

    /** Segundos por imagen (0 = foto estática). */
    int photoIntervalSec() { return prefs().getInt("photoInterval", 10); }

    /** Muestra fotos (cubre el mapa) o el mapa, según el ajuste. */
    void applyRightMode() {
        if (photos == null) return;
        boolean f = showPhotos();
        photos.setVisibility(f ? View.VISIBLE : View.GONE);
        if (f) { photos.setIntervalSec(photoIntervalSec()); photos.start(); } else photos.stop();
    }

    String photoIntervalLabel() {
        int s = photoIntervalSec();
        return s <= 0 ? "Foto estática (no cambia)" : "Cada " + s + " s";
    }
    /** Estática -> 5 -> 10 -> 20 -> 30 -> Estática */
    int nextPhotoInterval() {
        int s = photoIntervalSec();
        if (s <= 0) return 5; if (s < 10) return 10; if (s < 20) return 20; if (s < 30) return 30; return 0;
    }

    /** Cuadro para escribir a dónde vas; geocodifica y traza la ruta. */
    void openRouteSearch() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("Calle y número, lugar, colonia…");
        in.setSingleLine(true);
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(in);
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("¿A dónde vas?")
                .setView(box)
                .setPositiveButton("Buscar", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { geocodeAndRoute(in.getText().toString()); }
                })
                .setNegativeButton("Cancelar", null)
                .create();
        in.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView t, int a, android.view.KeyEvent e) {
                geocodeAndRoute(in.getText().toString()); dlg.dismiss(); return true;
            }
        });
        dlg.show();
    }

    /** Geocodifica el texto con Nominatim (OSM), sesgado a tu zona, y traza la ruta al mejor resultado. */
    void geocodeAndRoute(final String query) {
        if (query == null || query.trim().isEmpty()) return;
        Toast.makeText(this, "Buscando “" + query.trim() + "”…", Toast.LENGTH_SHORT).show();
        final double oLat = curLat, oLon = curLon;
        new Thread(new Runnable() {
            public void run() {
                java.net.HttpURLConnection c = null;
                try {
                    // viewbox alrededor de la posición actual para priorizar resultados cercanos (bounded=0 = solo sesgo)
                    String vb = (oLon - 0.4) + "," + (oLat + 0.4) + "," + (oLon + 0.4) + "," + (oLat - 0.4);
                    java.net.URL u = new java.net.URL("https://nominatim.openstreetmap.org/search?format=json&limit=1"
                            + "&countrycodes=mx&viewbox=" + vb + "&bounded=0&q="
                            + java.net.URLEncoder.encode(query.trim(), "UTF-8"));
                    c = (java.net.HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(9000);
                    c.setRequestProperty("User-Agent", "SharkHome/1.0");  // Nominatim exige User-Agent
                    c.setRequestProperty("Accept-Language", "es");
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String ln;
                    while ((ln = r.readLine()) != null) sb.append(ln);
                    org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
                    if (arr.length() == 0) { postRouteFail("No encontré “" + query.trim() + "”"); return; }
                    org.json.JSONObject best = arr.getJSONObject(0);
                    final double la = best.getDouble("lat"), lo = best.getDouble("lon");
                    final String name = best.optString("display_name", "");
                    runOnUiThread(new Runnable() { public void run() {
                        if (mapWeb != null) mapWeb.evaluateJavascript("setDest(" + la + "," + lo + ");", null);
                        if (!name.isEmpty())
                            Toast.makeText(MainActivity.this, name.split(",")[0], Toast.LENGTH_LONG).show();
                        onDestPicked(la, lo);
                    } });
                } catch (Throwable t) {
                    android.util.Log.w("SharkRoute", "geocode: " + t);
                    postRouteFail("No se pudo buscar la dirección");
                } finally { if (c != null) c.disconnect(); }
            }
        }).start();
    }

    android.webkit.WebView mapWeb;
    boolean mapReady;
    double curLat, curLon;
    TextView navGo, routeBtn;
    boolean hasDest;
    double destLat, destLon;

    // ---------- Ruta en nuestro mapa (dibujo) + voz por BYD Maps (geo:) ----------
    /** El usuario tocó un destino: pedir la ruta a OSRM y dibujarla. */
    void onDestPicked(final double la, final double lo) {
        destLat = la; destLon = lo;
        Toast.makeText(this, "Trazando ruta…", Toast.LENGTH_SHORT).show();
        final double oLat = curLat, oLon = curLon;
        new Thread(new Runnable() {
            public void run() {
                java.net.HttpURLConnection c = null;
                try {
                    // OSRM público (demo): origen;destino -> geometría + distancia + duración
                    java.net.URL u = new java.net.URL(String.format(java.util.Locale.US,
                            "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                            oLon, oLat, lo, la));
                    c = (java.net.HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(9000);
                    c.setRequestProperty("User-Agent", "SharkHome/1.0");
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String ln;
                    while ((ln = r.readLine()) != null) sb.append(ln);
                    org.json.JSONObject root = new org.json.JSONObject(sb.toString());
                    org.json.JSONArray routes = root.optJSONArray("routes");
                    if (routes == null || routes.length() == 0) { postRouteFail("Sin ruta a ese punto"); return; }
                    org.json.JSONObject route = routes.getJSONObject(0);
                    final double dist = route.getDouble("distance");   // metros
                    final double dur = route.getDouble("duration");    // segundos
                    org.json.JSONArray coords = route.getJSONObject("geometry").getJSONArray("coordinates");
                    // GeoJSON viene [lon,lat] -> Leaflet quiere [lat,lon]
                    StringBuilder js = new StringBuilder("[");
                    for (int i = 0; i < coords.length(); i++) {
                        org.json.JSONArray p = coords.getJSONArray(i);
                        if (i > 0) js.append(",");
                        js.append("[").append(p.getDouble(1)).append(",").append(p.getDouble(0)).append("]");
                    }
                    js.append("]");
                    final String jsCoords = js.toString();
                    runOnUiThread(new Runnable() { public void run() {
                        if (mapWeb != null) mapWeb.evaluateJavascript("drawRoute('" + jsCoords + "');", null);
                        hasDest = true;
                        updateRouteUi(dist, dur);
                    } });
                } catch (Throwable t) {
                    android.util.Log.w("SharkRoute", "ruta: " + t);
                    postRouteFail("No se pudo trazar la ruta");
                } finally { if (c != null) c.disconnect(); }
            }
        }).start();
    }

    void postRouteFail(final String msg) {
        runOnUiThread(new Runnable() { public void run() {
            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            if (mapWeb != null) mapWeb.evaluateJavascript("clearRoute();", null);
            hasDest = false; updateRouteUi(-1, -1);
        } });
    }

    void updateRouteUi(double distM, double durS) {
        if (routeBtn != null) routeBtn.setText(hasDest ? "Quitar ruta" : "Ruta");
        if (navGo != null) {
            if (hasDest) {
                String km = distM >= 1000 ? String.format(java.util.Locale.US, "%.1f km", distM / 1000.0)
                        : Math.round(distM) + " m";
                int min = (int) Math.round(durS / 60.0);
                navGo.setText("Navegar  ·  " + km + " (" + min + " min)");
            } else {
                String np = navPkg();
                navGo.setText(np == null ? "Navegar" : "Navegar con " + navLabel(np));
            }
        }
    }

    void clearRoute() {
        hasDest = false;
        if (mapWeb != null) mapWeb.evaluateJavascript("clearRoute();", null);
        updateRouteUi(-1, -1);
    }

    /** Manda el destino a la app de mapas de fábrica (BYD Maps) para la navegación por voz. */
    void handoffToNav(String navPkg, String navLabel) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("geo:" + destLat + "," + destLon + "?q=" + destLat + "," + destLon));
            if (navPkg != null) i.setPackage(navPkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            // si la app elegida no acepta geo:, intentar sin paquete (que el sistema resuelva)
            try {
                Intent i2 = new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("geo:" + destLat + "," + destLon + "?q=" + destLat + "," + destLon));
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i2);
            } catch (Throwable t2) {
                Toast.makeText(this, "No se pudo abrir " + (navLabel == null ? "el mapa" : navLabel), Toast.LENGTH_LONG).show();
            }
        }
    }

    // Mapa en vivo: Leaflet (funciona en el WebView 74 de la troca) + calles OSM. Filtro oscuro solo en tema oscuro.
    // Gira estilo "proa arriba": al manejar, el mapa rota para que a donde vas siempre quede hacia arriba.
    // v0.40 (prueba "mapas v1"): 3 capas, idea tomada de SharkOS-CE (solo las FUENTES de mapa, no su código):
    //   0 Calles = OSM (como siempre) · 1 Topo = OpenTopoMap (curvas de nivel, brechas) ·
    //   2 Vector = OpenFreeMap (estilo oscuro/claro de verdad) con MapLibre GL JS v1 vía maplibre-gl-leaflet.
    //   El vectorial se carga SOLO si lo eliges; si la WebView 74 no puede (WebGL), avisa y regresa a Calles.
    static String mapHtml(double lat, double lon, boolean lightTheme, int style) {
        String bg = lightTheme ? "#E8EAED" : "#1C1E22";
        String tileClass = lightTheme ? "" : "dark";
        return "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<link rel='stylesheet' href='https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css'>"
            + "<style>html,body{margin:0;height:100%;overflow:hidden;background:" + bg + "}"
            // #m sobredimensionado 160% y centrado: al girar, las esquinas de la pantalla siempre quedan cubiertas.
            + "#m{position:absolute;top:-30%;left:-30%;width:160%;height:160%;background:" + bg + ";"
            + "transform-origin:50% 50%;transition:transform .6s ease-out}"
            + ".dark{filter:invert(1) hue-rotate(180deg) brightness(.95) contrast(.9) saturate(.6)}"
            // Topo NUNCA se invierte (quedaría ilegible); de noche solo se baja el brillo.
            + ".topoDk{filter:brightness(.72) contrast(1.05)}"
            // Atribución OSM fija (fuera del mapa, no gira).
            + "#attr{position:fixed;right:2px;bottom:2px;z-index:600;font:10px sans-serif;color:#999;"
            + "background:rgba(0,0,0,.5);padding:1px 5px;border-radius:3px}#attr a{color:#bbb;text-decoration:none}"
            + ".me{width:22px;height:22px;border-radius:50%;background:#3E6AE1;border:3px solid #fff;"
            + "box-shadow:0 0 0 10px rgba(62,106,225,.25)}"
            + ".dest{width:18px;height:18px;border-radius:50% 50% 50% 0;background:#E2483A;border:2px solid #fff;"
            + "transform:rotate(-45deg);box-shadow:0 1px 4px rgba(0,0,0,.5)}</style></head><body><div id='m'></div>"
            + "<div id='attr'>&copy; <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap</a></div>"
            + "<script src='https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js'></script><script>"
            + "var follow=true;var map=L.map('m',{zoomControl:false,attributionControl:false}).setView([" + lat + "," + lon + "],16);"
            + "var dk=" + (!lightTheme) + ",base=null,styleN=-1,glLoading=false;"
            + "var ATTR=[\"&copy; <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap</a>\","
            + "\"&copy; OpenStreetMap &middot; SRTM &middot; &copy; OpenTopoMap (CC-BY-SA)\","
            + "\"OpenFreeMap &copy; OpenMapTiles &middot; &copy; OpenStreetMap\"];"
            + "function mk(n){"
            + "if(n==1)return L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',{subdomains:'abc',maxNativeZoom:17,maxZoom:19,className:dk?'topoDk':''});"
            + "if(n==2)return L.maplibreGL({style:'https://tiles.openfreemap.org/styles/'+(dk?'dark':'positron'),attributionControl:false,interactive:false,preserveDrawingBuffer:true});"
            + "return L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,className:'" + tileClass + "'});}"
            // Carga MapLibre (770 KB) solo la primera vez que se elige Vector.
            + "function loadGL(cb){if(glLoading)return;glLoading=true;"
            + "var c=document.createElement('link');c.rel='stylesheet';c.href='https://unpkg.com/maplibre-gl@1.15.3/dist/maplibre-gl.css';document.head.appendChild(c);"
            + "var s=document.createElement('script');s.src='https://unpkg.com/maplibre-gl@1.15.3/dist/maplibre-gl.js';"
            + "s.onerror=function(){glLoading=false;cb(false);};"
            + "s.onload=function(){var p=document.createElement('script');p.src='https://unpkg.com/@maplibre/maplibre-gl-leaflet@0.0.17/leaflet-maplibre-gl.js';"
            + "p.onerror=function(){glLoading=false;cb(false);};p.onload=function(){glLoading=false;"
            + "cb(!!(window.maplibregl&&maplibregl.supported&&maplibregl.supported()&&L.maplibreGL));};document.head.appendChild(p);};"
            + "document.head.appendChild(s);}"
            + "function failGL(){setStyle(0);if(window.Shark&&Shark.styleFail)Shark.styleFail();}"
            + "function setStyle(n){if(n==styleN)return;"
            + "if(n==2&&!(window.maplibregl&&L.maplibreGL)){loadGL(function(ok){if(ok)setStyle(2);else failGL();});return;}"
            + "try{var nl=mk(n);if(base)map.removeLayer(base);base=nl.addTo(map);base.bringToBack&&base.bringToBack();styleN=n;"
            + "document.getElementById('attr').innerHTML=ATTR[n];"
            + "if(n==2&&base.getMaplibreMap){base.getMaplibreMap().on('error',function(e){console.log('GL '+(e&&e.error?e.error.message:e));});}"
            + "}catch(e){console.log('style '+e);if(n!=0)failGL();}}"
            + "setStyle(" + style + ");"
            + "var me=L.marker([" + lat + "," + lon + "],{icon:L.divIcon({className:'',html:\"<div class='me'></div>\",iconSize:[28,28],iconAnchor:[14,14]})}).addTo(map);"
            // Al arrastrar con el dedo: dejar de seguir y enderezar al norte para explorar cómodo.
            + "map.on('dragstart',function(){follow=false;north();});"
            + "function setPos(a,b){me.setLatLng([a,b]);if(follow)map.panTo([a,b],{animate:true,duration:0.8});}"
            // Giro proa-arriba: acumulamos el ángulo tomando siempre el camino más corto (evita el giro largo 350->10).
            + "var rot=0;function setHeading(d){if(!follow)return;var delta=((-d-rot+540)%360)-180;rot+=delta;"
            + "document.getElementById('m').style.transform='rotate('+rot+'deg)';}"
            + "function north(){rot=0;document.getElementById('m').style.transform='rotate(0deg)';}"
            // --- Ruta ---
            + "var dest=null,route=null;"
            + "function setDest(a,b){if(dest)map.removeLayer(dest);dest=L.marker([a,b],{icon:L.divIcon({className:'',"
            + "html:\"<div class='dest'></div>\",iconSize:[22,22],iconAnchor:[11,22]})}).addTo(map);}"
            + "function drawRoute(j){if(route)map.removeLayer(route);var c=JSON.parse(j);"
            + "route=L.polyline(c,{color:'#3E6AE1',weight:6,opacity:.9}).addTo(map);}"
            + "function clearRoute(){if(route){map.removeLayer(route);route=null;}if(dest){map.removeLayer(dest);dest=null;}}"
            // modo elegir destino: endereza al norte (coords correctas) y espera UN toque
            + "function routePick(){follow=false;north();map.once('click',function(e){setDest(e.latlng.lat,e.latlng.lng);"
            + "if(window.Shark)Shark.pick(e.latlng.lat,e.latlng.lng);});}"
            + "function fix(){map.invalidateSize(true);}"
            + "setTimeout(fix,100);setTimeout(fix,400);setTimeout(fix,1000);"
            + "window.addEventListener('resize',fix);"
            + "if(window.Shark)Shark.ready();"
            + "</script></body></html>";
    }

    void pushPos() {
        if (mapReady && mapWeb != null)
            mapWeb.evaluateJavascript("setPos(" + curLat + "," + curLon + ");", null);
    }

    // Prueba del giro del mapa sin manejar: `am start ... --ei demorotate 1` lo hace girar solo.
    void maybeRotateDemo() {
        if (getIntent().getIntExtra("demorotate", 0) != 1) return;
        final int[] h = {0};
        final android.os.Handler hh = new android.os.Handler();
        hh.postDelayed(new Runnable() { public void run() {
            if (mapWeb == null || !mapReady) return;
            mapWeb.evaluateJavascript("follow=true;setHeading(" + h[0] + ");", null);
            h[0] = (h[0] + 20) % 360;
            hh.postDelayed(this, 500);
        } }, 600);
    }

    double[] lastKnown() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            android.location.Location l = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
            return l == null ? null : new double[]{l.getLatitude(), l.getLongitude()};
        } catch (SecurityException e) { return null; }
    }

    public void onLocation(double lat, double lon) {
        curLat = lat; curLon = lon;
        pushPos();
    }

    // Debajo de esta velocidad el rumbo del GPS es basura (estando parado salta a cualquier lado),
    // así que NO giramos el mapa: se quedaría dando vueltas solo y marea.
    static final int HEADING_MIN_KMH = 8;

    public void onHeading(float bearingDeg, int kmh) {
        fxSetSpeed(kmh); // inclinar la lluvia según la velocidad (aun parado)
        if (!mapReady || mapWeb == null) return;
        if (bearingDeg < 0 || kmh < HEADING_MIN_KMH) return; // parado / sin rumbo fiable: dejar el mapa quieto
        mapWeb.evaluateJavascript("setHeading(" + bearingDeg + ");", null);
    }

    // ---------- Barra de accesos rápidos (configurable) ----------
    SharedPreferences prefs() { return getSharedPreferences("shark", MODE_PRIVATE); }

    static final String[] MAP_STYLES = {"🗺️ Calles", "⛰️ Topo", "✨ Vector"};
    TextView styleBtn;
    int mapStyle() { int n = prefs().getInt("mapStyle", 0); return (n < 0 || n >= MAP_STYLES.length) ? 0 : n; }

    // Cada item = {paquete, actividad ("" = la principal), etiqueta ("" = la del sistema)}
    List<String[]> dockItems() {
        List<String[]> out = new ArrayList<String[]>();
        String s = prefs().getString("dock", null);
        if (s == null) { // por defecto: la lista curada DOCK
            for (String[] it : DOCK) out.add(new String[]{it[1], it[2] == null ? "" : it[2], it[0]});
            return out;
        }
        for (String line : s.split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\u0001", -1);
            out.add(new String[]{p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : ""});
        }
        return out;
    }

    void saveDock(List<String[]> items) {
        StringBuilder sb = new StringBuilder();
        for (String[] it : items)
            sb.append(it[0]).append('\u0001').append(it[1] == null ? "" : it[1])
              .append('\u0001').append(it[2] == null ? "" : it[2]).append('\n');
        prefs().edit().putString("dock", sb.toString()).apply();
    }

    String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) { return pkg; }
    }

    void rebuildDock() {
        if (dockHolder == null) return;
        dockHolder.removeAllViews();
        dockHolder.addView(buildDockRow());
    }

    View buildDockRow() {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setFillViewport(true);
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(8), 0, 0);
        PackageManager pm = getPackageManager();
        final java.util.List<String[]> shown = new ArrayList<String[]>(); // apps realmente visibles (para reordenar/guardar)
        for (String[] it : dockItems()) {
            final String pkg = it[0];
            final String act = (it[1] == null || it[1].isEmpty()) ? null : it[1];
            Drawable icon;
            try { icon = pm.getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException e) { continue; }
            shown.add(it);
            final int index = shown.size() - 1;
            final String lbl = (it[2] == null || it[2].isEmpty()) ? labelOf(pkg) : it[2];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(rounded(PANEL, 16));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            cell.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));
            TextView t = text(lbl, 12, TEXT, false);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(t, new LinearLayout.LayoutParams(dp(92), -2));
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { open(pkg, act, lbl); }
            });
            // Mantener presionado + arrastrar para reordenar.
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    v.startDragAndDrop(android.content.ClipData.newPlainText("i", String.valueOf(index)),
                            new View.DragShadowBuilder(v), Integer.valueOf(index), 0);
                    v.setVisibility(View.INVISIBLE);
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(100), -1);
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(cell, lp);
        }
        // Reordenar: al soltar, mover la app a la posición donde se soltó y guardar.
        row.setOnDragListener(new View.OnDragListener() {
            public boolean onDrag(View host, android.view.DragEvent e) {
                switch (e.getAction()) {
                    case android.view.DragEvent.ACTION_DRAG_STARTED:
                    case android.view.DragEvent.ACTION_DRAG_LOCATION:
                    case android.view.DragEvent.ACTION_DRAG_ENTERED:
                    case android.view.DragEvent.ACTION_DRAG_EXITED:
                        return true;
                    case android.view.DragEvent.ACTION_DROP: {
                        Object st = e.getLocalState();
                        if (st instanceof Integer) {
                            int from = (Integer) st;
                            int to = shown.size(); // por defecto: al final
                            for (int i = 0; i < shown.size() && i < row.getChildCount(); i++) {
                                View c = row.getChildAt(i);
                                if (e.getX() < c.getLeft() + c.getWidth() / 2f) { to = i; break; }
                            }
                            String[] moved = shown.remove(from);
                            if (to > from) to--;                 // el hueco desplaza los índices
                            if (to < 0) to = 0;
                            if (to > shown.size()) to = shown.size();
                            shown.add(to, moved);
                            saveDock(shown);
                        }
                        rebuildDock();
                        return true;
                    }
                    case android.view.DragEvent.ACTION_DRAG_ENDED:
                        if (!e.getResult()) rebuildDock(); // se soltó fuera: restaurar
                        return true;
                    default: return true;
                }
            }
        });
        // Celda para editar la selección de apps
        LinearLayout edit = new LinearLayout(this);
        edit.setOrientation(LinearLayout.VERTICAL);
        edit.setGravity(Gravity.CENTER);
        edit.setBackground(rounded(CARD, 16));
        TextView plus = text("＋", 26, ACCENT, true);
        plus.setGravity(Gravity.CENTER);
        edit.addView(plus, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView el = text("Editar", 12, MUTED, false);
        el.setGravity(Gravity.CENTER);
        edit.addView(el);
        edit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showAppPicker(); }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(100), -1);
        elp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(edit, elp);

        sv.addView(row, new FrameLayout.LayoutParams(-2, -1));
        return sv;
    }

    // Selector de apps: elige cuáles salen en el dock (agregar/quitar).
    void showAppPicker() {
        final List<String[]> sel = dockItems();
        final LinkedHashSet<String> pkgs = new LinkedHashSet<String>();
        for (String[] it : sel) pkgs.add(it[0]);

        final FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB0000000);
        scrim.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(PANEL, 20));
        card.setPadding(dp(20), dp(18), dp(20), dp(14));
        card.setClickable(true); // no dejar pasar el toque al fondo (que cierra)

        TextView title = text("Apps del tablero", 18, TEXT, true);
        card.addView(title);
        TextView sub = text("Toca para agregar o quitar", 13, MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(10));
        card.addView(sub);

        ScrollView scroll = new ScrollView(this);
        final LinearLayout listL = new LinearLayout(this);
        listL.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listL);

        PackageManager pm = getPackageManager();
        Intent mainI = new Intent(Intent.ACTION_MAIN);
        mainI.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainI, 0);
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        final List<String[]> all = new ArrayList<String[]>(); // {pkg, label}
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue; // no nos listamos a nosotros
            if (!seen.add(pkg)) continue;
            all.add(new String[]{pkg, ri.loadLabel(pm).toString()});
        }
        Collections.sort(all, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return a[1].compareToIgnoreCase(b[1]); }
        });

        for (String[] app : all) {
            final String pkg = app[0];
            final String lbl = app[1];
            final LinearLayout rowv = new LinearLayout(this);
            rowv.setOrientation(LinearLayout.HORIZONTAL);
            rowv.setGravity(Gravity.CENTER_VERTICAL);
            rowv.setPadding(dp(6), dp(8), dp(6), dp(8));
            ImageView iv = new ImageView(this);
            try { iv.setImageDrawable(pm.getApplicationIcon(pkg)); } catch (Throwable t) {}
            rowv.addView(iv, new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView name = text(lbl, 15, TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
            nlp.leftMargin = dp(12);
            rowv.addView(name, nlp);
            final TextView check = text(pkgs.contains(pkg) ? "✔" : "➕", 18, pkgs.contains(pkg) ? ACCENT : MUTED, true);
            rowv.addView(check);
            rowv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (pkgs.contains(pkg)) {
                        pkgs.remove(pkg);
                        for (int i = sel.size() - 1; i >= 0; i--) if (sel.get(i)[0].equals(pkg)) sel.remove(i);
                        check.setText("➕"); check.setTextColor(MUTED);
                    } else {
                        pkgs.add(pkg);
                        sel.add(new String[]{pkg, "", lbl});
                        check.setText("✔"); check.setTextColor(ACCENT);
                    }
                }
            });
            listL.addView(rowv);
        }

        int cardH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.80f);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView done = text("Listo", 16, 0xFFFFFFFF, true);
        done.setGravity(Gravity.CENTER);
        done.setBackground(rounded(ACCENT, 14));
        done.setPadding(dp(20), dp(12), dp(20), dp(12));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(12);
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveDock(sel); rootF.removeView(scrim); rebuildDock(); }
        });
        card.addView(done, dlp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(460), cardH, Gravity.CENTER);
        scrim.addView(card, clp);
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveDock(sel); rootF.removeView(scrim); rebuildDock(); }
        });
        rootF.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }

    boolean isInstalled(String pkg) {
        try { getPackageManager().getApplicationInfo(pkg, 0); return true; } catch (Throwable t) { return false; }
    }

    // App de navegación elegida (o Waze por defecto si está, o null).
    String navPkg() {
        String p = prefs().getString("navPkg", null);
        if (p != null && isInstalled(p)) return p;
        if (isInstalled(FACTORY_NAV)) return FACTORY_NAV;           // mapas de fábrica BYD (preferido)
        if (isInstalled("com.waze.electro")) return "com.waze.electro";
        if (isInstalled("com.waze")) return "com.waze";
        return null;
    }

    /** Nombre amigable para el botón Navegar (Telenav de fábrica reporta un label feo). */
    String navLabel(String pkg) {
        if (FACTORY_NAV.equals(pkg)) return "Mapas BYD";
        return labelOf(pkg);
    }

    /** Una sola vez: si la navegación o el dock apuntaban a Waze/Electro, pasar a los mapas de fábrica. */
    void migrateNavToFactory() {
        SharedPreferences sp = prefs();
        if (sp.getBoolean("navMigratedFactory2", false)) return;
        SharedPreferences.Editor ed = sp.edit().putBoolean("navMigratedFactory2", true);
        String np = sp.getString("navPkg", null);
        if ("com.waze.electro".equals(np) || "com.waze".equals(np)) ed.remove("navPkg"); // -> cae a FACTORY_NAV
        String dock = sp.getString("dock", null);
        if (dock != null && isInstalled(FACTORY_NAV)) {
            StringBuilder nb = new StringBuilder();
            for (String line : dock.split("\n")) {
                if (line.isEmpty()) continue;
                String[] p = line.split("\u0001", -1);
                String pkg = p[0], act = p.length > 1 ? p[1] : "", lbl = p.length > 2 ? p[2] : "";
                // Waze for Electro (o el que ya quedó a medias con icono de fábrica y nombre "Waze") -> Mapas de fábrica
                if ("com.waze.electro".equals(pkg) || "com.waze".equals(pkg)
                        || (FACTORY_NAV.equals(pkg) && ("Waze".equals(lbl) || "Waze for Electro".equals(lbl)))) {
                    pkg = FACTORY_NAV; act = ""; lbl = "Mapas";
                }
                if (nb.length() > 0) nb.append("\n");
                nb.append(pkg).append("\u0001").append(act).append("\u0001").append(lbl);
            }
            ed.putString("dock", nb.toString());
        }
        ed.apply();
    }

    interface AppPick { void onPick(String pkg, String label); }

    // Lista de apps instaladas con selección única (para elegir la app de navegación).
    void chooseApp(final AppPick cb) {
        final FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB0000000);
        scrim.setClickable(true);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(PANEL, 20));
        card.setPadding(dp(20), dp(18), dp(20), dp(14));
        card.setClickable(true);
        card.addView(text("Elige una app", 18, TEXT, true));
        ScrollView scroll = new ScrollView(this);
        LinearLayout listL = new LinearLayout(this);
        listL.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listL);

        PackageManager pm = getPackageManager();
        Intent mainI = new Intent(Intent.ACTION_MAIN);
        mainI.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainI, 0);
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        List<String[]> all = new ArrayList<String[]>();
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (!seen.add(pkg)) continue;
            all.add(new String[]{pkg, ri.loadLabel(pm).toString()});
        }
        Collections.sort(all, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return a[1].compareToIgnoreCase(b[1]); }
        });
        for (String[] app : all) {
            final String pkg = app[0];
            final String lbl = app[1];
            LinearLayout rowv = new LinearLayout(this);
            rowv.setGravity(Gravity.CENTER_VERTICAL);
            rowv.setPadding(dp(6), dp(8), dp(6), dp(8));
            ImageView iv = new ImageView(this);
            try { iv.setImageDrawable(pm.getApplicationIcon(pkg)); } catch (Throwable t) {}
            rowv.addView(iv, new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView name = text(lbl, 15, TEXT, false);
            name.setSingleLine(true);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
            nlp.leftMargin = dp(12);
            rowv.addView(name, nlp);
            rowv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { rootF.removeView(scrim); cb.onPick(pkg, lbl); }
            });
            listL.addView(rowv);
        }
        int cardH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.80f);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, cardH - dp(70)));
        scrim.addView(card, new FrameLayout.LayoutParams(dp(440), -2, Gravity.CENTER));
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { rootF.removeView(scrim); }
        });
        rootF.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }

    // Colores de pintura para la troca (0xRRGGBB). El primero es el verde salvia de fábrica.
    static final int[] TRUCK_COLORS = {
        0x929A87, 0x2B2F36, 0xE6E8EA, 0xB23A32, 0x2E5AA8, 0x3B6B4A, 0x8A6A3B, 0xC9A23B, 0x6A5ACD, 0xD98A2B
    };

    boolean settingsNeedsRebuild;

    // Encabezado de grupo en Ajustes (acento + línea divisoria).
    void grpHeader(LinearLayout card, String t) {
        TextView h = text(t.toUpperCase(), 13, ACCENT, true);
        h.setPadding(0, dp(22), 0, dp(6));
        card.addView(h);
        View div = new View(this);
        div.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, dp(1));
        l.bottomMargin = dp(10);
        card.addView(div, l);
    }
    TextView itemLabel(String t) {
        TextView v = text(t, 15, TEXT, true);
        v.setPadding(0, dp(10), 0, dp(2));
        return v;
    }
    LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        return p;
    }

    void showSettings() {
        settingsNeedsRebuild = false;
        final FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB0000000);
        scrim.setClickable(true);

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(PANEL, 20));
        card.setPadding(dp(24), dp(20), dp(24), dp(18));
        card.setClickable(true);

        card.addView(text("Ajustes", 20, TEXT, true));

        // ===== APARIENCIA =====
        grpHeader(card, "Apariencia");
        // Launcher por defecto (siempre abre el selector del sistema).
        final boolean def = isDefaultHome();
        final TextView homeBtn = text(def ? "✔ Launcher por defecto  ·  abrir selector" : "Poner como launcher por defecto",
                15, def ? ACCENT : 0xFFFFFFFF, true);
        homeBtn.setGravity(Gravity.CENTER);
        homeBtn.setBackground(rounded(def ? CARD : ACCENT, 12));
        homeBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        homeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestDefaultHome(); }
        });
        card.addView(homeBtn, rowLp());

        // Nombre
        card.addView(itemLabel("Nombre en el panel"));
        card.addView(text("El texto grande de arriba a la izquierda (por defecto BYD SHARK).", 12, MUTED, false));
        final android.widget.EditText nameEt = new android.widget.EditText(this);
        nameEt.setSingleLine(true);
        nameEt.setText(vehicleName());
        nameEt.setHint("BYD SHARK");
        nameEt.setTextColor(TEXT);
        nameEt.setHintTextColor(MUTED);
        LinearLayout.LayoutParams nelp = new LinearLayout.LayoutParams(-1, -2);
        nelp.topMargin = dp(6);
        card.addView(nameEt, nelp);
        TextView nameSave = text("Guardar nombre", 14, 0xFFFFFFFF, true);
        nameSave.setBackground(rounded(ACCENT, 12));
        nameSave.setGravity(Gravity.CENTER);
        nameSave.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams nslp = new LinearLayout.LayoutParams(-2, -2);
        nslp.topMargin = dp(8);
        nameSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String nm = nameEt.getText().toString().trim();
                prefs().edit().putString("vehicleName", nm.isEmpty() ? "BYD SHARK" : nm).apply();
                if (nameTv != null) nameTv.setText(vehicleName());
                nameEt.clearFocus();
                android.widget.Toast.makeText(MainActivity.this, "Nombre guardado", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(nameSave, nslp);

        // Color de la camioneta
        card.addView(itemLabel("Color de la camioneta"));
        LinearLayout sw1 = new LinearLayout(this);
        sw1.setOrientation(LinearLayout.HORIZONTAL);
        int cur = prefs().getInt("truckColor", TRUCK_COLORS[0]);
        for (int i = 0; i < TRUCK_COLORS.length; i++) {
            final int color = TRUCK_COLORS[i];
            final View sw = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF000000 | color);
            g.setStroke(dp(color == cur ? 3 : 1), color == cur ? ACCENT : 0x55888888);
            sw.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.setMargins(dp(4), 0, dp(4), 0);
            sw.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prefs().edit().putInt("truckColor", color).apply();
                    if (truck3d != null) truck3d.setPaint(color);
                    LinearLayout parent = (LinearLayout) v.getParent();
                    for (int k = 0; k < parent.getChildCount(); k++) {
                        View c = parent.getChildAt(k);
                        GradientDrawable gg = (GradientDrawable) c.getBackground();
                        gg.setStroke(dp(c == v ? 3 : 1), c == v ? ACCENT : 0x55888888);
                    }
                }
            });
            sw1.addView(sw, lp);
        }
        HorizontalScrollView swScroll = new HorizontalScrollView(this);
        swScroll.setHorizontalScrollBarEnabled(false);
        swScroll.addView(sw1);
        card.addView(swScroll, rowLp());

        // ===== PANTALLA DERECHA =====
        grpHeader(card, "Pantalla derecha");
        card.addView(text("Qué mostrar a la derecha. Las fotos van en /sdcard/SharkHome/fotos.", 12, MUTED, false));
        final TextView rmBtn = text("", 15, ACCENT, true);
        rmBtn.setGravity(Gravity.CENTER);
        rmBtn.setBackground(rounded(CARD, 12));
        rmBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        rmBtn.setText(showPhotos() ? "Mostrando: FOTOS  ·  tocar para Mapa" : "Mostrando: MAPA  ·  tocar para Fotos");
        card.addView(rmBtn, rowLp());
        // Tiempo por imagen / foto estática (solo visible en modo Fotos).
        final TextView piBtn = text("", 15, ACCENT, true);
        piBtn.setGravity(Gravity.CENTER);
        piBtn.setBackground(rounded(CARD, 12));
        piBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        piBtn.setText("Tiempo por imagen: " + photoIntervalLabel() + "  ·  tocar para cambiar");
        piBtn.setVisibility(showPhotos() ? View.VISIBLE : View.GONE);
        card.addView(piBtn, rowLp());
        piBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().putInt("photoInterval", nextPhotoInterval()).apply();
                piBtn.setText("Tiempo por imagen: " + photoIntervalLabel() + "  ·  tocar para cambiar");
                if (photos != null) photos.setIntervalSec(photoIntervalSec());
            }
        });
        rmBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().putString("rightMode", showPhotos() ? "map" : "fotos").apply();
                rmBtn.setText(showPhotos() ? "Mostrando: FOTOS  ·  tocar para Mapa" : "Mostrando: MAPA  ·  tocar para Fotos");
                piBtn.setVisibility(showPhotos() ? View.VISIBLE : View.GONE);
                applyRightMode();
            }
        });

        // ===== EFECTOS DE CLIMA =====
        grpHeader(card, "Efectos de clima");
        card.addView(text("Lluvia (inclinada según la velocidad), nieve, sol, nubes y niebla según el clima.", 12, MUTED, false));
        final TextView fxBtn = text("", 15, ACCENT, true);
        fxBtn.setGravity(Gravity.CENTER);
        fxBtn.setBackground(rounded(CARD, 12));
        fxBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        updateWeatherFxBtn(fxBtn);
        card.addView(fxBtn, rowLp());
        fxBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().putBoolean("weatherFx", !weatherFxOn()).apply();
                updateWeatherFxBtn(fxBtn);
                applyWeatherFx();
            }
        });
        final TextView fxScopeBtn = text("", 15, ACCENT, true);
        fxScopeBtn.setGravity(Gravity.CENTER);
        fxScopeBtn.setBackground(rounded(CARD, 12));
        fxScopeBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        updateFxScopeBtn(fxScopeBtn);
        card.addView(fxScopeBtn, rowLp());
        fxScopeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().putString("fxScope", fxPanelOnly() ? "full" : "panel").apply();
                updateFxScopeBtn(fxScopeBtn);
                applyWeatherFx();
            }
        });
        final TextView fxDenBtn = text("", 15, ACCENT, true);
        fxDenBtn.setGravity(Gravity.CENTER);
        fxDenBtn.setBackground(rounded(CARD, 12));
        fxDenBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        updateFxDensityBtn(fxDenBtn);
        card.addView(fxDenBtn, rowLp());
        fxDenBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().putString("fxDensity", nextDensity()).apply();
                updateFxDensityBtn(fxDenBtn);
                applyWeatherFx();
            }
        });

        // ===== PERMISOS Y DATOS =====
        grpHeader(card, "Permisos y datos");
        card.addView(itemLabel("Datos del vehículo (ADB)"));
        final boolean adbOk = vlog != null && vlog.canRead();
        card.addView(text(adbOk ? "Activo: leyendo puertas, luces y kilometraje."
                : "Permite el ADB local para leer puertas/luces/km y la música (toca “Permitir” en la troca).",
                12, MUTED, false));
        if (!adbOk) {
            TextView adbBtn = text("Activar datos del vehículo", 14, 0xFFFFFFFF, true);
            adbBtn.setGravity(Gravity.CENTER);
            adbBtn.setBackground(rounded(ACCENT, 12));
            adbBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
            adbBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Toast.makeText(MainActivity.this, "Conectando…", Toast.LENGTH_SHORT).show();
                    activateVehicleAccess(true);
                }
            });
            card.addView(adbBtn, rowLp());
        }
        card.addView(itemLabel("Detección de puertas y luces"));
        card.addView(text("Abre puertas/prende luces en el modelo 3D. Si a veces se traba “abierta”, apágala.", 12, MUTED, false));
        final TextView dlBtn = text("", 15, ACCENT, true);
        dlBtn.setGravity(Gravity.CENTER);
        dlBtn.setBackground(rounded(CARD, 12));
        dlBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        updateDoorsBtn(dlBtn);
        card.addView(dlBtn, rowLp());
        dlBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = !doorsOn();
                prefs().edit().putBoolean("doorsOn", on).apply();
                if (!on) clearDoorsLights();
                updateDoorsBtn(dlBtn);
            }
        });

        // ===== SISTEMA =====
        grpHeader(card, "Sistema");
        card.addView(itemLabel("Asistente de voz"));
        card.addView(text("El despertar por voz de BYD que se prende solo con ruido o música. Apágalo si molesta.", 12, MUTED, false));
        final TextView vwBtn = text("", 15, ACCENT, true);
        vwBtn.setGravity(Gravity.CENTER);
        vwBtn.setBackground(rounded(CARD, 12));
        vwBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        updateVoiceBtn(vwBtn);
        card.addView(vwBtn, rowLp());
        vwBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setVoiceWake(!voiceWakeOn(), vwBtn); }
        });
        card.addView(itemLabel("App de navegación"));
        card.addView(text("La que abre el botón “Navegar” del mapa.", 12, MUTED, false));
        LinearLayout navRow = new LinearLayout(this);
        navRow.setGravity(Gravity.CENTER_VERTICAL);
        navRow.setPadding(0, dp(8), 0, 0);
        String np = navPkg();
        final TextView navVal = text(np == null ? "Ninguna" : navLabel(np), 15, np == null ? MUTED : ACCENT, true);
        navRow.addView(navVal, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView change = text("Cambiar", 14, 0xFFFFFFFF, true);
        change.setBackground(rounded(ACCENT, 12));
        change.setPadding(dp(16), dp(8), dp(16), dp(8));
        change.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                chooseApp(new AppPick() {
                    public void onPick(String pkg, String label) {
                        prefs().edit().putString("navPkg", pkg).apply();
                        navVal.setText(label);
                        navVal.setTextColor(ACCENT);
                        settingsNeedsRebuild = true;
                    }
                });
            }
        });
        navRow.addView(change);
        card.addView(navRow);

        // --- Crédito (aquí sí visible) ---
        TextView credit = text("ⓘ  Hecho por @El.BioNiico", 15, TEXT, true);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, dp(22), 0, dp(2));
        card.addView(credit);
        TextView credit2 = text("Sugerencias por Instagram", 13, MUTED, false);
        credit2.setGravity(Gravity.CENTER);
        card.addView(credit2);

        // --- Listo ---
        TextView done = text("Listo", 16, 0xFFFFFFFF, true);
        done.setGravity(Gravity.CENTER);
        done.setBackground(rounded(ACCENT, 14));
        done.setPadding(dp(20), dp(12), dp(20), dp(12));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(16);
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                rootF.removeView(scrim);
                if (settingsNeedsRebuild) recreate(); // para actualizar el botón de navegación
            }
        });
        card.addView(done, dlp);

        ScrollView outer = new ScrollView(this);
        outer.addView(card);
        int cardH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.88f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(480), -2, Gravity.CENTER);
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(outer, new FrameLayout.LayoutParams(dp(480), Math.min(cardH, dp(560)), Gravity.CENTER));
        scrim.addView(wrap, new FrameLayout.LayoutParams(-1, -1));
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                rootF.removeView(scrim);
                if (settingsNeedsRebuild) recreate();
            }
        });
        rootF.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }

    void open(String pkg, String activity, String label) {
        try {
            Intent i;
            if (activity != null) {
                i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(new ComponentName(pkg, activity));
            } else {
                i = getPackageManager().getLaunchIntentForPackage(pkg);
            }
            if (i == null) throw new RuntimeException("sin actividad");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir " + label, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Dibujos ----------
    static class TruckView extends View {
        final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tire = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);

        TruckView(Context c) {
            super(c);
            body.setColor(0xFFD9DCE1);
            glass.setColor(0xFF2A3038);
            tire.setColor(0xFF0E0F11);
            rim.setColor(0xFF6B7078);
            rim.setStyle(Paint.Style.STROKE);
            shadow.setColor(0x55000000);
            light.setColor(0xFFE8F0FF);
        }

        @Override
        protected void onDraw(Canvas c) {
            // Silueta lateral de pickup en una caja de 100 x 40 unidades
            float w = getWidth(), h = getHeight();
            float s = Math.min(w / 108f, h / 46f);
            float ox = (w - 100 * s) / 2f, oy = (h - 40 * s) / 2f;
            rim.setStrokeWidth(1.6f * s);

            c.drawOval(new RectF(ox + 4 * s, oy + 35 * s, ox + 98 * s, oy + 40 * s), shadow);

            Path p = new Path();
            p.moveTo(ox + 2 * s, oy + 31 * s);
            p.lineTo(ox + 2 * s, oy + 17 * s);
            p.lineTo(ox + 49 * s, oy + 17 * s);
            p.lineTo(ox + 51 * s, oy + 6 * s);
            p.lineTo(ox + 71 * s, oy + 5 * s);
            p.lineTo(ox + 81 * s, oy + 15 * s);
            p.lineTo(ox + 95 * s, oy + 17 * s);
            p.lineTo(ox + 98 * s, oy + 21 * s);
            p.lineTo(ox + 98 * s, oy + 31 * s);
            p.close();
            c.drawPath(p, body);

            Path g = new Path();
            g.moveTo(ox + 53 * s, oy + 8 * s);
            g.lineTo(ox + 70 * s, oy + 7.5f * s);
            g.lineTo(ox + 78 * s, oy + 15 * s);
            g.lineTo(ox + 53 * s, oy + 15 * s);
            g.close();
            c.drawPath(g, glass);
            c.drawRect(ox + 63 * s, oy + 7.5f * s, ox + 64 * s, oy + 15 * s, body);

            c.drawRect(ox + 94 * s, oy + 18.5f * s, ox + 98 * s, oy + 20 * s, light);

            for (float cx : new float[]{20, 80}) {
                c.drawCircle(ox + cx * s, oy + 31 * s, 7.5f * s, body);
                c.drawCircle(ox + cx * s, oy + 31 * s, 6.5f * s, tire);
                c.drawCircle(ox + cx * s, oy + 31 * s, 3.6f * s, rim);
            }
        }
    }

    static class MapView extends View {
        final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint block = new Paint();
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);

        MapView(Context c) {
            super(c);
            road.setColor(0xFF2B2E35);
            road.setStrokeCap(Paint.Cap.ROUND);
            big.setColor(0xFF3A4152);
            big.setStrokeCap(Paint.Cap.ROUND);
            big.setStyle(Paint.Style.STROKE);
            block.setColor(0xFF22252A);
            dot.setColor(ACCENT);
            halo.setColor(0x403E6AE1);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float u = Math.min(w, h) / 12f;
            for (int i = 0; i < 14; i++)
                for (int j = 0; j < 10; j++)
                    if (((i * 7 + j * 3) % 5) < 2)
                        c.drawRect(i * u * 1.3f + u * 0.2f, j * u * 1.3f + u * 0.2f,
                                i * u * 1.3f + u * 1.1f, j * u * 1.3f + u * 1.1f, block);
            road.setStrokeWidth(u * 0.18f);
            for (float x = u * 1.25f; x < w; x += u * 1.3f) c.drawLine(x, 0, x, h, road);
            for (float y = u * 1.25f; y < h; y += u * 1.3f) c.drawLine(0, y, w, y, road);
            big.setStrokeWidth(u * 0.42f);
            Path hw = new Path();
            hw.moveTo(-u, h * 0.85f);
            hw.cubicTo(w * 0.3f, h * 0.7f, w * 0.45f, h * 0.35f, w + u, h * 0.18f);
            c.drawPath(hw, big);
            float cx = w * 0.5f, cy = h * 0.55f;
            c.drawCircle(cx, cy, u * 1.1f, halo);
            dot.setColor(Color.WHITE);
            c.drawCircle(cx, cy, u * 0.42f, dot);
            dot.setColor(ACCENT);
            c.drawCircle(cx, cy, u * 0.32f, dot);
        }
    }
}
