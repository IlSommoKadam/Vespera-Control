package com.vaonis.vesperacontrol.ui.telescopio;

import android.os.Bundle;
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

public class TelescopioFragment extends Fragment {

    private TextView textLog;
    private AdbBridge adb;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_telescopio, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adb = AdbBridgeHolder.get(requireContext());
        textLog = view.findViewById(R.id.textTelescopioLog);

        view.<Button>findViewById(R.id.btnPark)
                .setOnClickListener(v -> send("cmd|telescope|park"));
        view.<Button>findViewById(R.id.btnUnpark)
                .setOnClickListener(v -> send("cmd|telescope|init"));
        view.<Button>findViewById(R.id.btnMountHd)
                .setOnClickListener(v -> send("cmd|hd|mount|"));
        view.<Button>findViewById(R.id.btnUnmountHd)
                .setOnClickListener(v -> send("cmd|hd|eject|"));
        view.<Button>findViewById(R.id.btnGoto)
                .setOnClickListener(v -> send("cmd|telescope|resume"));
        view.<Button>findViewById(R.id.btnAbort)
                .setOnClickListener(v -> send("cmd|telescope|stop"));
    }

    private void send(String line) {
        adb.sendLine(line, this::onResult);
    }

    private void onResult(boolean ok, String msg) {
        postUi(() -> {
            textLog.setText(msg);
            Toast.makeText(requireContext(),
                    ok ? "OK" : "Errore",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void postUi(Runnable r) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(r);
    }
}
