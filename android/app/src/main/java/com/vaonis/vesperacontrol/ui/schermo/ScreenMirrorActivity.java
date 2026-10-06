package com.vaonis.vesperacontrol.ui.schermo;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.vaonis.vesperacontrol.R;

/** Fallback se Scrcpy-for-Android non è installato. */
public class ScreenMirrorActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_screen_mirror);
        View root = findViewById(R.id.root);
        final int basePad = Math.round(24f * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    bars.left + basePad,
                    bars.top + basePad,
                    bars.right + basePad,
                    bars.bottom + basePad
            );
            return windowInsets;
        });

        String ip = getIntent().getStringExtra(SchermoFragment.EXTRA_IP);
        String port = getIntent().getStringExtra(SchermoFragment.EXTRA_PORT);
        String bitrate = getIntent().getStringExtra(SchermoFragment.EXTRA_BITRATE);

        TextView params = findViewById(R.id.textMirrorParams);
        params.setText(getString(R.string.scrcpy_fallback_params,
                nullToDash(ip), nullToDash(port), nullToDash(bitrate)));
    }

    private static String nullToDash(String v) {
        return v == null || v.isEmpty() ? "—" : v;
    }
}
