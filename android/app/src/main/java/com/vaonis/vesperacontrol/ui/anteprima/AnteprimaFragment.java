package com.vaonis.vesperacontrol.ui.anteprima;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.DevicePrefs;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.ftp.FtpPreview;
import com.vaonis.vesperacontrol.ui.TabRefreshable;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Anteprima oggetti FTP + ultimo *-output.jpg (niente stacking). */
public class AnteprimaFragment extends Fragment implements TabRefreshable {

    private static final String CHANNEL_ID = "vespera_preview";
    private static final int NOTIFY_LIST = 4101;
    /** Avvio: carica in silenzio. Pulsante: toast, niente notifica. Tab: notifica solo se ci sono novità. */
    private static final int MODE_SILENT = 0;
    private static final int MODE_BUTTON = 1;
    private static final int MODE_TAB = 2;

    private TextView textHost;
    private TextView textListStatus;
    private TextView textDownloadPct;
    private ProgressBar progressDownload;
    private RadioGroup radioFtpSource;
    private ListView listFiles;
    private ImageView imagePreview;
    private ArrayAdapter<FtpPreview.Item> adapter;
    private final List<FtpPreview.Item> items = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger downloadGen = new AtomicInteger();
    private Bitmap currentPreview;
    private String currentLabel = "";
    private String downloadLabel = "";
    private String currentSuggestedName = "vespera-preview-edited.jpg";
    private boolean loadedOnce;
    private boolean listing;
    /** Elenco già caricato (cartella → firma ultimo output), per sorgente host:porta. */
    private final Map<String, String> loadedSignatures = new HashMap<>();
    private String loadedKey = "";

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
        ensureNotificationChannel();

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

        btnList.setOnClickListener(v -> refreshList(MODE_BUTTON));
        listFiles.setOnItemClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= items.size()) return;
            loadPreview(items.get(position));
        });
        imagePreview.setOnClickListener(v -> openEditor());
        setDownloadUi(false, 0);
    }

    @Override
    public void onTabSelected() {
        showHost();
        if (!DevicePrefs.getIp(requireContext()).isEmpty()) {
            refreshList(MODE_TAB);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        showHost();
        if (!loadedOnce && !DevicePrefs.getIp(requireContext()).isEmpty()) {
            loadedOnce = true;
            refreshList(MODE_SILENT);
        }
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

    private void refreshList(int mode) {
        if (listing) return; // un solo elenco alla volta: niente raffiche di aggiornamenti
        String host = deviceHost();
        if (host == null) return;
        int port = DevicePrefs.getFtpPort(requireContext());
        String key = host + ":" + port;
        listing = true;
        setListStatus(getString(R.string.preview_list_updating));
        if (mode == MODE_BUTTON) {
            Toast.makeText(requireContext(), R.string.preview_listing, Toast.LENGTH_SHORT).show();
        }
        executor.execute(() -> {
            try {
                List<FtpPreview.Item> found = FtpPreview.listObjects(host, port);
                postUi(() -> {
                    listing = false;
                    // Confronto solo con quanto già caricato dalla stessa sorgente.
                    boolean hasBaseline = key.equals(loadedKey) && !loadedSignatures.isEmpty();
                    int added = 0;
                    int updated = 0;
                    for (FtpPreview.Item item : found) {
                        String before = loadedSignatures.get(item.folder);
                        if (!hasBaseline) {
                            item.mark = "";
                        } else if (before == null) {
                            item.mark = getString(R.string.preview_mark_new);
                            added++;
                        } else if (!before.equals(item.signature)) {
                            item.mark = getString(R.string.preview_mark_updating);
                            updated++;
                        } else {
                            item.mark = "";
                        }
                    }
                    loadedSignatures.clear();
                    for (FtpPreview.Item item : found) loadedSignatures.put(item.folder, item.signature);
                    loadedKey = key;
                    items.clear();
                    items.addAll(found);
                    adapter.notifyDataSetChanged();

                    String summary = listSummary(found.size(), added, updated, hasBaseline);
                    setListStatus(summary);
                    if (mode == MODE_BUTTON) {
                        Toast.makeText(requireContext(), summary, Toast.LENGTH_SHORT).show();
                    } else if (mode == MODE_TAB && (added > 0 || updated > 0)) {
                        notifyListUpdated(added, updated);
                    }
                });
            } catch (Exception e) {
                postUi(() -> {
                    listing = false;
                    String msg = e.getMessage() == null ? "FTP errore" : e.getMessage();
                    setListStatus(msg);
                    if (mode == MODE_BUTTON) {
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private String listSummary(int count, int added, int updated, boolean hasBaseline) {
        String base = getString(R.string.preview_list_count, count);
        if (!hasBaseline) return base;
        if (added == 0 && updated == 0) return base + " \u00b7 " + getString(R.string.preview_no_changes);
        return base + " \u00b7 " + changesText(added, updated);
    }

    private String changesText(int added, int updated) {
        StringBuilder sb = new StringBuilder();
        if (added > 0) sb.append(getString(R.string.preview_changes_new, added));
        if (updated > 0) {
            if (sb.length() > 0) sb.append(" \u00b7 ");
            sb.append(getString(R.string.preview_changes_updating, updated));
        }
        return sb.toString();
    }

    private void setListStatus(String text) {
        if (textListStatus == null) return;
        textListStatus.setText(text);
        textListStatus.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void loadPreview(FtpPreview.Item item) {
        String host = deviceHost();
        if (host == null) return;
        int port = DevicePrefs.getFtpPort(requireContext());
        final int gen = downloadGen.incrementAndGet();
        downloadLabel = item.label == null || item.label.trim().isEmpty() ? item.folder : item.label;
        setDownloadUi(true, 0);
        executor.execute(() -> {
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

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26 || getContext() == null) return;
        NotificationManager nm = requireContext().getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.preview_notify_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(channel);
    }

    private void notifyListUpdated(int added, int updated) {
        if (getContext() == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 91);
            return;
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(requireContext(), CHANNEL_ID)
                .setSmallIcon(R.drawable.vespera_launcher_icon)
                .setContentTitle(getString(R.string.preview_notify_title))
                .setContentText(changesText(added, updated))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManager nm = requireContext().getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFY_LIST, builder.build());
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

    private interface Saver {
        void save(String value);
    }

    private void onEdit(EditText edit, Saver saver) {
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
                showHost();
            }
        });
    }

    private void postUi(Runnable r) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(r);
    }

    @Override
    public void onDestroyView() {
        executor.shutdownNow();
        super.onDestroyView();
    }
}
