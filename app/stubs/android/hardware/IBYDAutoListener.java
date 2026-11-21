package android.hardware;
import android.hardware.bydauto.BYDAutoEventValue;
/** Stub de compilación del listener base. */
public interface IBYDAutoListener {
    void onDataChanged(IBYDAutoEvent e);
    void onDataEventChanged(int a, BYDAutoEventValue v);
    void onError(int code, String msg);
}
