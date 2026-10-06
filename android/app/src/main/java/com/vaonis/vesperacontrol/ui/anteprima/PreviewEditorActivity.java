package com.vaonis.vesperacontrol.ui.anteprima;

import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.vaonis.vesperacontrol.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Editor anteprima: autostretch, livelli, crop, salva, condividi. */
public class PreviewEditorActivity extends AppCompatActivity {

    private static final int TOOL_LEVELS = 1;
    private static final int TOOL_GEOMETRY = 2;

    private PreviewCanvasView canvas;
    private TextView textHint;
    private TextView textBlack;
    private TextView textMid;
    private TextView textWhite;
    private TextView textSat;
    private SeekBar seekBlack;
    private SeekBar seekMid;
    private SeekBar seekWhite;
    private SeekBar seekSat;
    private Button btnApplyCrop;
    private Button btnCrop;
    private EditText editCropW;
    private EditText editCropH;
    private boolean suppressCropSize;
    private View editorTopBar;
    private View editorSheet;
    private View editorSheetChrome;
    private View panelLevels;
    private View panelGeometry;
    private Button btnToolLevels;
    private Button btnToolGeometry;
    private BottomSheetBehavior<View> sheetBehavior;
    /** Bottom system-bar (navigation / IME) inset: the sheet extends behind it. */
    private int openTool;

