package com.vaonis.vesperacontrol.ui.settings;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.text.Editable;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.AdbBridgeHolder;
import com.vaonis.vesperacontrol.DevicePrefs;
import com.vaonis.vesperacontrol.ObservatorySite;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.adb.AdbBridge;
import com.vaonis.vesperacontrol.update.AppUpdates;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

public class ImpostazioniFragment extends Fragment {

    private static final int REQ_LOCATION = 47;

    private TextView textUpdateStatus;
    private TextView textSiteSource;
    private EditText editLat;
    private EditText editLon;
    private boolean fillingSite;
    private AdbBridge adb;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_impostazioni, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        textUpdateStatus = view.findViewById(R.id.textUpdateStatus);
        Button btnCheckUpdates = view.findViewById(R.id.btnCheckUpdates);
        editLat = view.findViewById(R.id.editSiteLat);
        editLon = view.findViewById(R.id.editSiteLon);
        textSiteSource = view.findViewById(R.id.textSiteSource);
        adb = AdbBridgeHolder.get(requireContext());

        bindNetwork(view);
        fillSiteFields();
        bindSite(editLat);
        bindSite(editLon);
        view.<Button>findViewById(R.id.btnSiteGps).setOnClickListener(v -> readGps());
        view.<Button>findViewById(R.id.btnSiteVespera).setOnClickListener(v -> readVespera());

        btnCheckUpdates.setOnClickListener(v -> {
            textUpdateStatus.setText(R.string.update_checking);
            AppUpdates.check(requireActivity(), false, textUpdateStatus);
        });
        textUpdateStatus.setText(AppUpdates.lastCheck(requireContext()));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (textUpdateStatus != null) {
            textUpdateStatus.setText(AppUpdates.lastCheck(requireContext()));
        }
        fillSiteFields();
    }

    /** IP e porte del Pi: tutte qui (Connessioni, Anteprima e Schermo le leggono). */
    private void bindNetwork(View view) {
        EditText ip = view.findViewById(R.id.editSetIp);
        EditText adbPort = view.findViewById(R.id.editSetAdbPort);
        EditText ftpHd = view.findViewById(R.id.editSetFtpHd);
        EditText ftpVesp = view.findViewById(R.id.editSetFtpVespera);
        EditText scrcpy = view.findViewById(R.id.editSetScrcpyPort);
        ip.setText(DevicePrefs.getIp(requireContext()));
        adbPort.setText(String.valueOf(DevicePrefs.getAdbPort(requireContext())));
        ftpHd.setText(String.valueOf(DevicePrefs.getFtpHdPort(requireContext())));
        ftpVesp.setText(String.valueOf(DevicePrefs.getFtpVesperaPort(requireContext())));
        scrcpy.setText(String.valueOf(DevicePrefs.getScrcpyAdbPort(requireContext())));
        onText(ip, v -> DevicePrefs.setIp(requireContext(), v));
        onText(adbPort, v -> DevicePrefs.setAdbPort(requireContext(), v));
        onText(ftpHd, v -> DevicePrefs.setFtpHdPort(requireContext(), v));
        onText(ftpVesp, v -> DevicePrefs.setFtpVesperaPort(requireContext(), v));
        onText(scrcpy, v -> DevicePrefs.setScrcpyAdbPort(requireContext(), v));
    }

    private interface Saver {
        void save(String value);
    }

    private void onText(EditText edit, Saver saver) {
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

    private void fillSiteFields() {
        if (editLat == null || editLon == null) return;
        fillingSite = true;
        if (DevicePrefs.hasSite(requireContext())) {
            editLat.setText(String.format(Locale.US, "%.5f", DevicePrefs.getLat(requireContext())));
            editLon.setText(String.format(Locale.US, "%.5f", DevicePrefs.getLon(requireContext())));
        }
        fillingSite = false;
        showSiteSource();
    }

    private void showSiteSource() {
        if (textSiteSource == null) return;
        String source = DevicePrefs.getSiteSource(requireContext());
        int id = R.string.site_source_missing;
        if (DevicePrefs.SITE_MANUAL.equals(source)) id = R.string.site_source_manual;
        else if (DevicePrefs.SITE_GPS.equals(source)) id = R.string.site_source_gps;
        else if (DevicePrefs.SITE_VESPERA.equals(source)) id = R.string.site_source_vespera;
        textSiteSource.setText(id);
    }

    private void bindSite(EditText edit) {
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (fillingSite) return;
                saveManualSite();
            }
        });
    }

    private void saveManualSite() {
        double lat = DevicePrefs.parseCoord(editLat.getText() == null ? "" : editLat.getText().toString());
        double lon = DevicePrefs.parseCoord(editLon.getText() == null ? "" : editLon.getText().toString());
        if (Double.isNaN(lat) || Double.isNaN(lon)) return;
        DevicePrefs.setSite(requireContext(), lat, lon, DevicePrefs.SITE_MANUAL);
        showSiteSource();
    }

    private void applySite(double lat, double lon, String source) {
        DevicePrefs.setSite(requireContext(), lat, lon, source);
        fillSiteFields();
        Toast.makeText(requireContext(), R.string.site_saved, Toast.LENGTH_SHORT).show();
    }

    private void readGps() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
            return;
        }
        LocationManager manager = requireContext().getSystemService(LocationManager.class);
        if (manager == null) {
            Toast.makeText(requireContext(), R.string.site_gps_fail, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), R.string.site_gps_wait, Toast.LENGTH_SHORT).show();
        Location last = newest(manager);
        if (last != null) {
            applySite(last.getLatitude(), last.getLongitude(), DevicePrefs.SITE_GPS);
            return;
        }
        String provider = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER
                : LocationManager.NETWORK_PROVIDER;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(provider, null, runnable -> runnable.run(), this::onGpsFix);
            } else {
                manager.requestSingleUpdate(provider, this::onGpsFix, Looper.getMainLooper());
            }
        } catch (SecurityException denied) {
            Toast.makeText(requireContext(), R.string.site_gps_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void onGpsFix(Location location) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (location == null) {
                Toast.makeText(requireContext(), R.string.site_gps_fail, Toast.LENGTH_LONG).show();
                return;
            }
            applySite(location.getLatitude(), location.getLongitude(), DevicePrefs.SITE_GPS);
        });
    }

    private Location newest(LocationManager manager) {
        Location best = null;
        try {
            for (String provider : manager.getProviders(true)) {
                Location fix = manager.getLastKnownLocation(provider);
                if (fix == null) continue;
                if (best == null || fix.getTime() > best.getTime()) best = fix;
            }
        } catch (SecurityException ignored) {
            return null;
        }
        return best;
    }

    private void readVespera() {
        if (adb == null || !adb.isConnected()) {
            Toast.makeText(requireContext(), R.string.conn_status_fail, Toast.LENGTH_LONG).show();
            return;
        }
        File dest = new File(requireContext().getCacheDir(), "remote.state.json");
        adb.pullState(dest, (ok, msg) -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!ok) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                    return;
                }
                double[] site = null;
                try {
                    JSONObject root = new JSONObject(adb.readPulledText(dest));
                    site = ObservatorySite.parse(root.optJSONObject("telescope"));
                } catch (Exception ignored) {
                }
                if (site == null) {
                    Toast.makeText(requireContext(), R.string.site_vespera_fail, Toast.LENGTH_LONG).show();
                    return;
                }
                applySite(site[0], site[1], DevicePrefs.SITE_VESPERA);
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) return;
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) granted = true;
        }
        if (granted) readGps();
        else Toast.makeText(requireContext(), R.string.site_gps_denied, Toast.LENGTH_LONG).show();
    }

}
