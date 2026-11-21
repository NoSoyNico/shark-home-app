package android.hardware.bydauto.statistic;
import android.hardware.IBYDAutoEvent;
import android.hardware.IBYDAutoListener;
import android.hardware.bydauto.BYDAutoEventValue;
/** Stub de compilación: solo las firmas que sobrescribimos. */
public class AbsBYDAutoStatisticListener implements IBYDAutoListener {
    public void onDataChanged(IBYDAutoEvent e) {}
    public void onDataEventChanged(int a, BYDAutoEventValue v) {}
    public void onError(int code, String msg) {}
    public void onElecPercentageChanged(double v) {}
    public void onElecDrivingRangeChanged(int v) {}
    public void onSOCBatteryPercentageChanged(int v) {}
    public void onRemainingBatteryPowerChanged(int v) {}
    public void onFuelPercentageChanged(int v) {}
    public void onFuelDrivingRangeChanged(int v) {}
    public void onTotalMileageValueChanged(int v) {}
}
