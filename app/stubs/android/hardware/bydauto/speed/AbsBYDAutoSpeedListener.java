package android.hardware.bydauto.speed;
import android.hardware.IBYDAutoEvent;
import android.hardware.IBYDAutoListener;
import android.hardware.bydauto.BYDAutoEventValue;
public class AbsBYDAutoSpeedListener implements IBYDAutoListener {
    public void onDataChanged(IBYDAutoEvent e) {}
    public void onDataEventChanged(int a, BYDAutoEventValue v) {}
    public void onError(int code, String msg) {}
    public void onSpeedChanged(double v) {}
}
