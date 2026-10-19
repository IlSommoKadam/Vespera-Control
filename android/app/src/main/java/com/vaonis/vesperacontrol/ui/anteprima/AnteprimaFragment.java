package com.vaonis.vesperacontrol.ui.anteprima;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.DevicePrefs;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.ftp.FtpListStore;
import com.vaonis.vesperacontrol.ftp.FtpPreview;
import com.vaonis.vesperacontrol.ui.TabRefreshable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Anteprima oggetti FTP + ultimo *-output.jpg (niente stacking). */
public class AnteprimaFragment extends Fragment implements TabRefreshable {

    private TextView textHost;
    private TextView textListStatus;
    private TextView textDownloadPct;
    private ProgressBar progressDownload;
    private RadioGroup radioFtpSource;
    private ListView listFiles;
    private ImageView imagePreview;
    private ArrayAdapter<FtpPreview.Item> adapter;
    private final List<FtpPreview.Item> items = new ArrayList<>();
    /** Executor statico: non si spegne con la view, così il download non si annulla in background. */
    private static final ExecutorService DOWNLOAD_EXEC = Executors.newSingleThreadExecutor();
    private final AtomicInteger downloadGen = new AtomicInteger();
    private Bitmap currentPreview;
    private String currentLabel = "";
    private String downloadLabel = "";
    private String currentSuggestedName = "vespera-preview-edited.jpg";

    private final FtpListStore.Listener listListener = this::applyListState;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_anteprima, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        textHost = view.findViewById(R.id.textFtpHost);
        textListStatus = view.findViewById(R.id.textListStatus);
        textDownloadPct = view.findViewById(R.id.textDownloadPct);
        progressDownload = view.findViewById(R.id.progressDownload);
        radioFtpSource = view.findViewById(R.id.radioFtpSource);
        listFiles = view.findViewById(R.id.listFtpFiles);
        imagePreview = view.findViewById(R.id.imagePreview);
        Button btnList = view.findViewById(R.id.btnFtpList);
        RadioButton radioHd = view.findViewById(R.id.radioFtpHd);
        RadioButton radioVesp = view.findViewById(R.id.radioFtpVespera);

        String source = DevicePrefs.getFtpSource(requireContext());
        if (DevicePrefs.SOURCE_VESP.equals(source)) {
            radioVesp.setChecked(true);
        } else {
            radioHd.setChecked(true);
        }
        radioFtpSource.setOnCheckedChangeListener((group, checkedId) -> {
            String next = checkedId == R.id.radioFtpVespera
                    ? DevicePrefs.SOURCE_VESP : DevicePrefs.SOURCE_HD;
            DevicePrefs.setFtpSource(requireContext(), next);
            showHost();
        });

