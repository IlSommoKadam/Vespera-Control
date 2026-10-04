package com.vaonis.vesperacontrol.ui.notifiche;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.AdbBridgeHolder;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.adb.AdbBridge;

import java.io.File;

/**
 * Stub notifiche: legge remote.state.json / remote.ack via AdbBridge.
 * Parsing eventi notifiche = TODO.
 */
public class NotificheFragment extends Fragment {

    private TextView textNotifiche;
    private AdbBridge adb;

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
        textNotifiche = view.findViewById(R.id.textNotifiche);
        Button btnRefresh = view.findViewById(R.id.btnRefreshNotifiche);
        btnRefresh.setOnClickListener(v -> refresh());
    }

    private void refresh() {
        File state = new File(requireContext().getCacheDir(), "remote.state.json");
        File ack = new File(requireContext().getCacheDir(), "remote.ack");
        adb.pullState(state, (okState, msgState) ->
                adb.pullAck(ack, (okAck, msgAck) -> postUi(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== remote.state.json ===\n");
                    sb.append(okState ? adb.readPulledText(state) : msgState);
                    sb.append("\n\n=== remote.ack ===\n");
                    String ackBody = okAck ? adb.readPulledText(ack) : msgAck;
                    sb.append(TextUtils.isEmpty(ackBody) ? "(vuoto)" : ackBody);
                    textNotifiche.setText(sb.toString());
                }))
        );
    }

    private void postUi(Runnable r) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(r);
    }
}
