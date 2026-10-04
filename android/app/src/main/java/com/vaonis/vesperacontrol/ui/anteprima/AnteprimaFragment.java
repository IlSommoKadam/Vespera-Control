package com.vaonis.vesperacontrol.ui.anteprima;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.ftp.FtpPreview;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Anteprima oggetti FTP + ultimo *-output.jpg (niente stacking). */
public class AnteprimaFragment extends Fragment {

    private EditText editHost;
    private EditText editPort;
    private ListView listFiles;
    private ImageView imagePreview;
    private ArrayAdapter<FtpPreview.Item> adapter;
    private final List<FtpPreview.Item> items = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

        editHost = view.findViewById(R.id.editFtpHost);
        editPort = view.findViewById(R.id.editFtpPort);
        listFiles = view.findViewById(R.id.listFtpFiles);
        imagePreview = view.findViewById(R.id.imagePreview);
        Button btnConnect = view.findViewById(R.id.btnFtpConnect);
        Button btnRefresh = view.findViewById(R.id.btnFtpRefresh);

        adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, items);
        listFiles.setAdapter(adapter);

        btnConnect.setOnClickListener(v -> refreshList());
        btnRefresh.setOnClickListener(v -> refreshList());
        listFiles.setOnItemClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= items.size()) return;
            loadPreview(items.get(position));
        });
    }

    private void refreshList() {
        String host = editHost.getText().toString().trim();
        int port = parsePort();
        Toast.makeText(requireContext(), "Elenco oggetti…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                List<FtpPreview.Item> found = FtpPreview.listObjects(host, port);
                postUi(() -> {
                    items.clear();
                    items.addAll(found);
                    adapter.notifyDataSetChanged();
                    Toast.makeText(requireContext(),
                            found.size() + " oggetti", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                postUi(() -> Toast.makeText(requireContext(),
                        e.getMessage() == null ? "FTP errore" : e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loadPreview(FtpPreview.Item item) {
        String host = editHost.getText().toString().trim();
        int port = parsePort();
        Toast.makeText(requireContext(), "Scarico anteprima…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                Bitmap bmp = FtpPreview.loadLatestOutput(host, port, item.folder);
                postUi(() -> {
                    imagePreview.setImageBitmap(bmp);
                    imagePreview.setContentDescription(item.label);
                });
            } catch (Exception e) {
                postUi(() -> Toast.makeText(requireContext(),
                        e.getMessage() == null ? "Anteprima fallita" : e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private int parsePort() {
        try {
            return Integer.parseInt(editPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 2121;
        }
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
