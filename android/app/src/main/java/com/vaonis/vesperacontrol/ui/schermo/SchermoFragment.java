package com.vaonis.vesperacontrol.ui.schermo;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.R;

/**
 * Tab Schermo — stub integrazione scrcpy.
 *
 * Integrazione futura:
 * - modulo/AAR da
 *   c:\Danger\MieiProgetti\Vespera II - Osservatorio\Scrcpy-for-Android
 * - package tipico: org.client.scrcpy
 *
 * Esempio Intent (da abilitare quando il modulo è linkato):
 * <pre>
 * Intent i = new Intent();
 * i.setClassName("org.client.scrcpy", "org.client.scrcpy.MainActivity");
 * // oppure startActivity verso ScreenMirrorActivity locale che wrappa scrcpy
 * i.putExtra("ip", ip);
 * i.putExtra("port", port);
 * i.putExtra("bitrate", bitrate);
 * startActivity(i);
 * </pre>
 */
public class SchermoFragment extends Fragment {

    public static final String EXTRA_IP = "mirror_ip";
    public static final String EXTRA_PORT = "mirror_port";
    public static final String EXTRA_BITRATE = "mirror_bitrate";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_schermo, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        EditText editIp = view.findViewById(R.id.editMirrorIp);
        EditText editPort = view.findViewById(R.id.editMirrorPort);
        EditText editBitrate = view.findViewById(R.id.editMirrorBitrate);
        Button btnOpen = view.findViewById(R.id.btnOpenMirror);

        btnOpen.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), ScreenMirrorActivity.class);
            intent.putExtra(EXTRA_IP, editIp.getText().toString().trim());
            intent.putExtra(EXTRA_PORT, editPort.getText().toString().trim());
            intent.putExtra(EXTRA_BITRATE, editBitrate.getText().toString().trim());
            startActivity(intent);
        });
    }
}
