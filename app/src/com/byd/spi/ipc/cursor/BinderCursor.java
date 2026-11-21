package com.byd.spi.ipc.cursor;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Copia mínima (mismo nombre de clase) del contenedor que usa DiCarServer para mandar el canal (IBinder)
 * dentro del Bundle "binder". Android necesita una clase con este nombre exacto para poder abrir el paquete.
 * Solo lee el IBinder; no hace nada más.
 */
public class BinderCursor {
    public static class BinderParcelable implements Parcelable {
        public final IBinder binder;

        public BinderParcelable(IBinder b) { binder = b; }

        public int describeContents() { return 0; }

        public void writeToParcel(Parcel p, int flags) { p.writeStrongBinder(binder); }

        public static final Parcelable.Creator<BinderParcelable> CREATOR = new Parcelable.Creator<BinderParcelable>() {
            public BinderParcelable createFromParcel(Parcel p) { return new BinderParcelable(p.readStrongBinder()); }
            public BinderParcelable[] newArray(int n) { return new BinderParcelable[n]; }
        };
    }
}