        showHost();
        adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, items);
        listFiles.setAdapter(adapter);

        btnList.setOnClickListener(v ->
                FtpListStore.get().refresh(requireContext(), FtpListStore.MODE_BUTTON));
        listFiles.setOnItemClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= items.size()) return;
            loadPreview(items.get(position));
        });
        imagePreview.setOnClickListener(v -> openEditor());
        setDownloadUi(false, 0);

        FtpListStore.get().init(requireContext());
        FtpListStore.get().addListener(listListener);
    }

    @Override
    public void onTabSelected() {
        showHost();
        maybeRequestNotifyPermission();
        if (!DevicePrefs.getIp(requireContext()).isEmpty()) {
            FtpListStore.get().refresh(requireContext(), FtpListStore.MODE_TAB);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        showHost();
    }

    private void applyListState(@NonNull FtpListStore.State state) {
        if (!isAdded() || adapter == null) return;
        items.clear();
        items.addAll(state.items);
        adapter.notifyDataSetChanged();
        setListStatus(state.statusText);
    }

    private void showHost() {
        if (textHost == null) return;
        String host = DevicePrefs.getIp(requireContext());
        if (host.isEmpty()) {
            textHost.setText(R.string.label_device_ip_missing);
            return;
        }
        boolean vesp = DevicePrefs.SOURCE_VESP.equals(DevicePrefs.getFtpSource(requireContext()));
        int port = DevicePrefs.getFtpPort(requireContext());
        textHost.setText(getString(R.string.label_ftp_source_line,
                host,
                vesp ? getString(R.string.label_ftp_vespera) : getString(R.string.label_ftp_hd),
                port));
    }

    private String deviceHost() {
        String host = DevicePrefs.getIp(requireContext());
        if (host.isEmpty()) {
            Toast.makeText(requireContext(), R.string.label_device_ip_missing, Toast.LENGTH_LONG).show();
            return null;
        }
        return host;
    }

    private void loadPreview(FtpPreview.Item item) {
        String host = deviceHost();
        if (host == null) return;
        int port = DevicePrefs.getFtpPort(requireContext());
        final int gen = downloadGen.incrementAndGet();
        downloadLabel = item.label == null || item.label.trim().isEmpty() ? item.folder : item.label;
        setDownloadUi(true, 0);
        DOWNLOAD_EXEC.execute(() -> {
            try {
                FtpPreview.Preview preview = FtpPreview.loadLatestPreview(
                        host, port, item.folder, percent -> postUi(() -> {
                            if (gen != downloadGen.get()) return;
                            setDownloadUi(true, percent);
                        }));
                postUi(() -> {
                    if (gen != downloadGen.get()) return;
                    setDownloadUi(false, 100);
                    currentPreview = preview.bitmap;
                    currentLabel = item.label;
                    currentSuggestedName = editedName(preview.fileName);
                    imagePreview.setImageBitmap(preview.bitmap);
                    imagePreview.setContentDescription(item.label);
                });
            } catch (Exception e) {
                postUi(() -> {
                    if (gen != downloadGen.get()) return;
                    setDownloadUi(false, 0);
                    Toast.makeText(requireContext(),
                            e.getMessage() == null ? "Anteprima fallita" : e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setDownloadUi(boolean active, int percent) {
        if (progressDownload == null || textDownloadPct == null) return;
        progressDownload.setVisibility(active ? View.VISIBLE : View.GONE);
        textDownloadPct.setVisibility(active ? View.VISIBLE : View.GONE);
        progressDownload.setIndeterminate(false);
        progressDownload.setProgress(Math.max(0, Math.min(100, percent)));
        String name = downloadLabel == null || downloadLabel.isEmpty() ? "anteprima" : downloadLabel;
        textDownloadPct.setText(getString(R.string.preview_download_pct, name, percent));
    }

    private void setListStatus(String text) {
        if (textListStatus == null) return;
        textListStatus.setText(text);
        textListStatus.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openEditor() {
        if (currentPreview == null || currentPreview.isRecycled()) {
            Toast.makeText(requireContext(), R.string.editor_no_image, Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap copy = currentPreview.copy(Bitmap.Config.ARGB_8888, false);
        if (copy == null) {
            Toast.makeText(requireContext(), R.string.editor_no_image, Toast.LENGTH_SHORT).show();
            return;
        }
        PreviewEditorSession.set(copy, currentLabel, currentSuggestedName);
        startActivity(new Intent(requireContext(), PreviewEditorActivity.class));
    }

    private void maybeRequestNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33 || getContext() == null) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 91);
    }

    private static String editedName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "vespera-preview-edited.jpg";
        }
        String name = fileName.trim();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".jpg";
        if (!stem.toLowerCase(java.util.Locale.US).endsWith("-edited")) {
            stem = stem + "-edited";
        }
        return stem + ext;
    }

    private void postUi(Runnable r) {
        if (getActivity() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            r.run();
        });
    }

    @Override
    public void onDestroyView() {
        FtpListStore.get().removeListener(listListener);
        textHost = null;
        textListStatus = null;
        textDownloadPct = null;
        progressDownload = null;
        radioFtpSource = null;
        listFiles = null;
        imagePreview = null;
        adapter = null;
        super.onDestroyView();
    }
}
