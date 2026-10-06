package com.vaonis.vesperacontrol;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.vaonis.vesperacontrol.adb.AdbBridge;
import com.vaonis.vesperacontrol.ui.TabRefreshable;
import com.vaonis.vesperacontrol.ui.anteprima.AnteprimaFragment;
import com.vaonis.vesperacontrol.ui.connections.ConnessioniFragment;
import com.vaonis.vesperacontrol.ui.foto.FotoFragment;
import com.vaonis.vesperacontrol.ui.notifiche.NotificheFragment;
import com.vaonis.vesperacontrol.ui.schermo.SchermoFragment;
import com.vaonis.vesperacontrol.ui.settings.ImpostazioniFragment;
import com.vaonis.vesperacontrol.ui.sistema.SistemaFragment;
import com.vaonis.vesperacontrol.ui.telescopio.TelescopioFragment;
import com.vaonis.vesperacontrol.update.AppUpdates;

public class MainActivity extends AppCompatActivity {

    /** Stesso ordine della app Windows: Connessioni all'inizio, Impostazioni in fondo. */
    private static final int[] TAB_TITLES = {
            R.string.tab_connessioni,
            R.string.tab_foto,
            R.string.tab_telescopio,
            R.string.tab_sistema,
            R.string.tab_notifiche,
            R.string.tab_anteprima,
            R.string.tab_schermo,
            R.string.tab_impostazioni
    };
    private static final int TAB_CONNESSIONI = 0;
    private static final int TAB_IMPOSTAZIONI = 7;

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private MaterialToolbar toolbar;
    private boolean autoConnectStarted;
    /** Aggiorna il sottotitolo quando la connessione ADB si apre/chiude (anche da altri tab). */
    private final AdbBridge.StateListener adbListener = (linked, target, message) -> runOnUiThread(() -> {
        if (isFinishing()) return;
        String ip = DevicePrefs.getIp(this);
        if (linked) {
            setConnectionSubtitle(getString(R.string.conn_status_ok, ip));
        } else if (!TextUtils.isEmpty(ip)) {
            setConnectionSubtitle(getString(R.string.conn_status_unreachable, ip));
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setConnectionSubtitle(null);
        toolbar.setSubtitleTextColor(ContextCompat.getColor(this, R.color.vespera_muted));

        viewPager = findViewById(R.id.viewPager);
        tabLayout = findViewById(R.id.tabLayout);

        viewPager.setAdapter(new TabsAdapter(this));
        viewPager.setOffscreenPageLimit(2);

        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(TAB_TITLES[position])
        ).attach();

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                notifyTabSelected(position);
            }
        });

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                styleTabs();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                styleTabs();
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                if (tab != null) {
                    notifyTabSelected(tab.getPosition());
                }
            }
        });
        tabLayout.post(this::styleTabs);

        AdbBridgeHolder.get(this).addStateListener(adbListener);
        autoConnectOnLaunch();
        // Come Windows: controllo automatico subito dopo l'avvio UI (silent).
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing()) return;
            AppUpdates.check(this, true, null);
        }, 2500);
    }

    private void notifyTabSelected(int position) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + position);
        if (fragment instanceof TabRefreshable) {
            ((TabRefreshable) fragment).onTabSelected();
        }
    }

    /** Se l'IP è già salvato, connette ADB all'apertura; altrimenti non fa nulla. */
    private void autoConnectOnLaunch() {
        if (autoConnectStarted) return;
        String ip = DevicePrefs.getIp(this);
        if (TextUtils.isEmpty(ip)) {
            // Primo avvio senza IP: apri Impostazioni, dove si inseriscono IP e porte.
            viewPager.setCurrentItem(TAB_IMPOSTAZIONI, false);
            return;
        }
        autoConnectStarted = true;

        AdbBridge adb = AdbBridgeHolder.get(this);
        if (adb.isLinked()) {
            setConnectionSubtitle(getString(R.string.conn_status_ok, ip));
            return;
        }

        int port = DevicePrefs.getAdbPort(this);
        setConnectionSubtitle(getString(R.string.conn_status_connecting, ip));
        adb.connect(ip, port, (ok, msg) -> runOnUiThread(() -> {
            if (isFinishing()) return;
            if (ok) {
                Toast.makeText(this, getString(R.string.conn_auto_ok, ip), Toast.LENGTH_SHORT).show();
            } else {
                String detail = TextUtils.isEmpty(msg)
                        ? getString(R.string.conn_auto_fail, ip)
                        : msg;
                View root = findViewById(R.id.root);
                if (root != null) {
                    Snackbar.make(root, detail, Snackbar.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
                }
            }
        }));
    }

    private void setConnectionSubtitle(String status) {
        if (toolbar == null) return;
        String base = "APK " + BuildConfig.VERSION_NAME + " · rev " + BuildConfig.VERSION_CODE;
        toolbar.setSubtitle(TextUtils.isEmpty(status) ? base : base + " · " + status);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Richeck silenzioso se sono passate ≥24 ore (o mai controllato).
        // Ripresa installazione dopo il permesso "Installa app sconosciute".
        AppUpdates.resumePendingInstall(this);
        com.vaonis.vesperacontrol.update.AppUpdates.checkWhenVisible(this);
        // Tornando in primo piano (es. dopo condivisione/download) riapre ADB se è caduto.
        if (autoConnectStarted && !TextUtils.isEmpty(DevicePrefs.getIp(this))) {
            AdbBridge adb = AdbBridgeHolder.get(this);
            if (!adb.isLinked()) adb.ensureConnectedAsync(null);
        }
    }

    @Override
    protected void onDestroy() {
        AdbBridgeHolder.get(this).removeStateListener(adbListener);
        super.onDestroy();
    }

    /** Evita che titolo/tab finiscano sotto status bar / gesture bar (Android 15+). */
    private void applySystemBarInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
    }

    /** Tab segmentate come UiStyle.applyTab di VesperaHelper (verde selezionata). */
    private void styleTabs() {
        if (tabLayout == null) return;
        float density = getResources().getDisplayMetrics().density;
        int selected = ContextCompat.getColor(this, R.color.vespera_green);
        int idle = ContextCompat.getColor(this, R.color.vespera_tab_idle);
        int idleText = ContextCompat.getColor(this, R.color.vespera_tab_idle_text);
        int white = ContextCompat.getColor(this, R.color.white);
        ViewGroup strip = (ViewGroup) tabLayout.getChildAt(0);
        if (!(strip instanceof LinearLayout)) return;
        for (int i = 0; i < strip.getChildCount(); i++) {
            View tabView = strip.getChildAt(i);
            boolean isSelected = tabLayout.getSelectedTabPosition() == i;
            GradientDrawable face = new GradientDrawable();
            face.setColor(isSelected ? selected : idle);
            face.setCornerRadius(6f * density);
            tabView.setBackground(face);
            if (tabView instanceof ViewGroup) {
                paintTabText((ViewGroup) tabView, isSelected ? white : idleText);
            }
        }
    }

    private void paintTabText(ViewGroup root, int color) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(color);
            } else if (child instanceof ViewGroup) {
                paintTabText((ViewGroup) child, color);
            }
        }
    }

    private static final class TabsAdapter extends FragmentStateAdapter {

        TabsAdapter(@NonNull AppCompatActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case TAB_CONNESSIONI:
                    return new ConnessioniFragment();
                case 1:
                    return new FotoFragment();
                case 2:
                    return new TelescopioFragment();
                case 3:
                    return new SistemaFragment();
                case 4:
                    return new NotificheFragment();
                case 5:
                    return new AnteprimaFragment();
                case 6:
                    return new SchermoFragment();
                case TAB_IMPOSTAZIONI:
                default:
                    return new ImpostazioniFragment();
            }
        }

        @Override
        public int getItemCount() {
            return TAB_TITLES.length;
        }
    }
}
