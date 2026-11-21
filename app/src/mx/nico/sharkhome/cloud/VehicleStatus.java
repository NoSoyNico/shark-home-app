package mx.nico.sharkhome.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Estado del vehículo ya interpretado (solo lectura), a partir del JSON crudo de la nube de BYD.
 * Valores en null = la troca no lo reportó. Nombres y códigos según pyBYD (MIT).
 */
public final class VehicleStatus {

    public Double batteryPct, evRangeKm, fuelPct, fuelRangeKm, odometerKm;
    public boolean charging;
    public Boolean online;
    public long timestamp; // segundos desde 1970 (0 = desconocido)

    // Puertas: true = abierta, false = cerrada, null = desconocido
    public Boolean doorLF, doorRF, doorLR, doorRR, trunk, hood;
    // Seguros: true = con seguro
    public Boolean lockLF, lockRF, lockLR, lockRR;
    // Ventanas: true = abierta
    public Boolean winLF, winRF, winLR, winRR, sunroof;
    // Llantas
    public Double tireLF, tireRF, tireLR, tireRR;
    public String tireUnit = "";
    public boolean tireWarning;
    // Luces (del log; null = sin dato). true = encendida
    public Boolean lowBeam, sideLight, turnSignal;

    static Double num(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object o = m.get(k);
            if (o == null) continue;
            double d;
            if (o instanceof Number) d = ((Number) o).doubleValue();
            else {
                String s = String.valueOf(o).trim();
                if (s.isEmpty()) continue;
                try { d = Double.parseDouble(s); } catch (Exception e) { continue; }
            }
            if (d < 0 || d == -129) continue; // centinelas de "sin dato"
            return d;
        }
        return null;
    }

    /** 0 = cerrada, 1 = abierta. */
    static Boolean door(Map<String, Object> m, String... keys) {
        Double d = num(m, keys);
        if (d == null) return null;
        if (d == 1) return Boolean.TRUE;
        if (d == 0) return Boolean.FALSE;
        return null;
    }

    /** 1 = sin seguro, 2 = con seguro, 0 = no disponible. */
    static Boolean lock(Map<String, Object> m, String key) {
        Double d = num(m, key);
        if (d == null) return null;
        if (d == 2) return Boolean.TRUE;
        if (d == 1) return Boolean.FALSE;
        return null;
    }

    /** 1 = cerrada, 2 = abierta. */
    static Boolean window(Map<String, Object> m, String key) {
        Double d = num(m, key);
        if (d == null) return null;
        if (d == 2) return Boolean.TRUE;
        if (d == 1) return Boolean.FALSE;
        return null;
    }

    static Double tire(Map<String, Object> m, String a, String b) {
        Double d = num(m, a, b);
        return d == null || d == 0 ? null : d;
    }

    public static VehicleStatus parse(Map<String, Object> m) {
        VehicleStatus s = new VehicleStatus();
        s.batteryPct = num(m, "elecPercent", "powerBattery");
        s.evRangeKm = num(m, "enduranceMileage", "evEndurance");
        s.fuelPct = num(m, "oilPercent");
        s.fuelRangeKm = num(m, "oilEndurance");
        s.odometerKm = num(m, "totalMileage");
        Double ch = num(m, "chargingState", "chargeState");
        s.charging = ch != null && ch == 1;
        Double on = num(m, "onlineState");
        s.online = on == null ? null : (on == 1 ? Boolean.TRUE : (on == 2 ? Boolean.FALSE : null));
        Double t = num(m, "time", "timestamp");
        if (t != null) s.timestamp = t > 1e12 ? (long) (t / 1000) : t.longValue();

        s.doorLF = door(m, "leftFrontDoor");
        s.doorRF = door(m, "rightFrontDoor");
        s.doorLR = door(m, "leftRearDoor");
        s.doorRR = door(m, "rightRearDoor");
        s.trunk = door(m, "trunkLid", "backCover");
        s.hood = door(m, "forehold");

        s.lockLF = lock(m, "leftFrontDoorLock");
        s.lockRF = lock(m, "rightFrontDoorLock");
        s.lockLR = lock(m, "leftRearDoorLock");
        s.lockRR = lock(m, "rightRearDoorLock");

        s.winLF = window(m, "leftFrontWindow");
        s.winRF = window(m, "rightFrontWindow");
        s.winLR = window(m, "leftRearWindow");
        s.winRR = window(m, "rightRearWindow");
        s.sunroof = window(m, "skylight");

        s.tireLF = tire(m, "leftFrontTirePressure", "leftFrontTirepressure");
        s.tireRF = tire(m, "rightFrontTirePressure", "rightFrontTirepressure");
        s.tireLR = tire(m, "leftRearTirePressure", "leftRearTirepressure");
        s.tireRR = tire(m, "rightRearTirePressure", "rightRearTirepressure");
        Double u = num(m, "tirePressUnit");
        s.tireUnit = u == null ? "" : (u == 1 ? "bar" : u == 2 ? "psi" : u == 3 ? "kPa" : "");
        String[] st = {"leftFrontTireStatus", "rightFrontTireStatus", "leftRearTireStatus", "rightRearTireStatus", "rapidTireLeak"};
        for (String k : st) {
            Double v = num(m, k);
            if (v != null && v > 0) s.tireWarning = true;
        }
        return s;
    }

    static boolean is(Boolean b) { return b != null && b; }

    public boolean anyOpen() {
        return is(doorLF) || is(doorRF) || is(doorLR) || is(doorRR) || is(trunk) || is(hood);
    }

    /** "Todo cerrado" o la lista de lo que está abierto, en español. */
    public String openSummary() {
        List<String> o = new ArrayList<String>();
        if (is(doorLF)) o.add("piloto");
        if (is(doorRF)) o.add("copiloto");
        if (is(doorLR)) o.add("trasera izq");
        if (is(doorRR)) o.add("trasera der");
        if (is(trunk)) o.add("batea/cajuela");
        if (is(hood)) o.add("cofre");
        List<String> w = new ArrayList<String>();
        if (is(winLF) || is(winRF) || is(winLR) || is(winRR)) w.add("ventanas abiertas");
        if (is(sunroof)) w.add("quemacocos abierto");
        if (o.isEmpty() && w.isEmpty()) {
            if (doorLF == null && doorRF == null && trunk == null) return "";
            return "Todo cerrado";
        }
        StringBuilder sb = new StringBuilder();
        if (!o.isEmpty()) sb.append("Abierto: ").append(join(o));
        if (!w.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(join(w));
        return sb.toString();
    }

    public String tireSummary() {
        if (tireLF == null && tireRF == null && tireLR == null && tireRR == null) return "";
        String f = "psi".equals(tireUnit) || "kPa".equals(tireUnit) ? "%.0f" : "%.1f";
        return "Llantas " + fmt(f, tireLF) + "·" + fmt(f, tireRF) + "·" + fmt(f, tireLR) + "·" + fmt(f, tireRR)
                + (tireUnit.isEmpty() ? "" : " " + tireUnit) + (tireWarning ? " ⚠" : "");
    }

    public boolean allLocked() {
        return is(lockLF) && is(lockRF) && (lockLR == null || is(lockLR)) && (lockRR == null || is(lockRR));
    }

    /** "Luces: bajas" etc., o vacío si están apagadas / sin dato. */
    public String lightsSummary() {
        List<String> l = new ArrayList<String>();
        if (is(lowBeam)) l.add("bajas");
        else if (is(sideLight)) l.add("cuartos");
        if (is(turnSignal)) l.add("direccional");
        return l.isEmpty() ? "" : "Luces: " + join(l);
    }

    static String fmt(String f, Double d) { return d == null ? "–" : String.format(Locale.US, f, d); }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(i == l.size() - 1 ? " y " : ", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }
}
