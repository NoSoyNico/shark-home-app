package android.hardware.bydauto.statistic;
import android.content.Context;
/** Stub de compilación. La implementación real está en la troca. */
public class BYDAutoStatisticDevice {
    public static BYDAutoStatisticDevice getInstance(Context c) { return null; }
    public void registerListener(AbsBYDAutoStatisticListener l) {}
    public void registerListener(AbsBYDAutoStatisticListener l, int[] ids) {}
    public void unregisterListener(AbsBYDAutoStatisticListener l) {}
    public void getAllStatus() {}
    public String getGetPermission() { return null; }
    public double getElecPercentageValue() { return 0; }
    public int getElecDrivingRangeValue() { return 0; }
}
