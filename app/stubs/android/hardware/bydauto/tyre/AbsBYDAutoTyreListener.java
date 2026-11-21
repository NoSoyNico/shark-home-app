package android.hardware.bydauto.tyre;
import android.hardware.IBYDAutoEvent;
import android.hardware.IBYDAutoListener;
import android.hardware.bydauto.BYDAutoEventValue;
public class AbsBYDAutoTyreListener implements IBYDAutoListener {
    public void onDataChanged(IBYDAutoEvent e) {}
    public void onDataEventChanged(int a, BYDAutoEventValue v) {}
    public void onError(int code, String msg) {}
    public void onTyrePressureValueChanged(int index, int value) {}
    public void onTyrePressureStateChanged(int index, int state) {}
    public void onTyreSystemStateChanged(int v) {}
}