    private Bitmap source;
    private Bitmap viewBitmap;
    private String suggestedName = "vespera-preview-edited.jpg";
    private final ImageAdjust.Params params = new ImageAdjust.Params();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger applyGen = new AtomicInteger();
    private Runnable pendingApply;
    private boolean suppressSeek;
    private File lastExport;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_preview_editor);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            // BottomSheetBehavior positions the sheet against its parent's full height and
            // ignores parent padding, so the insets go on the outer frame: the coordinator
            // inside it already ends above the nav bar.
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            v.post(() -> syncSheetChrome());
            return WindowInsetsCompat.CONSUMED;
        });

        Bitmap src = PreviewEditorSession.getSource();
        if (src == null || src.isRecycled()) {
            Toast.makeText(this, R.string.editor_no_image, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        source = src.copy(Bitmap.Config.ARGB_8888, true);
        suggestedName = PreviewEditorSession.getSuggestedName();

        TextView title = findViewById(R.id.textEditorTitle);
        title.setText(PreviewEditorSession.getTitle());
        textHint = findViewById(R.id.textEditorHint);
        canvas = findViewById(R.id.previewCanvas);
        textBlack = findViewById(R.id.textBlack);
        textMid = findViewById(R.id.textMid);
        textWhite = findViewById(R.id.textWhite);
        textSat = findViewById(R.id.textSat);
        seekBlack = findViewById(R.id.seekBlack);
        seekMid = findViewById(R.id.seekMid);
        seekWhite = findViewById(R.id.seekWhite);
        seekSat = findViewById(R.id.seekSat);
        btnApplyCrop = findViewById(R.id.btnApplyCrop);
        btnCrop = findViewById(R.id.btnCrop);
        editCropW = findViewById(R.id.editCropW);
        editCropH = findViewById(R.id.editCropH);

        bindToolSheet();

        findViewById(R.id.btnEditorClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnEditorSave).setOnClickListener(v -> saveImage());
        findViewById(R.id.btnEditorShare).setOnClickListener(v -> shareImage());
        findViewById(R.id.btnAutostretch).setOnClickListener(v -> autostretch());
        findViewById(R.id.btnResetLevels).setOnClickListener(v -> resetLevels());
        findViewById(R.id.btnRotateLeft).setOnClickListener(v -> rotate(90));
        findViewById(R.id.btnRotateRight).setOnClickListener(v -> rotate(-90));
        findViewById(R.id.btnFit).setOnClickListener(v -> canvas.fit());
        findViewById(R.id.btnClearCrop).setOnClickListener(v -> {
            canvas.clearCropSelection();
            setCropMode(false);
            syncCropSizeFields(null);
            textHint.setText(R.string.editor_hint);
        });
        btnCrop.setOnClickListener(v -> setCropMode(!canvas.isCropMode()));
        btnApplyCrop.setOnClickListener(v -> applyCrop());
        findViewById(R.id.btnCropSizeApply).setOnClickListener(v -> applyCropSizeFromFields());

        canvas.setCropListener(rect -> {
            boolean ok = rect != null && rect.width() >= 2f && rect.height() >= 2f;
            btnApplyCrop.setEnabled(ok);
            syncCropSizeFields(rect);
            if (ok) {
                textHint.setText(getString(R.string.editor_crop_ready,
                        Math.round(rect.width()), Math.round(rect.height())));
            } else if (canvas.isCropMode()) {
                textHint.setText(R.string.editor_crop_hint);
            }
        });

        bindSeek(seekBlack, textBlack, 0f, 0.95f, v -> params.black = v);
        // mid: progress 0..280 → 0.20..3.00
        seekMid.setProgress(Math.round((1f - 0.2f) * 100f));
        bindSeekMapped(seekMid, textMid, progress -> 0.2f + progress / 100f, v -> params.mid = v);
        // white: progress 0..95 → 0.05..1.00
        bindSeekMapped(seekWhite, textWhite, progress -> 0.05f + progress / 100f, v -> params.white = v);
        bindSeekMapped(seekSat, textSat, progress -> progress / 100f, v -> params.saturation = v);

        syncSeekFromParams();
        scheduleApply(true);
    }

    private void bindToolSheet() {
        editorTopBar = findViewById(R.id.editorTopBar);
        editorSheet = findViewById(R.id.editorSheet);
        editorSheetChrome = findViewById(R.id.editorSheetChrome);
        panelLevels = findViewById(R.id.panelLevels);
        panelGeometry = findViewById(R.id.panelGeometry);
        btnToolLevels = findViewById(R.id.btnToolLevels);
        btnToolGeometry = findViewById(R.id.btnToolGeometry);
        sheetBehavior = BottomSheetBehavior.from(editorSheet);
        sheetBehavior.setHideable(false);
        sheetBehavior.setFitToContents(true);
        sheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED && openTool == 0) {
                    sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                    return;
                }
                if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    openTool = 0;
                    panelLevels.setVisibility(View.GONE);
                    panelGeometry.setVisibility(View.GONE);
                    tintToolButtons();
                }
            }

            @Override
            public void onSlide(View bottomSheet, float slideOffset) {
            }
        });
        btnToolLevels.setOnClickListener(v -> toggleTool(TOOL_LEVELS));
        btnToolGeometry.setOnClickListener(v -> toggleTool(TOOL_GEOMETRY));
        editorSheetChrome.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) syncSheetChrome();
        });
        editorTopBar.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) syncCanvasInsets();
        });
        editorSheet.post(this::syncSheetChrome);
    }

    private void syncSheetChrome() {
        if (editorSheetChrome == null || sheetBehavior == null || editorSheet == null) return;
        int chromeH = editorSheetChrome.getHeight();
        int peek = chromeH;
        if (peek > 0 && sheetBehavior.getPeekHeight() != peek) {
            sheetBehavior.setPeekHeight(peek, false);
        }
        int parentH = findViewById(R.id.editorCoordinator).getHeight();
        if (parentH > 0 && peek > 0) {
            int max = Math.round(parentH * 0.46f);
            if (max < peek + dp(120)) max = Math.min(parentH, peek + dp(220));
            int content = measureToolContent();
            int desired = content > 0 ? peek + content : max;
            int height = openTool == 0 ? max : Math.min(max, Math.max(desired, peek));
            ViewGroup.LayoutParams lp = editorSheet.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                editorSheet.setLayoutParams(lp);
            }
        }
        syncCanvasInsets();
    }

    private int measureToolContent() {
        if (openTool == 0 || editorSheet.getWidth() <= 0) return 0;
        View scroll = editorSheet.findViewById(R.id.editorToolsScroll);
        if (!(scroll instanceof ViewGroup) || ((ViewGroup) scroll).getChildCount() == 0) return 0;
        View child = ((ViewGroup) scroll).getChildAt(0);
        child.measure(
                View.MeasureSpec.makeMeasureSpec(editorSheet.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return child.getMeasuredHeight();
    }

    private void syncCanvasInsets() {
        if (canvas == null || editorTopBar == null) return;
        int top = editorTopBar.getHeight();
        int bottom = editorSheetChrome != null ? editorSheetChrome.getHeight() : 0;
        canvas.setVisibleInsets(top, bottom);
    }

    private void toggleTool(int tool) {
        int state = sheetBehavior.getState();
        boolean showing = openTool == tool
                && state != BottomSheetBehavior.STATE_COLLAPSED
                && state != BottomSheetBehavior.STATE_HIDDEN;
        if (showing) {
            sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            return;
        }
        openTool(tool);
    }

    private void openTool(int tool) {
        openTool = tool;
        panelLevels.setVisibility(tool == TOOL_LEVELS ? View.VISIBLE : View.GONE);
        panelGeometry.setVisibility(tool == TOOL_GEOMETRY ? View.VISIBLE : View.GONE);
        tintToolButtons();
        syncSheetChrome();
        editorSheet.post(() -> {
            if (openTool == tool) {
                sheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
    }

    private void tintToolButtons() {
        int green = ContextCompat.getColor(this, R.color.vespera_green);
        int slate = ContextCompat.getColor(this, R.color.vespera_slate);
        btnToolLevels.setBackgroundTintList(ColorStateList.valueOf(
                openTool == TOOL_LEVELS ? green : slate));
        btnToolGeometry.setBackgroundTintList(ColorStateList.valueOf(
                openTool == TOOL_GEOMETRY ? green : slate));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface FloatConsumer {
        void accept(float v);
    }

    private interface ProgressMapper {
        float map(int progress);
    }

    private void bindSeek(SeekBar bar, TextView label, float min, float max, FloatConsumer setter) {
        bindSeekMapped(bar, label, progress -> min + (max - min) * (progress / (float) bar.getMax()), setter);
    }

    private void bindSeekMapped(SeekBar bar, TextView label, ProgressMapper mapper, FloatConsumer setter) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float v = mapper.map(progress);
                label.setText(String.format(Locale.US, "%.2f", v));
                if (!fromUser || suppressSeek) return;
                setter.accept(v);
                ensureWhiteAboveBlack();
                scheduleApply(false);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        float v = mapper.map(bar.getProgress());
        label.setText(String.format(Locale.US, "%.2f", v));
    }

    private void syncSeekFromParams() {
        suppressSeek = true;
        seekBlack.setProgress(Math.round(params.black / 0.95f * seekBlack.getMax()));
        seekMid.setProgress(Math.round((params.mid - 0.2f) * 100f));
        seekWhite.setProgress(Math.round((params.white - 0.05f) * 100f));
        seekSat.setProgress(Math.round(params.saturation * 100f));
        textBlack.setText(String.format(Locale.US, "%.2f", params.black));
        textMid.setText(String.format(Locale.US, "%.2f", params.mid));
        textWhite.setText(String.format(Locale.US, "%.2f", params.white));
        textSat.setText(String.format(Locale.US, "%.2f", params.saturation));
        suppressSeek = false;
    }

    private void ensureWhiteAboveBlack() {
        if (params.white <= params.black + 0.01f) {
            params.white = Math.min(1f, params.black + 0.01f);
            suppressSeek = true;
            seekWhite.setProgress(Math.round((params.white - 0.05f) * 100f));
            textWhite.setText(String.format(Locale.US, "%.2f", params.white));
            suppressSeek = false;
        }
    }

    private void scheduleApply(boolean fit) {
        if (pendingApply != null) ui.removeCallbacks(pendingApply);
        pendingApply = () -> recompute(fit);
        ui.postDelayed(pendingApply, 60);
    }

    private void recompute(boolean fit) {
        pendingApply = null;
        if (source == null || source.isRecycled()) return;
        final int gen = applyGen.incrementAndGet();
        final ImageAdjust.Params snap = params.copy();
        final Bitmap src = source;
        executor.execute(() -> {
            Bitmap out;
            try {
                out = ImageAdjust.apply(src, snap);
            } catch (Exception e) {
                ui.post(() -> textHint.setText(getString(R.string.editor_error, e.getMessage())));
                return;
            }
            final Bitmap result = out;
            ui.post(() -> {
                if (gen != applyGen.get() || isFinishing()) {
                    if (result != null && result != src) result.recycle();
                    return;
                }
                if (viewBitmap != null && viewBitmap != source && !viewBitmap.isRecycled()) {
                    viewBitmap.recycle();
                }
                viewBitmap = result;
                canvas.setImage(viewBitmap);
                if (fit) canvas.fit();
                canvas.setHelpText(getString(R.string.editor_canvas_help));
            });
        });
    }

    private void autostretch() {
        if (source == null) return;
        executor.execute(() -> {
            ImageAdjust.Params est = ImageAdjust.estimateAutostretch(source);
            ui.post(() -> {
                params.black = est.black;
                params.white = est.white;
                params.mid = 1f;
                params.saturation = params.saturation;
                syncSeekFromParams();
                textHint.setText(getString(R.string.editor_autostretch_done, params.black, params.white));
                scheduleApply(false);
            });
        });
    }

    private void resetLevels() {
        params.black = 0f;
        params.white = 1f;
        params.mid = 1f;
        params.saturation = 1f;
        syncSeekFromParams();
        textHint.setText(R.string.editor_reset_done);
        scheduleApply(false);
    }

    private void rotate(int degrees) {
        if (source == null) return;
        applyGen.incrementAndGet();
        Matrix m = new Matrix();
        m.postRotate(degrees);
        source = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
        canvas.clearCropSelection();
        setCropMode(false);
        textHint.setText(degrees > 0 ? R.string.editor_rotated_left : R.string.editor_rotated_right);
        scheduleApply(true);
    }

    private void setCropMode(boolean enabled) {
        canvas.setCropMode(enabled);
        btnCrop.setAlpha(enabled ? 1f : 0.85f);
        if (enabled) {
            textHint.setText(R.string.editor_crop_hint);
            canvas.setHelpText(getString(R.string.editor_canvas_crop_help));
            openTool(TOOL_GEOMETRY);
        } else {
            canvas.setHelpText(getString(R.string.editor_canvas_help));
            if (!canvas.hasValidCrop()) {
                textHint.setText(R.string.editor_hint);
            }
        }
    }

    private void syncCropSizeFields(@Nullable RectF rect) {
        if (editCropW == null || editCropH == null) return;
        suppressCropSize = true;
        if (rect != null && rect.width() >= 2f && rect.height() >= 2f) {
            editCropW.setText(String.valueOf(Math.round(rect.width())));
            editCropH.setText(String.valueOf(Math.round(rect.height())));
        } else {
            editCropW.setText("");
            editCropH.setText("");
        }
        suppressCropSize = false;
    }

    private void applyCropSizeFromFields() {
        if (source == null || suppressCropSize) return;
        int w;
        int h;
        try {
            w = Integer.parseInt(editCropW.getText().toString().trim());
            h = Integer.parseInt(editCropH.getText().toString().trim());
        } catch (NumberFormatException e) {
            textHint.setText(R.string.editor_crop_size_invalid);
            return;
        }
        if (w < 2 || h < 2) {
            textHint.setText(R.string.editor_crop_small);
            return;
        }
        w = Math.min(w, source.getWidth());
        h = Math.min(h, source.getHeight());
        setCropMode(true);
        canvas.setCropSize(w, h);
        if (canvas.hasValidCrop()) {
            RectF rect = canvas.getCropRect();
            textHint.setText(getString(R.string.editor_crop_ready,
                    Math.round(rect.width()), Math.round(rect.height())));
        }
    }

    private void applyCrop() {
        RectF rect = canvas.getCropRect();
        if (source == null || rect == null || !canvas.hasValidCrop()) return;
        int x0 = Math.max(0, Math.min(source.getWidth() - 1, Math.round(rect.left)));
        int y0 = Math.max(0, Math.min(source.getHeight() - 1, Math.round(rect.top)));
        int x1 = Math.max(1, Math.min(source.getWidth(), Math.round(rect.right)));
        int y1 = Math.max(1, Math.min(source.getHeight(), Math.round(rect.bottom)));
        int w = x1 - x0;
        int h = y1 - y0;
        if (w < 2 || h < 2) {
            textHint.setText(R.string.editor_crop_small);
            return;
        }
        applyGen.incrementAndGet();
        source = Bitmap.createBitmap(source, x0, y0, w, h);
        canvas.clearCropSelection();
        setCropMode(false);
        textHint.setText(getString(R.string.editor_crop_applied, w, h));
        scheduleApply(true);
    }

    private Bitmap currentImage() {
        if (viewBitmap != null && !viewBitmap.isRecycled()) return viewBitmap;
        return ImageAdjust.apply(source, params);
    }

    private void saveImage() {
        Bitmap image = currentImage();
        if (image == null) return;
        executor.execute(() -> {
            try {
                Uri uri = writeToGallery(image, suggestedName);
                ui.post(() -> {
                    textHint.setText(getString(R.string.editor_saved, suggestedName));
                    Toast.makeText(this, R.string.editor_saved_toast, Toast.LENGTH_SHORT).show();
                });
                if (uri != null) {
                    // also keep a cache copy for share
                    lastExport = writeCacheCopy(image, suggestedName);
                }
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this,
                        e.getMessage() == null ? "Salvataggio fallito" : e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void shareImage() {
        Bitmap image = currentImage();
        if (image == null) return;
        executor.execute(() -> {
            try {
                File file = lastExport != null && lastExport.isFile()
                        ? lastExport
                        : writeCacheCopy(image, suggestedName);
                lastExport = file;
                Uri uri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/jpeg");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ui.post(() -> startActivity(Intent.createChooser(send, getString(R.string.editor_share))));
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this,
                        e.getMessage() == null ? "Condivisione fallita" : e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private Uri writeToGallery(Bitmap image, String name) throws Exception {
        String display = name == null || name.isEmpty() ? "vespera-preview-edited.jpg" : name;
        if (!display.toLowerCase(Locale.US).endsWith(".jpg")
                && !display.toLowerCase(Locale.US).endsWith(".jpeg")
                && !display.toLowerCase(Locale.US).endsWith(".png")) {
            display = display + ".jpg";
        }
        boolean png = display.toLowerCase(Locale.US).endsWith(".png");
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, display);
        values.put(MediaStore.Images.Media.MIME_TYPE, png ? "image/png" : "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/VesperaControl");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("MediaStore insert fallito");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("OutputStream null");
            if (!image.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 95, out)) {
                throw new IllegalStateException("Compress fallito");
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
        }
        return uri;
    }

    private File writeCacheCopy(Bitmap image, String name) throws Exception {
        File dir = new File(getCacheDir(), "previews");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cache preview non creabile");
        }
        String display = name == null || name.isEmpty() ? "vespera-preview-edited.jpg" : name;
        if (!display.toLowerCase(Locale.US).endsWith(".jpg")
                && !display.toLowerCase(Locale.US).endsWith(".jpeg")
                && !display.toLowerCase(Locale.US).endsWith(".png")) {
            display = display + ".jpg";
        }
        File file = new File(dir, display);
        boolean png = display.toLowerCase(Locale.US).endsWith(".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!image.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 95, out)) {
                throw new IllegalStateException("Compress fallito");
            }
        }
        return file;
    }

    @Override
    protected void onDestroy() {
        if (pendingApply != null) ui.removeCallbacks(pendingApply);
        executor.shutdownNow();
        if (viewBitmap != null && viewBitmap != source && !viewBitmap.isRecycled()) {
            viewBitmap.recycle();
        }
        Bitmap session = PreviewEditorSession.getSource();
        if (source != null && source != session && !source.isRecycled()) {
            source.recycle();
        }
        PreviewEditorSession.clear();
        if (session != null && !session.isRecycled()) {
            session.recycle();
        }
        super.onDestroy();
    }
}
