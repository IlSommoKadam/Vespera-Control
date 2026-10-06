package com.vaonis.vesperacontrol.ui.sistema;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.AdbBridgeHolder;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.RemoteState;
import com.vaonis.vesperacontrol.adb.AdbBridge;
import com.vaonis.vesperacontrol.ui.TabRefreshable;

import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class SistemaFragment extends Fragment implements TabRefreshable {

    /** Stesse chiavi/label di Windows app.py SYSTEM_FLAGS. */
    private static final String[][] SYSTEM_FLAGS = {
            {"photoSync", "flag_photo_sync"},
            {"storageSync", "flag_storage_sync"},
            {"resumeSync", "flag_resume_sync"},
            {"hdMount", "flag_hd_mount"},
            {"clockNtp", "flag_clock_ntp"},
            {"bootStart", "flag_boot_start"},
            {"wifiConnect", "flag_wifi_connect"},
            {"singularityStart", "flag_singularity_start"},
            {"watchdog", "flag_watchdog"},
            {"ftpLocal", "flag_ftp_local"},
            {"keepAlive", "flag_keep_alive"},
            {"sunCheck", "flag_sun_check"},
            {"sunSync", "flag_sun_sync"},
            {"sunTelescopeShutdown", "flag_sun_telescope"},
            {"sunHdShutdown", "flag_sun_hd"},
            {"sunPiShutdown", "flag_sun_pi"},
    };

    private final Map<String, CheckBox> flagBoxes = new LinkedHashMap<>();
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
        buildFlags(view.findViewById(R.id.flagsContainer));

        view.<Button>findViewById(R.id.btnApplySystem)
                .setOnClickListener(v -> applySystem());
    }

    @Override
    public void onTabSelected() {
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adb != null) {
            refresh();
        }
    }

    private void buildFlags(LinearLayout container) {
        if (container == null) return;
        container.removeAllViews();
        flagBoxes.clear();
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = null;
        for (int i = 0; i < SYSTEM_FLAGS.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                container.addView(row);
            }
            CheckBox box = new CheckBox(requireContext());
            box.setChecked(true);
            box.setText(labelFor(SYSTEM_FLAGS[i][1]));
            box.setTextColor(getResources().getColor(R.color.vespera_text, null));
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, (int) (2 * density), (int) (8 * density), (int) (2 * density));
            box.setLayoutParams(lp);
            row.addView(box);
            flagBoxes.put(SYSTEM_FLAGS[i][0], box);
        }
    }

    private String labelFor(String stringName) {
        int id = getResources().getIdentifier(stringName, "string", requireContext().getPackageName());
        return id != 0 ? getString(id) : stringName;
    }

    private void applySystem() {
        try {
            JSONObject payload = new JSONObject();
            for (Map.Entry<String, CheckBox> e : flagBoxes.entrySet()) {
                payload.put(e.getKey(), e.getValue().isChecked());
            }
            String line = "set|system|" + payload.toString();
            adb.sendLine(line, (ok, msg) -> postUi(() -> {
                textLog.setText(msg);
                Toast.makeText(requireContext(), ok ? "OK" : "Errore", Toast.LENGTH_SHORT).show();
                if (ok) {
                    refresh();
                }
            }));
        } catch (Exception e) {
            textLog.setText(e.getMessage() == null ? "Errore JSON" : e.getMessage());
        }
    }

    private void refresh() {
        if (adb == null || !adb.isConnected()) {
            if (textLog != null) {
                textLog.setText(R.string.conn_status_fail);
            }
            return;
        }
        File dest = new File(requireContext().getCacheDir(), "remote.state.json");
        adb.pullState(dest, (pullOk, pullMsg) -> postUi(() -> {
            if (!pullOk) {
                textLog.setText(pullMsg);
                return;
            }
            String body = adb.readPulledText(dest);
            RemoteState state = RemoteState.parse(body);
            syncFlags(state.system);
            textLog.setText(getString(R.string.label_system_refreshed, state.appVersion));
        }));
    }

    private void syncFlags(JSONObject system) {
        if (system == null) return;
        for (Map.Entry<String, CheckBox> e : flagBoxes.entrySet()) {
            if (system.has(e.getKey())) {
                e.getValue().setChecked(system.optBoolean(e.getKey(), e.getValue().isChecked()));
            }
        }
    }

    private void postUi(Runnable r) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(r);
    }
}
