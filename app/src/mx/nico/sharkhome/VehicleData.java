package mx.nico.sharkhome;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import com.byd.spi.ipc.cursor.BinderCursor;

/**
 * Lector de datos del vehículo — SOLO LECTURA.
 *
 * Ruta (sacada de DiCarServer.apk de la propia troca):
 *   content://com.byd.car.server.provider.CarServiceProvider  (query con selectionArgs = nombre de interfaz)
 *   -> Cursor con extras["binder"] -> ICarPropertyService
 *   -> transacción 2 = getProperty(String nombre) -> Response
 *
 * A propósito NO implementa setProperties (transacción de escritura) ni nada que mande órdenes al vehículo.
 */
public class VehicleData {
    static final String TAG = "SharkData";
    static final Uri PROVIDER = Uri.parse("content://com.byd.car.server.provider.CarServiceProvider");
    static final String IFACE = "com.byd.car.property.ICarPropertyService";
    static final int TX_GET_PROPERTY = 2; // única transacción que se usa

    private final Context ctx;
    private IBinder service;
    String lastError = "";

    VehicleData(Context c) { ctx = c.getApplicationContext(); }

    boolean connect() {
        if (service != null && service.isBinderAlive()) return true;
        service = null;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(PROVIDER, null, null, new String[]{IFACE}, null);
            if (c == null) { lastError = "provider devolvió null"; return false; }
            Bundle b = c.getExtras();
            b.setClassLoader(VehicleData.class.getClassLoader());
            BinderCursor.BinderParcelable bp = b.getParcelable("binder");
            if (bp == null || bp.binder == null) { lastError = "sin binder"; return false; }
            service = bp.binder;
            return true;
        } catch (Throwable t) {
            lastError = "connect: " + t;
            Log.w(TAG, lastError);
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Devuelve Integer/Long/Float/Double/String/Boolean, o null si no hay dato. */
    Object get(String name) {
        if (!connect()) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE);
            data.writeString(name);
            if (!service.transact(TX_GET_PROPERTY, data, reply, 0)) { lastError = "transact false"; return null; }
            reply.readException();
            if (reply.readInt() == 0) { lastError = name + ": respuesta vacía"; return null; }
            // Response: writeParcelable(Status) = nombreClase, código, mensaje
            reply.readString();
            int code = reply.readInt();
            String msg = reply.readString();
            if (code != 0) { lastError = name + ": status " + code + " " + msg; return null; }
            String type = reply.readString();
            if (type == null) { lastError = name + ": sin valor"; return null; }
            switch (type) {
                case "java.lang.Integer": case "int": return reply.readInt();
                case "java.lang.Long": case "long": return reply.readLong();
                case "java.lang.Float": case "float": return reply.readFloat();
                case "java.lang.Double": case "double": return reply.readDouble();
                case "java.lang.Boolean": case "boolean": return reply.readInt() != 0;
                case "java.lang.String": return reply.readString();
                default: lastError = name + ": tipo no soportado " + type; return null;
            }
        } catch (Throwable t) {
            lastError = name + ": " + t;
            Log.w(TAG, lastError);
            service = null;
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // IDs sacados de la tabla nombre->ID de DiCarServer (ver propiedades_dicarserver.txt)
    static final String ELEC_PERCENT = "0x4A505038";   // STATISTIC_ELEC_PERCENTAGE
    static final String ELEC_RANGE = "0x4A50203E";     // STATISTIC_ELEC_DRIVING_RANGE
    static final String FUEL_PERCENT = "0x4A507040";   // STATISTIC_FUEL_PERCENTAGE
    static final String FUEL_RANGE = "0x4A504038";     // STATISTIC_FUEL_DRIVING_RANGE
    static final String TOTAL_MILEAGE = "0x4A502010";  // STATISTIC_TOTAL_MILEAGE
    static final String GEAR = "0x34200020";           // GEARBOX_AUTOMATIC_GEAR
    static final String OUT_TEMP = "0x4A503040";       // INSTRUMENT_DD_OUT_TEMP
    static final String[] TYRES_2IN1 = {"0x4A506010", "0x4A50601C", "0x4A506028", "0x4A506034"}; // LF RF LR RR
    static final String[] TYRES_VAL = {"0x99000124", "0x99000128", "0x9900012C", "0x99000130"};

    static final String[][] PROBE = {
        {ELEC_PERCENT, "ELEC_PERCENTAGE"}, {"0x4A710010", "CHARGING_VEHICLE_SOC"},
        {ELEC_RANGE, "ELEC_DRIVING_RANGE"}, {FUEL_PERCENT, "FUEL_PERCENTAGE"}, {FUEL_RANGE, "FUEL_DRIVING_RANGE"},
        {TOTAL_MILEAGE, "TOTAL_MILEAGE"}, {"0x4A504024", "MILEAGE_EV"}, {GEAR, "GEAR"}, {OUT_TEMP, "OUT_TEMP"},
        {"0x0ED00047", "VEHICLE_SPEED"}, {"0x4A50202B", "MILEAGE_UNIT"},
        {TYRES_2IN1[0], "2IN1_LF_TYRE"}, {TYRES_2IN1[1], "2IN1_RF_TYRE"}, {TYRES_2IN1[2], "2IN1_LB_TYRE"}, {TYRES_2IN1[3], "2IN1_RB_TYRE"},
        {TYRES_VAL[0], "TYRE_VALUE_LF"}, {TYRES_VAL[1], "TYRE_VALUE_RF"}, {TYRES_VAL[2], "TYRE_VALUE_LR"}, {TYRES_VAL[3], "TYRE_VALUE_RR"},
    };

    // ---- Ruta 2: clases BYDAuto*Device del framework (como Electro). Solo métodos get*. ----
    static Object autoDevice(Context c, String cls) throws Exception {
        Class<?> k = Class.forName(cls);
        return k.getMethod("getInstance", Context.class).invoke(null, c);
    }

    static Object call(Object dev, String m, Object... args) throws Exception {
        if (!m.startsWith("get")) throw new IllegalArgumentException("solo lectura");
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = int.class;
        return dev.getClass().getMethod(m, types).invoke(dev, args);
    }

    void probeAuto() {
        String[][] tests = {
            {"android.hardware.bydauto.statistic.BYDAutoStatisticDevice", "getElecPercentageValue", "getSOCBatteryPercentage",
                "getElecDrivingRangeValue", "getFuelPercentageValue", "getFuelDrivingRangeValue", "getTotalMileageValue",
                "getEVMileageValue", "getRemainingBatteryPower"},
            {"android.hardware.bydauto.speed.BYDAutoSpeedDevice", "getCurrentSpeed"},
            {"android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", "getCurrentGear", "getGearboxAutoModeType"},
            {"android.hardware.bydauto.tyre.BYDAutoTyreDevice", "getTyreSystemState"},
        };
        for (String[] t : tests) {
            Object dev;
            try { dev = autoDevice(ctx, t[0]); } catch (Throwable e) { Log.i(TAG, "AUTO " + t[0] + " getInstance: " + root(e)); continue; }
            for (int i = 1; i < t.length; i++) {
                try { Log.i(TAG, "AUTO " + t[i] + " = " + call(dev, t[i])); }
                catch (Throwable e) { Log.i(TAG, "AUTO " + t[i] + " ERR " + root(e)); }
            }
        }
        try {
            Object tyre = autoDevice(ctx, "android.hardware.bydauto.tyre.BYDAutoTyreDevice");
            for (int a = 0; a <= 5; a++) {
                try { Log.i(TAG, "AUTO getTyrePressureValue(" + a + ") = " + call(tyre, "getTyrePressureValue", a)); }
                catch (Throwable e) { Log.i(TAG, "AUTO getTyrePressureValue(" + a + ") ERR " + root(e)); }
            }
        } catch (Throwable e) { Log.i(TAG, "AUTO tyre: " + root(e)); }
    }

    static String root(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.toString();
    }

    // Lista métodos/campos de las clases del framework BYDAuto para descubrir la API de EVENTOS
    // (register/listener) que no exige _GET. Solo lectura de la forma de las clases, no llama nada.
    void dumpApi() {
        String[] classes = {
            "android.hardware.bydauto.AbsBYDAutoDevice",
            "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
            "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "android.hardware.bydauto.BYDAutoEvent",
            "android.hardware.bydauto.BYDAutoEventValue",
            "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
            "android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener",
            "android.hardware.bydauto.light.BYDAutoLightDevice",
            "android.hardware.bydauto.light.AbsBYDAutoLightListener",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener",
        };
        for (String cn : classes) {
            try {
                Class<?> k = Class.forName(cn);
                Log.i(TAG, "API === " + cn + "  extends " + (k.getSuperclass() == null ? "-" : k.getSuperclass().getName()));
                for (Class<?> ifc : k.getInterfaces()) Log.i(TAG, "API   implements " + ifc.getName());
                for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                    StringBuilder sb = new StringBuilder("API   m " + m.getName() + "(");
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) { if (i > 0) sb.append(", "); sb.append(ps[i].getSimpleName()); }
                    sb.append(") : ").append(m.getReturnType().getSimpleName());
                    Log.i(TAG, sb.toString());
                }
                for (Class<?> nested : k.getDeclaredClasses()) Log.i(TAG, "API   nested " + nested.getName());
            } catch (Throwable t) {
                Log.i(TAG, "API " + cn + " -> " + root(t));
            }
        }
    }

    static BydLink testLink; // referencia fuerte para que no se recoja durante la prueba

    void probe() {
        dumpApi();
        try {
            testLink = new BydLink(ctx, null);
            testLink.setVerbose(true);
            testLink.start();
            Log.i(TAG, "BydLink arrancado, esperando eventos...");
        } catch (Throwable t) { Log.w(TAG, "BydLink: " + t); }
        probeAuto();
        Log.i(TAG, "connect=" + connect() + " " + lastError);
        for (String[] p : PROBE) {
            lastError = "";
            Object v = get(p[0]);
            Log.i(TAG, "PROBE " + p[1] + " " + p[0] + " = " + v
                    + (v == null ? "  (" + lastError + ")" : "  [" + v.getClass().getSimpleName() + "]"));
        }
    }
}
