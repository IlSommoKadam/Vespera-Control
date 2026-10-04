package com.vaonis.vesperacontrol.ui.connections;

import android.os.Bundle;
import android.text.TextUtils;
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

import com.vaonis.vesperacontrol.AdbBridgeHolder;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.adb.AdbBridge;

import java.io.File;

public class ConnessioniFragment extends Fragment {

    private EditText editDeviceIp;
    private EditText editAdbPort;
    private EditText editAdbPath;
    private TextView textStatus;
    private AdbBridge adb;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_connessioni, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adb = AdbBridgeHolder.get(requireContext());

        editDeviceIp = view.findViewById(R.id.editDeviceIp);
        editAdbPort = view.findViewById(R.id.editAdbPort);
        editAdbPath = view.findViewById(R.id.editAdbPath);
        textStatus = view.findViewById(R.id.textStatus);
        Button btnConnect = view.findViewById(R.id.btnConnect);
        Button btnDisconnect = view.findViewById(R.id.btnDisconnect);
        Button btnRefresh = view.findViewById(R.id.btnRefreshStatus);

        editAdbPath.setText(adb.getAdbPath());

        btnConnect.setOnClickListener(v -> doConnect());
        btnDisconnect.setOnClickListener(v ->
                adb.disconnect((ok, msg) -> postUi(() -> {
                    textStatus.setText(msg);
                    toast(ok ? "Disconnesso" : "Errore disconnect");
                }))
        );
        btnRefresh.setOnClickListener(v -> refreshState());
        view.<Button>findViewById(R.id.btnPingBridge)
                .setOnClickListener(v -> adb.sendLine("ping", (ok, msg) -> postUi(() -> {
                    textStatus.setText(msg);
                    toast(ok ? "Bridge OK" : "Bridge non risponde");
                })));
        view.<Button>findViewById(R.id.btnWifiConnect)
                .setOnClickListener(v -> adb.sendLine("cmd|wifi|connect", (ok, msg) -> postUi(() -> {
                    textStatus.setText(msg);
                    toast(ok ? "OK" : "Errore");
                })));
        view.<Button>findViewById(R.id.btnWifiDisconnect)
                .setOnClickListener(v -> adb.sendLine("cmd|wifi|disconnect", (ok, msg) -> postUi(() -> {
                    textStatus.setText(msg);
                    toast(ok ? "OK" : "Errore");
                })));
    }

    private void doConnect() {
        String ip = editDeviceIp.getText().toString().trim();
        String portStr = editAdbPort.getText().toString().trim();
        String path = editAdbPath.getText().toString().trim();
        if (TextUtils.isEmpty(ip)) {
            toast("Inserisci IP device");
            return;
        }
        int port = 5555;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {
        }
        if (!TextUtils.isEmpty(path)) {
            adb.setAdbPath(path);
        }
        textStatus.setText("Connessione in corso…");
        adb.connect(ip, port, (ok, msg) -> postUi(() -> {
            textStatus.setText(msg);
            toast(ok ? "Connesso" : "Connessione fallita");
        }));
    }

    private void refreshState() {
        File dest = new File(requireContext().getCacheDir(), "remote.state.json");
        adb.pullState(dest, (ok, msg) -> postUi(() -> {
            if (!ok) {
                textStatus.setText(msg);
                return;
            }
            String body = adb.readPulledText(dest);
            textStatus.setText(TextUtils.isEmpty(body) ? msg : body);
        }));
    }

    private void postUi(Runnable r) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(r);
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
