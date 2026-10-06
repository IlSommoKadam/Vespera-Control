package com.vaonis.vesperacontrol.ui.connections;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
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

import com.vaonis.vesperacontrol.AdbBridgeHolder;
import com.vaonis.vesperacontrol.DevicePrefs;
import com.vaonis.vesperacontrol.InstrumentStatus;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.RemoteState;
import com.vaonis.vesperacontrol.adb.AdbBridge;
import com.vaonis.vesperacontrol.ui.TabRefreshable;

import org.json.JSONObject;

import java.io.File;

/** Come la tab Connessioni di Windows: connessione al Pi + Wi‑Fi Vespera / Singularity (IP e porte in Impostazioni). */
public class ConnessioniFragment extends Fragment implements TabRefreshable {

    private TextView textStatus;
    private TextView textWifi;
    private TextView textAddress;
    private TextView barVespera;
    private TextView barSingularity;
    private TextView textSingularityInfo;
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

        textStatus = view.findViewById(R.id.textStatus);
        textWifi = view.findViewById(R.id.textWifiStatus);
        textAddress = view.findViewById(R.id.textPiAddress);
        barVespera = view.findViewById(R.id.barVespera);
        barSingularity = view.findViewById(R.id.barSingularity);
        textSingularityInfo = view.findViewById(R.id.textSingularityInfo);
        InstrumentStatus.paint(barVespera, InstrumentStatus.vespera(null));
        JSONObject idle = new JSONObject();
        try {
            idle.put("status", "IDLE");
        } catch (Exception ignored) {
        }
        InstrumentStatus.paint(barSingularity, InstrumentStatus.singularity(idle));
        showAddress();

        view.<Button>findViewById(R.id.btnConnect).setOnClickListener(v -> doConnect());
        view.<Button>findViewById(R.id.btnRefreshStatus).setOnClickListener(v -> refreshState());
        view.<Button>findViewById(R.id.btnWifiConnect)
                .setOnClickListener(v -> sendCmd("cmd|wifi|connect"));
        view.<Button>findViewById(R.id.btnWifiDisconnect)
                .setOnClickListener(v -> sendCmd("cmd|wifi|disconnect"));
        view.<Button>findViewById(R.id.btnWifiScan)
                .setOnClickListener(v -> sendCmd("cmd|wifi|scan"));
        view.<Button>findViewById(R.id.btnRestartSingularity)
                .setOnClickListener(v -> sendCmd("cmd|singularity|restart"));
    }

    @Override
    public void onTabSelected() {
        showAddress();
        if (adb != null && adb.isConnected()) {
            refreshState();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        showAddress();
        if (adb != null && adb.isConnected()) {
            refreshState();
        } else if (textStatus != null && adb != null) {
            textStatus.setText(R.string.conn_status_fail);
        }
    }

    /** IP e porta si cambiano in Impostazioni. */
    private void showAddress() {
        if (textAddress == null) return;
        String ip = DevicePrefs.getIp(requireContext());
        textAddress.setText(TextUtils.isEmpty(ip)
                ? getString(R.string.label_pi_address_missing)
                : getString(R.string.label_pi_address, ip, DevicePrefs.getAdbPort(requireContext())));
    }

    /** Come "Connetti ADB" su Windows: connessione + ping del RemoteBridge. */
    private void doConnect() {
        String ip = DevicePrefs.getIp(requireContext());
        if (TextUtils.isEmpty(ip)) {
            toast(getString(R.string.label_device_ip_missing));
            return;
        }
        int port = DevicePrefs.getAdbPort(requireContext());
        textStatus.setText(getString(R.string.conn_status_connecting, ip));
        adb.connect(ip, port, (ok, msg) -> postUi(() -> {
            textStatus.setText(msg);
            if (!ok) {
                toast("Connessione fallita");
                return;
            }
            adb.sendLine("ping", (pingOk, ack) -> postUi(() -> {
                textStatus.setText(pingOk ? "ADB ok · RemoteBridge " + ack : ack);
                toast(pingOk ? "Connesso" : "Bridge non risponde");
                refreshState();
            }));
        }));
    }

    private void sendCmd(String line) {
        adb.sendLine(line, (ok, msg) -> postUi(() -> {
            textStatus.setText(msg);
            toast(ok ? "OK" : "Errore");
            if (ok) {
                refreshState();
            }
        }));
    }

    private void refreshState() {
        File dest = new File(requireContext().getCacheDir(), "remote.state.json");
        adb.pullState(dest, (ok, msg) -> postUi(() -> {
            if (!ok) {
                textStatus.setText(msg);
                return;
            }
            RemoteState state = RemoteState.parse(adb.readPulledText(dest));
            textWifi.setText(TextUtils.isEmpty(state.wifiLine) ? "—" : state.wifiLine);
            InstrumentStatus.paint(barVespera, InstrumentStatus.vespera(state.wifi));
            textSingularityInfo.setText(InstrumentStatus.singularityInfo(state.singularity));
            InstrumentStatus.paint(barSingularity, InstrumentStatus.singularity(state.singularity));
            String ver = TextUtils.isEmpty(state.appVersion) ? "?" : state.appVersion;
            textStatus.setText(getString(R.string.label_system_refreshed, ver));
        }));
    }

    private interface Saver {
        void save(String value);
    }

    private void bind(EditText edit, Saver saver) {
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
            }
        });
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
