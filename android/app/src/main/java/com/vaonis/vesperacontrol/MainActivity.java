package com.vaonis.vesperacontrol;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.vaonis.vesperacontrol.ui.anteprima.AnteprimaFragment;
import com.vaonis.vesperacontrol.ui.connections.ConnessioniFragment;
import com.vaonis.vesperacontrol.ui.foto.FotoFragment;
import com.vaonis.vesperacontrol.ui.notifiche.NotificheFragment;
import com.vaonis.vesperacontrol.ui.schermo.SchermoFragment;
import com.vaonis.vesperacontrol.ui.sistema.SistemaFragment;
import com.vaonis.vesperacontrol.ui.telescopio.TelescopioFragment;

public class MainActivity extends AppCompatActivity {

    /** Ordine speculare a VesperaHelper, poi Anteprima / Schermo (solo remote). */
    private static final int[] TAB_TITLES = {
            R.string.tab_connessioni,
            R.string.tab_foto,
            R.string.tab_telescopio,
            R.string.tab_sistema,
            R.string.tab_notifiche,
            R.string.tab_anteprima,
            R.string.tab_schermo
    };

    private TabLayout tabLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ViewPager2 viewPager = findViewById(R.id.viewPager);
        tabLayout = findViewById(R.id.tabLayout);

        viewPager.setAdapter(new TabsAdapter(this));
        viewPager.setOffscreenPageLimit(2);

        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(TAB_TITLES[position])
        ).attach();

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
            }
        });
        tabLayout.post(this::styleTabs);

        AdbBridgeHolder.get(this);
        com.vaonis.vesperacontrol.update.AppUpdates.check(this, true, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.vaonis.vesperacontrol.update.AppUpdates.checkWhenVisible(this);
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
                case 0:
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
                default:
                    return new SchermoFragment();
            }
        }

        @Override
        public int getItemCount() {
            return TAB_TITLES.length;
        }
    }
}
