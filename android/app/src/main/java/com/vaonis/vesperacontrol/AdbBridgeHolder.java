package com.vaonis.vesperacontrol;

import android.content.Context;

import com.vaonis.vesperacontrol.adb.AdbBridge;

/**
 * Holder semplice per condividere un'unica istanza AdbBridge tra i fragment.
 */
public final class AdbBridgeHolder {

    private static AdbBridge instance;

    private AdbBridgeHolder() {
    }

    public static synchronized AdbBridge get(Context context) {
        if (instance == null) {
            instance = new AdbBridge(context);
        }
        return instance;
    }
}
