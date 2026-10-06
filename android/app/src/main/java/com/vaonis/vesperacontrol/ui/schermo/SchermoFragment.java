package com.vaonis.vesperacontrol.ui.schermo;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.DevicePrefs;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.ui.TabRefreshable;

/**
 * Avvia Scrcpy-for-Android ({@code org.client.scrcpy}) con IP da Connessioni e porta/bitrate di questa tab.
 */
public class SchermoFragment extends Fragment implements TabRefreshable {

    public static final String SCRCPY_PACKAGE = "org.client.scrcpy";
    public static final String SCRCPY_ACTIVITY = "org.client.scrcpy.MainActivity";
    public static final String EXTRA_IP = "mirror_ip";
    public static final String EXTRA_PORT = "mirror_port";
    public static final String EXTRA_BITRATE = "mirror_bitrate";

    private TextView textIp;

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

        textIp = view.findViewById(R.id.textMirrorIp);
        Button btnOpen = view.findViewById(R.id.btnOpenMirror);
        EditText editBitrate = view.findViewById(R.id.editScrcpyBitrate);
        editBitrate.setText(String.valueOf(DevicePrefs.getScrcpyBitrateMbps(requireContext())));
        onEdit(editBitrate, s -> DevicePrefs.setScrcpyBitrateMbps(requireContext(), s));
        showIp();
        btnOpen.setOnClickListener(v -> openMirror());
    }

    @Override
    public void onTabSelected() {
        showIp();
    }

    @Override
    public void onResume() {
        super.onResume();
        showIp();
    }

    private void openMirror() {
        String ip = DevicePrefs.getIp(requireContext());
        if (ip.isEmpty()) {
            Toast.makeText(requireContext(), R.string.label_device_ip_missing, Toast.LENGTH_LONG).show();
            showIp();
            return;
        }
        int port = DevicePrefs.getScrcpyAdbPort(requireContext());
        int bitrate = DevicePrefs.getScrcpyBitrateMbps(requireContext());

        if (!isScrcpyInstalled()) {
            // Fallback locale con istruzioni.
            Intent intent = new Intent(requireContext(), ScreenMirrorActivity.class);
            intent.putExtra(EXTRA_IP, ip);
            intent.putExtra(EXTRA_PORT, String.valueOf(port));
            intent.putExtra(EXTRA_BITRATE, String.valueOf(bitrate));
            startActivity(intent);
            Toast.makeText(requireContext(), R.string.scrcpy_missing, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Intent intent = new Intent();
            intent.setClassName(SCRCPY_PACKAGE, SCRCPY_ACTIVITY);
            intent.putExtra("start_remote_headless", true);
            intent.putExtra("remote_addr", ip + ":" + port);
            intent.putExtra(EXTRA_IP, ip);
            intent.putExtra(EXTRA_PORT, String.valueOf(port));
            intent.putExtra(EXTRA_BITRATE, String.valueOf(bitrate));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.scrcpy_missing, Toast.LENGTH_LONG).show();
        }
    }

    private interface Saver {
        void save(String value);
    }

    private void onEdit(EditText edit, Saver saver) {
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                saver.save(s.toString());
                showIp();
            }
        });
    }

    private boolean isScrcpyInstalled() {
        try {
            requireContext().getPackageManager().getPackageInfo(SCRCPY_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void showIp() {
        if (textIp == null) return;
        String ip = DevicePrefs.getIp(requireContext());
        if (ip.isEmpty()) {
            textIp.setText(R.string.label_device_ip_missing);
        } else {
            textIp.setText(getString(R.string.label_mirror_device_ip_full,
                    ip, DevicePrefs.getScrcpyAdbPort(requireContext()),
                    DevicePrefs.getScrcpyBitrateMbps(requireContext())));
        }
    }
}
