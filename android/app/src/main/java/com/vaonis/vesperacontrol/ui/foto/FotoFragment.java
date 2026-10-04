package com.vaonis.vesperacontrol.ui.foto;

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

public class FotoFragment extends Fragment {

    private TextView textLog;
    private AdbBridge adb;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_foto, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adb = AdbBridgeHolder.get(requireContext());
        textLog = view.findViewById(R.id.textFotoLog);

        view.<Button>findViewById(R.id.btnTakePhoto)
                .setOnClickListener(v -> send("cmd|hd|list"));
        view.<Button>findViewById(R.id.btnStartObs)
                .setOnClickListener(v -> send("cmd|hd|mount|"));
        view.<Button>findViewById(R.id.btnStopObs)
                .setOnClickListener(v -> send("cmd|sync|now"));
    }

    private void send(String line) {
        adb.sendLine(line, (ok, msg) -> postUi(() -> {
            textLog.setText(line + " → " + msg);
            Toast.makeText(requireContext(),
                    ok ? "Comando inviato" : "Errore",
                    Toast.LENGTH_SHORT).show();
        }));
    }

    private void postUi(Runnable r) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(r);
    }
}
