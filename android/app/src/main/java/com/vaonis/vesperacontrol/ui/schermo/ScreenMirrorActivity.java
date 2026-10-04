package com.vaonis.vesperacontrol.ui.schermo;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.vaonis.vesperacontrol.R;

/**
 * Activity stub "Apri mirror".
 *
 * TODO integrazione:
 * 1. Includere il modulo scrcpy dal fork Scrcpy-for-Android
 *    (package org.client.scrcpy) senza copiare l'intero tree nel progetto.
 * 2. Avviare qui la superficie/client scrcpy con IP / porta / bitrate.
 *
 * Intent commentato di esempio:
 * <pre>
 * // Intent scrcpy = new Intent();
 * // scrcpy.setClassName(this, "org.client.scrcpy.Main");
 * // scrcpy.putExtra("server_ip", ip);
 * // scrcpy.putExtra("server_port", port);
 * // scrcpy.putExtra("video_bitrate", bitrateMbps * 1_000_000);
 * // startActivity(scrcpy);
 * </pre>
 */
public class ScreenMirrorActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen_mirror);

        String ip = getIntent().getStringExtra(SchermoFragment.EXTRA_IP);
        String port = getIntent().getStringExtra(SchermoFragment.EXTRA_PORT);
        String bitrate = getIntent().getStringExtra(SchermoFragment.EXTRA_BITRATE);

        TextView params = findViewById(R.id.textMirrorParams);
        params.setText("IP=" + nullToDash(ip)
                + "\nPorta=" + nullToDash(port)
                + "\nBitrate=" + nullToDash(bitrate) + " Mbps"
                + "\n\n(stub — nessun mirror attivo)");
    }

    private static String nullToDash(String v) {
        return v == null || v.isEmpty() ? "—" : v;
    }
}
