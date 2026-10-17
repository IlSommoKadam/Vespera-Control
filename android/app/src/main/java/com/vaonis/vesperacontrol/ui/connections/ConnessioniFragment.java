package com.vaonis.vesperacontrol.ui.connections;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
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

/** Come la tab Connessioni di Windows / Helper: Pi + Wi‑Fi Vespera / Singularity. */
public class ConnessioniFragment extends Fragment implements TabRefreshable {

    private TextView textStatus;
    private TextView textAction;
    private TextView textWifi;
    private TextView textAddress;
    private TextView barVespera;
    private TextView barSingularity;
    private TextView textSingularityInfo;
    private LinearLayout legendRow;
    private Button btnWifiConnect;
    private Button btnWifiDisconnect;
    private Button btnWifiScan;
    private Button btnRestartSingularity;
    private Button btnConnect;
    private Button btnRefreshStatus;
    private AdbBridge adb;
    private boolean busy;
    private boolean wifiConnected;

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
        textAction = view.findViewById(R.id.textAction);
        textWifi = view.findViewById(R.id.textWifiStatus);
        textAddress = view.findViewById(R.id.textPiAddress);
        barVespera = view.findViewById(R.id.barVespera);
        barSingularity = view.findViewById(R.id.barSingularity);
        textSingularityInfo = view.findViewById(R.id.textSingularityInfo);
        legendRow = view.findViewById(R.id.legendRow);
        btnWifiConnect = view.findViewById(R.id.btnWifiConnect);
        btnWifiDisconnect = view.findViewById(R.id.btnWifiDisconnect);
        btnWifiScan = view.findViewById(R.id.btnWifiScan);
        btnRestartSingularity = view.findViewById(R.id.btnRestartSingularity);
        btnConnect = view.findViewById(R.id.btnConnect);
        btnRefreshStatus = view.findViewById(R.id.btnRefreshStatus);

        buildLegend();
        InstrumentStatus.paint(barVespera, InstrumentStatus.vespera(null));
        JSONObject idle = new JSONObject();
        try {
            idle.put("status", "IDLE");
        } catch (Exception ignored) {
        }
        InstrumentStatus.paint(barSingularity, InstrumentStatus.singularity(idle));
        showAddress();
        updateWifiButtons();

