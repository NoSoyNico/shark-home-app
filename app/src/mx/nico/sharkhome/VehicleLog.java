package mx.nico.sharkhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mx.nico.sharkhome.cloud.VehicleStatus;

/**
 * Lee el estado del vehículo del LOG DEL SISTEMA (logcat), igual que Electro.
 * La troca escribe su telemetría como líneas `BYDAuto<X>Device: postEvent ... event_type =<idHex>, value = <v>`.
 * Con el acceso READ_LOGS (otorgado una vez con pm grant) podemos leer todo eso. SOLO LECTURA.
 *
 * Los ids se decodifican con las propiedades de DiCarServer (propiedades_dicarserver.txt).
 */
public class VehicleLog {
    static final String TAG = "SharkLog";

    public interface Ui {
        void onVehicleLog(VehicleStatus s);   // puertas/luces del log (se fusiona con la nube)
        void onLogError(String msg);
    }

    // ---- ids (hex en minúsculas, como salen en el log) ----
    // Puertas (1=abierta / 0=cerrada; se verifica en vivo)
    static final String DOOR_LF = "29400008", DOOR_RF = "2940000a", DOOR_LR = "2940000c", DOOR_RR = "2940000e";
    static final String DOOR_LF_DD = "26100014", DOOR_RF_DD = "26100015", DOOR_LR_DD = "26100016", DOOR_RR_DD = "26100017";
    static final String BACK_DOOR = "15d0002b", BACK_DOOR2 = "29400015";
    // Luces (1=encendida)
    static final String L_SIDE = "38a00008", L_LOW = "38a0000a", L_DRL_L = "4fe0000e", L_DRL_R = "4fe00014",
            L_TURN = "38a0002c", L_TURN_SW = "1330002c";
    // Batería / rango / consumo
    static final String SOC = "43a00028", ELEC_PCT = "4a505038", SOC_PCT = "44400030",
            ELEC_RANGE = "4a50203e", FUEL_PCT = "4a507040", FUEL_RANGE = "4a504038",
            MILEAGE = "4a502010", OUT_TEMP = "4a503040", GEAR = "34200020", SPEED = "0ed00047";
    // Llantas (presión por rueda)
    static final String TYRE_LF = "99000124", TYRE_RF = "99000128", TYRE_LR = "9900012c", TYRE_RR = "99000130";

    final Context ctx;
    final Ui ui;
    final Handler main = new Handler(Looper.getMainLooper());
    final Map<String, Double> values = new HashMap<String, Double>(); // id -> último valor
    volatile boolean running;
    Thread reader;
    Process proc;
    long lastPush;

    VehicleLog(Context c, Ui u) { ctx = c.getApplicationContext(); ui = u; }

