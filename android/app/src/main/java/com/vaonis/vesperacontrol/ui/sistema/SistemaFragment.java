package com.vaonis.vesperacontrol.ui.sistema;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.AdbBridgeHolder;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.adb.AdbBridge;

import java.io.File;

public class SistemaFragment extends Fragment {

    private TextView textLog;
    private AdbBridge adb;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sistema, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adb = AdbBridgeHolder.get(requireContext());
        textLog = view.findViewById(R.id.textSistemaLog);

        view.<Button>findViewById(R.id.btnMountHdSistema)
                .setOnClickListener(v -> send("cmd|hd|mount|"));
        view.<Button>findViewById(R.id.btnUnmountHdSistema)
                .setOnClickListener(v -> send("cmd|hd|eject|"));
        view.<Button>findViewById(R.id.btnReboot)
                .setOnClickListener(v -> send("cmd|singularity|restart"));
        view.<Button>findViewById(R.id.btnRefreshSistema)
                .setOnClickListener(v -> refresh());
    }

    private void send(String line) {
        adb.sendLine(line, (ok, msg) -> postUi(() -> {
            textLog.setText(msg);
            Toast.makeText(requireContext(), ok ? "OK" : "Errore", Toast.LENGTH_SHORT).show();
        }));
    }

    private void refresh() {
        adb.sendLine("get|state", (ok, msg) -> {
            File dest = new File(requireContext().getCacheDir(), "remote.state.json");
            adb.pullState(dest, (pullOk, pullMsg) -> postUi(() -> {
                if (!pullOk) {
                    textLog.setText(msg + "\n" + pullMsg);
                    return;
                }
                String body = adb.readPulledText(dest);
                textLog.setText(TextUtils.isEmpty(body) ? msg : body);
            }));
        });
    }

    private void postUi(Runnable r) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(r);
    }
}