        btnConnect.setOnClickListener(v -> doConnect());
        btnRefreshStatus.setOnClickListener(v -> refreshState(true));
        btnWifiConnect.setOnClickListener(v -> sendCmd(
                "cmd|wifi|connect", getString(R.string.action_busy_connect), 40_000L));
        btnWifiDisconnect.setOnClickListener(v -> sendCmd(
                "cmd|wifi|disconnect", getString(R.string.action_busy_disconnect), 40_000L));
        btnWifiScan.setOnClickListener(v -> sendCmd(
                "cmd|wifi|scan", getString(R.string.action_busy_scan), 30_000L));
        btnRestartSingularity.setOnClickListener(v -> sendCmd(
                "cmd|singularity|restart", getString(R.string.action_busy_singularity), 40_000L));
        barVespera.setOnClickListener(v -> {
            if (!wifiConnected && !busy) {
                sendCmd("cmd|wifi|connect", getString(R.string.action_busy_connect), 40_000L);
            }
        });
    }

    @Override
    public void onTabSelected() {
        showAddress();
        if (adb != null && adb.isConnected()) {
            refreshState(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        showAddress();
        if (adb != null && adb.isConnected()) {
            refreshState(false);
        } else if (textStatus != null && adb != null) {
            textStatus.setText(R.string.conn_status_fail);
            setAction(getString(R.string.conn_status_fail));
        }
    }

    private void buildLegend() {
        if (legendRow == null) return;
        legendRow.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int gap = Math.round(6 * d);
        legendRow.addView(legendChip(getString(R.string.legend_offline), InstrumentStatus.STEEL, gap));
        legendRow.addView(legendChip(getString(R.string.legend_detected), InstrumentStatus.AMBER, gap));
        legendRow.addView(legendChip(getString(R.string.legend_connected), InstrumentStatus.GREEN, 0));
    }

    private TextView legendChip(String label, int color, int marginEnd) {
        TextView chip = new TextView(requireContext());
        float d = getResources().getDisplayMetrics().density;
        int h = Math.round(8 * d);
        int v = Math.round(4 * d);
        chip.setText(label);
        chip.setTextSize(12);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(6 * d);
        chip.setBackground(bg);
        chip.setTextColor(InstrumentStatus.textOn(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(marginEnd);
        chip.setLayoutParams(lp);
        return chip;
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
        if (busy) return;
        String ip = DevicePrefs.getIp(requireContext());
        if (TextUtils.isEmpty(ip)) {
            toast(getString(R.string.label_device_ip_missing));
            return;
        }
        int port = DevicePrefs.getAdbPort(requireContext());
        setBusy(true, getString(R.string.action_busy_adb));
        textStatus.setText(getString(R.string.conn_status_connecting, ip));
        adb.connect(ip, port, (ok, msg) -> postUi(() -> {
            textStatus.setText(msg);
            if (!ok) {
                setBusy(false, msg);
                toast("Connessione fallita");
                return;
            }
            setAction("Ping RemoteBridge…");
            adb.sendLine("ping", (pingOk, ack) -> postUi(() -> {
                String done = pingOk ? "ADB ok · RemoteBridge " + ack : ack;
                textStatus.setText(done);
                setBusy(false, done);
                toast(pingOk ? "Connesso" : "Bridge non risponde");
                if (pingOk) {
                    refreshState(false);
                }
            }));
        }));
    }

    private void sendCmd(String line, String busyMsg, long waitMs) {
        if (busy) {
            toast("Operazione già in corso");
            return;
        }
        if (adb == null || !adb.isConnected()) {
            String fail = getString(R.string.conn_status_fail);
            setAction(fail);
            toast(fail);
            return;
        }
        setBusy(true, busyMsg);
        textStatus.setText(busyMsg);
        adb.sendLine(line, waitMs, (ok, msg) -> postUi(() -> {
            String result = TextUtils.isEmpty(msg) ? (ok ? "OK" : "Errore") : msg;
            textStatus.setText(result);
            setBusy(false, summarizeAck(line, ok, result));
            toast(ok ? "OK" : "Errore");
            if (ok) {
                refreshState(false);
            }
        }));
    }

    private String summarizeAck(String line, boolean ok, String ack) {
        if (!ok) return "Errore: " + ack;
        if (line.contains("|wifi|connect")) return "Collegamento avviato sul Pi. " + ack;
        if (line.contains("|wifi|disconnect")) return "Disconnessione inviata. " + ack;
        if (line.contains("|wifi|scan")) {
            if (ack.contains("|wifi|scan|[")) {
                int n = countScanHits(ack);
                return n > 0
                        ? "Scan AP: trovati " + n + " Vespera. " + shortAck(ack)
                        : "Scan AP completata: nessun Vespera in radio. " + shortAck(ack);
            }
            return "Scan AP completata. " + shortAck(ack);
        }
        if (line.contains("|singularity|restart")) return "Riavvio Singularity richiesto. " + ack;
        return ack;
    }

    private static int countScanHits(String ack) {
        int n = 0;
        int from = 0;
        while (true) {
            int i = ack.indexOf("\"ssid\"", from);
            if (i < 0) break;
            n++;
            from = i + 6;
        }
        return n;
    }

    private static String shortAck(String ack) {
        if (ack == null) return "";
        return ack.length() > 90 ? ack.substring(0, 90) + "…" : ack;
    }

    private void refreshState(boolean showBusy) {
        if (adb == null || !adb.isConnected()) {
            if (showBusy) {
                setAction(getString(R.string.conn_status_fail));
            }
            return;
        }
        if (showBusy) {
            if (busy) return;
            setBusy(true, getString(R.string.action_busy_refresh));
        }
        File dest = new File(requireContext().getCacheDir(), "remote.state.json");
        adb.pullState(dest, (ok, msg) -> postUi(() -> {
            if (!ok) {
                textStatus.setText(msg);
                if (showBusy) {
                    setBusy(false, msg);
                }
                return;
            }
            RemoteState state = RemoteState.parse(adb.readPulledText(dest));
            textWifi.setText(TextUtils.isEmpty(state.wifiLine) ? "—" : state.wifiLine);
            InstrumentStatus.Bar ves = InstrumentStatus.vespera(state.wifi);
            InstrumentStatus.paint(barVespera, ves);
            textSingularityInfo.setText(InstrumentStatus.singularityInfo(state.singularity));
            InstrumentStatus.paint(barSingularity, InstrumentStatus.singularity(state.singularity));
            wifiConnected = isWifiConnected(state.wifi);
            updateWifiButtons();
            bindVesperaBarClick(state.wifi);
            String ver = TextUtils.isEmpty(state.appVersion) ? "?" : state.appVersion;
            String refreshed = getString(R.string.label_system_refreshed, ver);
            textStatus.setText(refreshed);
            if (showBusy) {
                setBusy(false, refreshed);
            } else if (!busy) {
                setAction(wifiConnected
                        ? refreshed
                        : canCollega(state.wifi)
                        ? getString(R.string.hint_collega_bar)
                        : refreshed);
            }
        }));
    }

    private static boolean isWifiConnected(JSONObject wifi) {
        return wifi != null
                && "CONNECTED".equals(wifi.optString("status", ""))
                && wifi.optBoolean("hasNetwork", true);
    }

    private static boolean canCollega(JSONObject wifi) {
        if (wifi == null) return false;
        if (isWifiConnected(wifi)) return false;
        String status = wifi.optString("status", "");
        if (status.startsWith("REQUESTING")) return false;
        return wifi.optBoolean("online", false)
                || wifi.optBoolean("configured", !wifi.optString("ssid", "").isEmpty());
    }

    private void bindVesperaBarClick(JSONObject wifi) {
        boolean collega = canCollega(wifi);
        barVespera.setClickable(collega && !busy);
        barVespera.setEnabled(collega && !busy);
    }

    private void updateWifiButtons() {
        if (btnWifiConnect == null || btnWifiDisconnect == null) return;
        if (wifiConnected) {
            btnWifiConnect.setVisibility(View.GONE);
            btnWifiDisconnect.setVisibility(View.VISIBLE);
        } else {
            btnWifiConnect.setVisibility(View.VISIBLE);
            btnWifiDisconnect.setVisibility(View.GONE);
            btnWifiConnect.setText(busy
                    ? R.string.btn_wifi_connecting
                    : R.string.btn_wifi_connect);
        }
    }

    private void setBusy(boolean on, String message) {
        busy = on;
        setAction(message);
        updateWifiButtons();
        boolean enable = !on;
        if (btnConnect != null) btnConnect.setEnabled(enable);
        if (btnRefreshStatus != null) btnRefreshStatus.setEnabled(enable);
        if (btnWifiConnect != null) btnWifiConnect.setEnabled(enable);
        if (btnWifiDisconnect != null) btnWifiDisconnect.setEnabled(enable);
        if (btnWifiScan != null) btnWifiScan.setEnabled(enable);
        if (btnRestartSingularity != null) btnRestartSingularity.setEnabled(enable);
        if (barVespera != null) {
            barVespera.setClickable(enable && !wifiConnected);
            barVespera.setEnabled(enable && !wifiConnected);
        }
    }

    private void setAction(String message) {
        if (textAction != null) {
            textAction.setText(TextUtils.isEmpty(message) ? getString(R.string.action_idle) : message);
        }
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
