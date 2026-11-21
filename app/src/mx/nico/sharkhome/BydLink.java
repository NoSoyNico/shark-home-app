package mx.nico.sharkhome;

import android.content.Context;
import android.hardware.bydauto.speed.AbsBYDAutoSpeedListener;
import android.hardware.bydauto.speed.BYDAutoSpeedDevice;
import android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener;
import android.hardware.bydauto.statistic.BYDAutoStatisticDevice;
import android.hardware.bydauto.tyre.AbsBYDAutoTyreListener;
import android.hardware.bydauto.tyre.BYDAutoTyreDevice;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Lee datos del vehículo por la API de EVENTOS de BYD (registerListener), que solo exige los
 * permisos *_COMMON (prot=dangerous, otorgables con pm grant) — NO los *_GET de firma.
 * SOLO LECTURA: solo se sobrescriben callbacks on*Changed y se llama getAllStatus() para pedir
 * un volcado inicial. No se llama ningún método set ni postEvent ni nada que mande órdenes al coche.
 *
 * Compila contra stubs (carpeta stubs/, no van al APK); en la troca se usan las clases reales.
 */
public class BydLink {

    static final String TAG = "SharkData";

    public interface Ui {
        void onVehicle(Snapshot s);
    }

    /** Último estado conocido. Campos en -1 = aún sin dato. */
    public static class Snapshot {
        public double elecPercent = -1;   // % batería
        public int elecRange = -1;        // km eléctricos
        public int fuelPercent = -1;      // % gasolina
        public int fuelRange = -1;        // km gasolina
        public int totalMileage = -1;     // odómetro
        public double speed = -1;         // km/h (del vehículo, no GPS)
        public final int[] tyre = {-1, -1, -1, -1}; // presión por llanta (kPa)
    }

    final Context ctx;
    final Ui ui;
    final Handler main = new Handler(Looper.getMainLooper());
    final Snapshot snap = new Snapshot();
    boolean verbose;   // true = loguea cada callback (modo prueba)

    BYDAutoStatisticDevice statDev;
    BYDAutoTyreDevice tyreDev;
    BYDAutoSpeedDevice speedDev;
    StatListener statL;
    TyreListener tyreL;
    SpeedListener speedL;
    boolean started;

    BydLink(Context c, Ui u) { ctx = c.getApplicationContext(); ui = u; }

    void setVerbose(boolean v) { verbose = v; }

    void notifyUi() {
        if (ui == null) return;
        main.post(new Runnable() { public void run() { ui.onVehicle(snap); } });
    }

    void start() {
        if (started) return;
        started = true;
        try {
            statDev = BYDAutoStatisticDevice.getInstance(ctx);
            statL = new StatListener();
            try { statDev.registerListener(statL); Log.i(TAG, "stat registerListener OK"); }
            catch (Throwable t) { Log.w(TAG, "stat registerListener ERR: " + t); }
            try { statDev.getAllStatus(); Log.i(TAG, "stat getAllStatus OK"); }
            catch (Throwable t) { Log.w(TAG, "stat getAllStatus ERR: " + t); }
        } catch (Throwable t) { Log.w(TAG, "stat start: " + t); }
        try {
            tyreDev = BYDAutoTyreDevice.getInstance(ctx);
            tyreL = new TyreListener();
            tyreDev.registerListener(tyreL);
            tyreDev.getAllStatus();
        } catch (Throwable t) { Log.w(TAG, "tyre start: " + t); }
        try {
            speedDev = BYDAutoSpeedDevice.getInstance(ctx);
            speedL = new SpeedListener();
            speedDev.registerListener(speedL);
            speedDev.getAllStatus();
        } catch (Throwable t) { Log.w(TAG, "speed start: " + t); }
    }

    void stop() {
        started = false;
        try { if (statDev != null && statL != null) statDev.unregisterListener(statL); } catch (Throwable ignored) {}
        try { if (tyreDev != null && tyreL != null) tyreDev.unregisterListener(tyreL); } catch (Throwable ignored) {}
        try { if (speedDev != null && speedL != null) speedDev.unregisterListener(speedL); } catch (Throwable ignored) {}
    }

    class StatListener extends AbsBYDAutoStatisticListener {
        public void onElecPercentageChanged(double v) { snap.elecPercent = v; if (verbose) Log.i(TAG, "EVENT elecPercent=" + v); notifyUi(); }
        public void onElecDrivingRangeChanged(int v) { snap.elecRange = v; if (verbose) Log.i(TAG, "EVENT elecRange=" + v); notifyUi(); }
        public void onSOCBatteryPercentageChanged(int v) { if (snap.elecPercent < 0) snap.elecPercent = v; if (verbose) Log.i(TAG, "EVENT soc=" + v); notifyUi(); }
        public void onFuelPercentageChanged(int v) { snap.fuelPercent = v; if (verbose) Log.i(TAG, "EVENT fuelPercent=" + v); notifyUi(); }
        public void onFuelDrivingRangeChanged(int v) { snap.fuelRange = v; if (verbose) Log.i(TAG, "EVENT fuelRange=" + v); notifyUi(); }
        public void onTotalMileageValueChanged(int v) { snap.totalMileage = v; if (verbose) Log.i(TAG, "EVENT totalMileage=" + v); notifyUi(); }
        public void onRemainingBatteryPowerChanged(int v) { if (verbose) Log.i(TAG, "EVENT remBatteryPower=" + v); }
        public void onError(int code, String msg) { Log.w(TAG, "stat onError " + code + " " + msg); }
    }

    class TyreListener extends AbsBYDAutoTyreListener {
        public void onTyrePressureValueChanged(int index, int value) {
            if (index >= 0 && index < 4) snap.tyre[index] = value;
            if (verbose) Log.i(TAG, "EVENT tyre[" + index + "]=" + value);
            notifyUi();
        }
        public void onError(int code, String msg) { Log.w(TAG, "tyre onError " + code + " " + msg); }
    }

    class SpeedListener extends AbsBYDAutoSpeedListener {
        public void onSpeedChanged(double v) { snap.speed = v; if (verbose) Log.i(TAG, "EVENT speed=" + v); notifyUi(); }
        public void onError(int code, String msg) { Log.w(TAG, "speed onError " + code + " " + msg); }
    }
}
