package com.vaonis.vesperacontrol.ui.notifiche;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.AdbBridgeHolder;
import com.vaonis.vesperacontrol.PlansCloud;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.TelegramFlags;
import com.vaonis.vesperacontrol.adb.AdbBridge;
import com.vaonis.vesperacontrol.ui.TabRefreshable;

import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class NotificheFragment extends Fragment implements TabRefreshable {

    private TextView textStatus;
    private CheckBox checkEnabled;
    private final Map<String, CheckBox> eventChecks = new LinkedHashMap<>();
    private AdbBridge adb;
    private boolean binding;
    private boolean configured;
    private EditText editPlansToken;
    private EditText editPlansChat;
    private TextView textPlansStatus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_notifiche, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adb = AdbBridgeHolder.get(requireContext());
        textStatus = view.findViewById(R.id.textTelegramStatus);
        checkEnabled = view.findViewById(R.id.checkTelegramEnabled);
        checkEnabled.setOnCheckedChangeListener(this::onToggleAll);

        editPlansToken = view.findViewById(R.id.editPlansToken);
        editPlansChat = view.findViewById(R.id.editPlansChat);
        textPlansStatus = view.findViewById(R.id.textPlansStatus);
        editPlansToken.setText(PlansCloud.token(requireContext()));
        editPlansChat.setText(PlansCloud.chat(requireContext()));
        textPlansStatus.setText(PlansCloud.status(requireContext()));
        view.<Button>findViewById(R.id.btnPlansFromHelper).setOnClickListener(v -> plansFromHelper());
        view.<Button>findViewById(R.id.btnPlansSync).setOnClickListener(v -> {
            PlansCloud.setBot(requireContext(), editPlansToken.getText().toString(),
                    editPlansChat.getText().toString());
            syncPlans();
        });

        GridLayout grid = view.findViewById(R.id.gridTelegramEvents);
        eventChecks.clear();
        int textColor = ContextCompat.getColor(requireContext(), R.color.vespera_text);
        for (int i = 0; i < TelegramFlags.KEYS.length; i++) {
            CheckBox cb = new CheckBox(requireContext());
            cb.setText(TelegramFlags.LABELS[i]);
            cb.setTextColor(textColor);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 2),
                    GridLayout.spec(i % 2, 1f));
            lp.width = 0;
            cb.setLayoutParams(lp);
            cb.setOnCheckedChangeListener(this::onToggleEvent);
            grid.addView(cb);
            eventChecks.put(TelegramFlags.KEYS[i], cb);
        }
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

    private void plansFromHelper() {
        if (adb == null || !adb.isConnected()) {
            textPlansStatus.setText(R.string.conn_status_fail);
            return;
        }
        textPlansStatus.setText(R.string.plans_bot_fetching);
        PlansCloud.fetchFromHelper(requireContext(), adb, (ok, msg) -> postUi(() -> {
            textPlansStatus.setText(msg);
            if (!ok) return;
            editPlansToken.setText(PlansCloud.token(requireContext()));
            editPlansChat.setText(PlansCloud.chat(requireContext()));
            syncPlans();
        }));
    }

    private void syncPlans() {
        textPlansStatus.setText(R.string.plans_syncing);
        PlansCloud.sync(requireContext(), adb, () -> postUi(() ->
                textPlansStatus.setText(PlansCloud.status(requireContext()))));
    }

    /** Interruttore generale: accende o spegne tutti gli avvisi. */
    private void onToggleAll(CompoundButton button, boolean checked) {
        if (binding) return;
        binding = true;
        for (CheckBox cb : eventChecks.values()) cb.setChecked(checked);
        binding = false;
        send();
    }

    /** Singolo avviso: il generale resta acceso se almeno uno è attivo. */
    private void onToggleEvent(CompoundButton button, boolean checked) {
        if (binding) return;
        binding = true;
        checkEnabled.setChecked(activeCount() > 0);
        binding = false;
        send();
    }

    private int activeCount() {
        int n = 0;
        for (CheckBox cb : eventChecks.values()) if (cb.isChecked()) n++;
        return n;
    }

    private void send() {
        if (adb == null || !adb.isConnected()) {
            if (textStatus != null) textStatus.setText(R.string.conn_status_fail);
            refresh();
            return;
        }
        Map<String, Boolean> events = new LinkedHashMap<>();
        for (Map.Entry<String, CheckBox> e : eventChecks.entrySet()) {
            events.put(e.getKey(), e.getValue().isChecked());
        }
        updateStatusLabel();
        String line = "set|telegram|" + TelegramFlags.payload(checkEnabled.isChecked(), events);
        adb.sendLine(line, (ok, msg) -> postUi(() -> {
            Toast.makeText(requireContext(),
                    ok ? getString(R.string.telegram_saved) : msg,
                    Toast.LENGTH_SHORT).show();
            if (ok) refresh();
        }));
    }

    private void updateStatusLabel() {
        if (!configured) {
            textStatus.setText(R.string.telegram_not_configured);
            return;
        }
        int active = activeCount();
        if (!checkEnabled.isChecked() || active == 0) {
            textStatus.setText(R.string.telegram_off);
        } else {
            textStatus.setText(getString(R.string.telegram_on_count, active, eventChecks.size()));
        }
    }

    private void refresh() {
        if (adb == null || !adb.isConnected()) {
            if (textStatus != null) {
                textStatus.setText(R.string.conn_status_fail);
            }
            return;
        }
        File state = new File(requireContext().getCacheDir(), "remote.state.json");
        adb.pullState(state, (okState, msgState) -> postUi(() -> {
            if (!okState) {
                textStatus.setText(msgState);
                return;
            }
            String body = adb.readPulledText(state);
            JSONObject tg = null;
            try {
                JSONObject root = new JSONObject(body == null ? "" : body);
                tg = root.optJSONObject("telegram");
            } catch (Exception ignored) {
            }
            binding = true;
            checkEnabled.setChecked(TelegramFlags.isEnabled(tg));
            for (Map.Entry<String, CheckBox> e : eventChecks.entrySet()) {
                e.getValue().setChecked(TelegramFlags.isEventEnabled(tg, e.getKey()));
            }
            binding = false;
            if (tg == null) {
                textStatus.setText(R.string.telegram_unknown);
                return;
            }
            configured = tg.optBoolean("configured", false);
            updateStatusLabel();
        }));
    }

    private void postUi(Runnable r) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(r);
    }
}