    /** ¿Tenemos READ_LOGS? (sin él, logcat solo ve nuestras propias líneas y no sirve). */
    public boolean canRead() {
        return ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static final Pattern LINE = Pattern.compile(
            "BYDAuto[A-Za-z]+Device: postEvent device_type: \\d+, event_type =([0-9a-f]+), value = ([-0-9.]+)");
    // Kilometraje total: el sistema lo escribe aparte (no como postEvent). El valor viene en décimas de km.
    static final Pattern MILE = Pattern.compile("onTotalMileageValueChanged value=(\\d+)");
    volatile Double odoKm; // odómetro en km (value/10)

    void start() {
        if (running) return;
        running = true;
        reader = new Thread(new Runnable() { public void run() { loop(); } }, "SharkLog");
        reader.start();
    }

    void loop() {
        if (!canRead()) {
            main.post(new Runnable() { public void run() { ui.onLogError("Falta el acceso READ_LOGS (otorgar con pm grant)"); } });
            running = false;
            return;
        }
        try {
            // Solo las etiquetas del vehículo; el resto en silencio (menos CPU).
            proc = new ProcessBuilder(
                    "logcat", "-v", "threadtime",
                    "BYDAutoBodyworkDevice:D", "BYDAutoLightDevice:D", "BYDAutoStatisticDevice:D",
                    "BYDAutoChargingDevice:D", "BYDAutoTyreDevice:D", "BYDAutoInstrumentDevice:D",
                    "BYDAutoSpeedDevice:D", "BYDAutoGearboxDevice:D", "AbsBYDAutoStatisticListener:D", "*:S")
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while (running && (line = r.readLine()) != null) {
                Matcher m = LINE.matcher(line);
                if (!m.find()) {
                    Matcher mk = MILE.matcher(line);
                    if (mk.find()) {
                        try { odoKm = Double.parseDouble(mk.group(1)) / 10.0; } catch (NumberFormatException e) {}
                    }
                    continue;
                }
                try {
                    double val = Double.parseDouble(m.group(2));
                    values.put(m.group(1), val);
                    if (m.group(1).equals(SOC)) feedSoc(val);
                } catch (NumberFormatException e) { continue; }
                long now = System.currentTimeMillis();
                if (now - lastPush > 700) { lastPush = now; pushSnapshot(); } // no saturar la pantalla
            }
        } catch (Throwable t) {
            // Al pausar/relanzar la app interrumpimos la lectura a propósito: eso NO es un error que mostrar.
            if (!running || t instanceof InterruptedException
                    || t instanceof java.io.InterruptedIOException) {
                Log.i(TAG, "logcat detenido (normal): " + t);
                return;
            }
            Log.w(TAG, "logcat: " + t);
            final String msg = String.valueOf(t);
            main.post(new Runnable() { public void run() { ui.onLogError("No se pudo leer el log: " + msg); } });
        }
    }

    Double v(String id) { return values.get(id); }

    /** v(id) interpretado como abierto(true)/cerrado(false): 0 = cerrado, cualquier otro = abierto. */
    Boolean door(String... ids) {
        for (String id : ids) { Double d = values.get(id); if (d != null) return d != 0; }
        return null;
    }

    Boolean lightOn(String id) { Double d = values.get(id); return d == null ? null : d != 0; }

    // El SOC del log (ESTIMATE_SOC_V1) oscila; suavizamos con la mediana de las últimas lecturas.
    final java.util.ArrayDeque<Double> socHist = new java.util.ArrayDeque<Double>();

    void feedSoc(double v) {
        if (v < 0 || v > 100) return;
        synchronized (socHist) {
            socHist.addLast(v);
            while (socHist.size() > 9) socHist.removeFirst();
        }
    }

    Double battery() {
        // El % exacto del tablero (ELEC_PERCENTAGE) NO fluye por el log; el SOC_V1 que sí fluye es
        // demasiado ruidoso para mostrarlo como %. Mejor no mostrar batería desde el log (que la dé la nube).
        for (String id : new String[]{ELEC_PCT, SOC_PCT}) {
            Double d = values.get(id);
            if (d != null && d >= 0 && d <= 100) return d;
        }
        return null;
    }

    void pushSnapshot() {
        final VehicleStatus s = new VehicleStatus();
        s.batteryPct = battery();
        s.evRangeKm = pos(v(ELEC_RANGE));
        s.fuelPct = pos(v(FUEL_PCT));
        s.fuelRangeKm = pos(v(FUEL_RANGE));
        s.odometerKm = odoKm != null ? odoKm : pos(v(MILEAGE)); // odómetro real del log (onTotalMileageValueChanged)
        s.doorLF = door(DOOR_LF, DOOR_LF_DD);
        s.doorRF = door(DOOR_RF, DOOR_RF_DD);
        s.doorLR = door(DOOR_LR, DOOR_LR_DD);
        s.doorRR = door(DOOR_RR, DOOR_RR_DD);
        s.trunk = door(BACK_DOOR, BACK_DOOR2);
        s.tireLF = pos(v(TYRE_LF));
        s.tireRF = pos(v(TYRE_RF));
        s.tireLR = pos(v(TYRE_LR));
        s.tireRR = pos(v(TYRE_RR));
        s.online = Boolean.TRUE; // si hay log, la troca está despierta
        // luces encendidas -> se añaden al resumen de "abierto"
        s.lowBeam = lightOn(L_LOW);
        s.sideLight = lightOn(L_SIDE);
        s.turnSignal = lightOn(L_TURN) != null ? lightOn(L_TURN) : (lightOn(L_TURN_SW));
        main.post(new Runnable() { public void run() { ui.onVehicleLog(s); } });
    }

    static Double pos(Double d) { return d == null || d < 0 ? null : d; }

    void stop() {
        running = false;
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) {}
        if (reader != null) reader.interrupt();
        reader = null;
    }
}
