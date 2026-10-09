package com.vaonis.vesperacontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vaonis.vesperacontrol.catalog.HttpsFetch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Footer meteo dell'app (stessi dati di NightForecastFooter di Vespera Helper).
 * Footer: ‹ giorno › fisso, un riquadro con la prima previsione disponibile di quella notte (▲)
 * e il nome del paese. Solo il riquadro è cliccabile: apre sopra il footer tutte le fasce di 3 h
 * della notte scelta, a griglia così da stare nello schermo senza scorrere (icone più piccole se
 * serve). Un altro tap (o Indietro) chiude.
 */
final class NightForecastFooter {
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final int NIGHT_FORECAST_DAYS = 7;
    /** Default di PhotoSyncStore dell'Helper (dayEndHour / dayStartHour). */
    private static final int DEFAULT_NIGHT_START = 19;
    private static final int DEFAULT_NIGHT_END = 10;
    private static final int TEXT = 0xFF37474F;
    private static final int RAIN = 0xFF1565C0;
    private static final int NAV_ON = 0xFF1565C0;
    private static final int NAV_OFF = 0xFFB0BEC5;

    private static final class Slot {
        final long epochMs;
        final int weatherCode;
        final double precipitationMm;
        final double temperatureC;

        Slot(long epochMs, int weatherCode, double precipitationMm, double temperatureC) {
            this.epochMs = epochMs;
            this.weatherCode = weatherCode;
            this.precipitationMm = precipitationMm;
            this.temperatureC = temperatureC;
        }
    }

    private static final class Night {
        final long startMs;
        final List<Slot> slots = new ArrayList<>();

        Night(long startMs) {
            this.startMs = startMs;
        }
    }

    private final Context app;
    private final float density;
    private final LinearLayout root;
    private final Button prevDay;
    private final Button nextDay;
    private final TextView dayLabel;
    private final ImageView boxIcon;
    private final TextView boxText;
    private final TextView boxArrow;
    private final TextView placeLabelView;
    private final LinearLayout panel;
    /** Sfondo trasparente sotto il pannello: un tap fuori dal pannello lo chiude. */
    private final View scrim;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable refreshTick = this::refresh;
    private final SharedPreferences.OnSharedPreferenceChangeListener siteListener =
            (prefs, key) -> mainHandler.post(this::onSiteMaybeChanged);

    private List<Night> nights = new ArrayList<>();
    private int nightIndex;
    private TimeZone zone = TimeZone.getDefault();
    private String loadedKey = "";
    private boolean started;
    private boolean open;
    private int laidOutWidth = -1;

    NightForecastFooter(Context context) {
        this.app = context.getApplicationContext();
        this.density = context.getResources().getDisplayMetrics().density;

        // --- footer: ‹ giorno ›  [riquadro]  ...  paese
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(0xFFE8EEF4);
        root.setPadding(dp(2), dp(4), dp(12), dp(4));
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        prevDay = navButton(context, "‹");
        prevDay.setOnClickListener(v -> shiftDay(-1));
        root.addView(prevDay);
        dayLabel = new TextView(context);
        dayLabel.setTextSize(15);
        dayLabel.setTypeface(dayLabel.getTypeface(), Typeface.BOLD);
        dayLabel.setTextColor(TEXT);
        dayLabel.setMaxLines(1);
        root.addView(dayLabel);
        nextDay = navButton(context, "›");
        nextDay.setOnClickListener(v -> shiftDay(1));
        root.addView(nextDay);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(card());
        box.setPadding(dp(8), dp(4), dp(10), dp(4));
        box.setOnClickListener(v -> toggle());
        boxIcon = new ImageView(context);
        boxIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        box.addView(boxIcon);
        boxText = new TextView(context);
        boxText.setTextSize(15);
        boxText.setTypeface(boxText.getTypeface(), Typeface.BOLD);
        boxText.setTextColor(TEXT);
        boxText.setMaxLines(1);
        boxText.setPadding(dp(6), 0, dp(6), 0);
        box.addView(boxText);
        boxArrow = new TextView(context);
        boxArrow.setText("▲");
        boxArrow.setTextSize(13);
        boxArrow.setTextColor(NAV_ON);
        box.addView(boxArrow);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.setMarginStart(dp(4));
        root.addView(box, boxLp);

        // Il nome del paese prende lo spazio che resta (troncato se lo schermo è stretto).
        placeLabelView = new TextView(context);
        placeLabelView.setTextSize(15);
        placeLabelView.setTypeface(placeLabelView.getTypeface(), Typeface.BOLD);
        placeLabelView.setTextColor(TEXT);
        placeLabelView.setMaxLines(1);
        placeLabelView.setEllipsize(TextUtils.TruncateAt.END);
        placeLabelView.setGravity(Gravity.END);
        placeLabelView.setPadding(dp(8), 0, 0, 0);
        root.addView(placeLabelView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // --- pannello sovrapposto (MainActivity lo mette sopra il contenuto, in basso)
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(card());
        panel.setElevation(dp(6));
        panel.setPadding(dp(8), dp(6), dp(8), dp(6));
        panel.setVisibility(View.GONE);
        panel.setClickable(true); // i tap non passano al contenuto sotto
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        panelLp.setMargins(dp(8), 0, dp(8), dp(6));
        panel.setLayoutParams(panelLp);
        scrim = new View(context);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> close());

        // Swipe a sinistra/destra su footer o pannello: giorno dopo / prima.
        // Un tap sul footer (fuori da riquadro e frecce) chiude; un tap dentro il pannello no.
        GestureDetector footerSwipe = swipeDetector(context, true);
        GestureDetector panelSwipe = swipeDetector(context, false);
        root.setOnTouchListener((v, e) -> {
            footerSwipe.onTouchEvent(e);
            return true;
        });
        panel.setOnTouchListener((v, e) -> {
            panelSwipe.onTouchEvent(e);
            return true;
        });

        // Rotazione / finestra ridimensionata: ricalcola colonne e dimensione icone.
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (open && r - l != laidOutWidth) mainHandler.post(this::fillPanel);
        });
    }

    private GestureDetector swipeDetector(Context context, boolean tapCloses) {
        return new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (tapCloses) close();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
                if (down == null) return false;
                float dx = up.getX() - down.getX();
                float dy = up.getY() - down.getY();
                if (Math.abs(dx) < dp(40) || Math.abs(dx) < 2 * Math.abs(dy)) return false;
                shiftDay(dx < 0 ? 1 : -1); // verso sinistra = giorno dopo
                return true;
            }
        });
    }

    private int dp(float value) {
        return (int) (value * density);
    }

    private GradientDrawable card() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setStroke(Math.max(1, dp(1)), 0xFF90A4AE);
        g.setCornerRadius(dp(6));
        return g;
    }

    private Button navButton(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextSize(26);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackgroundColor(0x00000000);
        b.setTextColor(NAV_ON);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** Sfondo trasparente da mettere nel FrameLayout prima del pannello (tap fuori = chiudi). */
    View scrimView() {
        return scrim;
    }

    /** Il footer. */
    View view() {
        return root;
    }

    /** Il pannello sovrapposto: va in un FrameLayout sopra il contenuto (gravity bottom già impostata). */
    View panelView() {
        return panel;
    }

    void start() {
        if (started) return;
        started = true;
        DevicePrefs.registerListener(app, siteListener);
        ObservatorySite.registerHelperListener(app, siteListener);
        refresh();
    }

    void stop() {
        started = false;
        DevicePrefs.unregisterListener(app, siteListener);
        ObservatorySite.unregisterHelperListener(app, siteListener);
        mainHandler.removeCallbacks(refreshTick);
    }

    private void toggle() {
        if (open) {
            close();
        } else if (!nights.isEmpty()) {
            open = true;
            boxArrow.setText("▼");
            scrim.setVisibility(View.VISIBLE);
            panel.setVisibility(View.VISIBLE);
            fillPanel();
        }
    }

    /** Chiude il pannello; true se era aperto (per il tasto Indietro). */
    boolean close() {
        if (!open) return false;
        open = false;
        scrim.setVisibility(View.GONE);
        panel.setVisibility(View.GONE);
        boxArrow.setText("▲");
        return true;
    }

    /** Riscarica solo se sito / ore della notte sono cambiati (le preferenze cambiano spesso). */
    private void onSiteMaybeChanged() {
        if (started && !siteKey().equals(loadedKey)) refresh();
        else bindPlace();
    }

    void refresh() {
        mainHandler.removeCallbacks(refreshTick);
        if (!started) return;
        mainHandler.postDelayed(refreshTick, REFRESH_MS);
        bindPlace();
        if (!DevicePrefs.hasSite(app)) {
            loadedKey = "";
            nights = new ArrayList<>();
            nightIndex = 0;
            close();
            showDay(app.getString(R.string.forecast_footer_no_site));
            return;
        }
        if (nights.isEmpty()) showDay(app.getString(R.string.forecast_footer_loading));
        final String key = siteKey();
        final double lat = DevicePrefs.getLat(app);
        final double lon = DevicePrefs.getLon(app);
        final int[] hours = ObservatorySite.helperNightHours(app, lat, lon);
        final int startHour = hours == null ? DEFAULT_NIGHT_START : hours[0];
        final int endHour = hours == null ? DEFAULT_NIGHT_END : hours[1];
        final boolean needTown = placeLabel().isEmpty();
        worker.execute(() -> {
            if (needTown) ObservatorySite.resolveTown(app, lat, lon);
            String error = "";
            List<Night> result = null;
            TimeZone tz = TimeZone.getDefault();
            try {
                Object[] out = fetchNights(lat, lon, startHour, endHour);
                tz = (TimeZone) out[0];
                @SuppressWarnings("unchecked") List<Night> list = (List<Night>) out[1];
                result = list;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final List<Night> fetched = result;
            final String err = error;
            final TimeZone fetchedZone = tz;
            mainHandler.post(() -> applyResult(key, fetched, fetchedZone, err));
        });
    }

    private String siteKey() {
        double lat = DevicePrefs.getLat(app);
        double lon = DevicePrefs.getLon(app);
        int[] hours = ObservatorySite.helperNightHours(app, lat, lon);
        return String.format(Locale.US, "%.4f,%.4f,%s", lat, lon,
                hours == null ? "-" : hours[0] + "-" + hours[1]);
    }

    private void applyResult(String key, List<Night> fetched, TimeZone tz, String err) {
        if (!started) return;
        bindPlace();
        if (fetched == null || fetched.isEmpty()) {
            if (!nights.isEmpty() && key.equals(loadedKey)) return; // tieni l'ultimo aggiornamento buono
            nights = new ArrayList<>();
            nightIndex = 0;
            close();
            showDay(friendlyError(err));
            return;
        }
        loadedKey = key;
        zone = tz;
        nights = fetched;
        if (nightIndex >= nights.size()) nightIndex = 0;
        showDay(null);
    }

    private void shiftDay(int delta) {
        int next = nightIndex + delta;
        if (next < 0 || next >= nights.size()) return;
        nightIndex = next;
        showDay(null);
    }

    /** Solo il paese/città: nome dell'Helper («Adelfia, Bari» → «Adelfia») o reverse geocoding. */
    private String placeLabel() {
        if (!DevicePrefs.hasSite(app)) return "";
        double lat = DevicePrefs.getLat(app);
        double lon = DevicePrefs.getLon(app);
        String label = ObservatorySite.helperLabel(app, lat, lon);
        if (label.isEmpty()) label = ObservatorySite.cachedTown(app, lat, lon);
        int comma = label.indexOf(',');
        return (comma >= 0 ? label.substring(0, comma) : label).trim();
    }

    private void bindPlace() {
        placeLabelView.setText(placeLabel());
    }

    /** Giorno scelto + riquadro con la sua prima previsione; con un messaggio, solo il messaggio. */
    private void showDay(String message) {
        Night night = message == null && !nights.isEmpty() ? nights.get(nightIndex) : null;
        prevDay.setEnabled(night != null && nightIndex > 0);
        nextDay.setEnabled(night != null && nightIndex + 1 < nights.size());
        prevDay.setTextColor(prevDay.isEnabled() ? NAV_ON : NAV_OFF);
        nextDay.setTextColor(nextDay.isEnabled() ? NAV_ON : NAV_OFF);
        if (night == null) {
            dayLabel.setText("");
            boxIcon.setVisibility(View.GONE);
            boxArrow.setVisibility(View.GONE);
            boxText.setTextColor(TEXT);
            boxText.setText(message == null ? "" : message);
            return;
        }
        dayLabel.setText(dayCaption(night.startMs));
        Slot slot = firstSlot(night);
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        fmt.setTimeZone(zone);
        String meta = slotMeta(slot);
        boxIcon.setImageResource(iconFor(slot.weatherCode));
        boxIcon.setVisibility(View.VISIBLE);
        boxArrow.setVisibility(View.VISIBLE);
        boxText.setTextColor(slot.precipitationMm >= 0.05d ? RAIN : TEXT);
        boxText.setText(fmt.format(new Date(slot.epochMs)) + (meta.isEmpty() ? "" : " · " + meta));
        if (open) fillPanel();
    }

    /** Prima previsione ancora disponibile (per stanotte salta le fasce già passate). */
    private static Slot firstSlot(Night night) {
        long since = System.currentTimeMillis() - 90 * 60_000L;
        for (Slot s : night.slots) {
            if (s.epochMs >= since) return s;
        }
        return night.slots.get(0);
    }

    /** Tutte le fasce della notte scelta, a griglia: tante colonne quante ne stanno, poi a capo. */
    private void fillPanel() {
        if (!open || nights.isEmpty()) return;
        View parent = (View) panel.getParent();
        int width = (parent != null && parent.getWidth() > 0 ? parent.getWidth() : root.getWidth())
                - dp(16) - panel.getPaddingLeft() - panel.getPaddingRight();
        if (width <= 0) {
            panel.post(this::fillPanel);
            return;
        }
        laidOutWidth = width + panel.getPaddingLeft() + panel.getPaddingRight();
        Night night = nights.get(nightIndex);
        List<View> cells = makeCells(night, 28, 16, 14);
        int cellW = widest(cells) + dp(8);
        if (cellW * cells.size() > width) {
            cells = makeCells(night, 22, 14, 12);
            cellW = widest(cells) + dp(6);
        }
        int cols = Math.max(1, Math.min(cells.size(), width / Math.max(1, cellW)));
        panel.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(root.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(2), 0, dp(2));
                panel.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            row.addView(cells.get(i), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        // Ultima riga incompleta: celle vuote così le colonne restano allineate.
        int rest = cells.size() % cols;
        for (int i = rest == 0 ? cols : rest; i < cols && row != null; i++) {
            row.addView(new View(root.getContext()), new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    private int widest(List<View> cells) {
        int max = 0;
        for (View c : cells) {
            c.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            max = Math.max(max, c.getMeasuredWidth());
        }
        return max;
    }

    private List<View> makeCells(Night night, int iconDp, int timeSp, int metaSp) {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        fmt.setTimeZone(zone);
        List<View> cells = new ArrayList<>();
        for (Slot slot : night.slots) {
            LinearLayout cell = new LinearLayout(root.getContext());
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(root.getContext());
            icon.setImageResource(iconFor(slot.weatherCode));
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
            cell.addView(icon);

            LinearLayout texts = new LinearLayout(root.getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(4), 0, 0, 0);
            TextView time = new TextView(root.getContext());
            time.setText(fmt.format(new Date(slot.epochMs)));
            time.setTextSize(timeSp);
            time.setTypeface(time.getTypeface(), Typeface.BOLD);
            time.setTextColor(0xFF1A237E);
            time.setMaxLines(1);
            texts.addView(time);
            String meta = slotMeta(slot);
            if (!meta.isEmpty()) {
                TextView detail = new TextView(root.getContext());
                detail.setText(meta);
                detail.setTextSize(metaSp);
                detail.setTypeface(detail.getTypeface(), Typeface.BOLD);
                detail.setMaxLines(1);
                detail.setIncludeFontPadding(false);
                detail.setTextColor(slot.precipitationMm >= 0.05d ? RAIN : TEXT);
                texts.addView(detail);
            }
            cell.addView(texts);
            cells.add(cell);
        }
        return cells;
    }

    // ------------------------------------------------------------------ Open-Meteo (come OpenMeteoClient.fetchNightSlots)

    /** {zona del sito, notti}. Orari in UTC; la zona arriva da timezone=auto. */
    private static Object[] fetchNights(double lat, double lon, int nightStartHour, int nightEndHour)
            throws Exception {
        String path = String.format(Locale.US, "/v1/forecast?latitude=%.5f&longitude=%.5f"
                + "&hourly=weather_code,precipitation,precipitation_probability,temperature_2m"
                + "&forecast_days=%d&timeformat=unixtime&timezone=auto", lat, lon, NIGHT_FORECAST_DAYS);
        HttpsFetch.Result http = HttpsFetch.get("api.open-meteo.com", path);
        if (http.code >= 400) throw new Exception("HTTP " + http.code);
        JSONObject root = new JSONObject(http.body);
        TimeZone zone = TimeZone.getTimeZone(root.optString("timezone", TimeZone.getDefault().getID()));
        JSONObject hourly = root.optJSONObject("hourly");
        if (hourly == null) throw new Exception("no_hourly");
        JSONArray times = hourly.optJSONArray("time");
        JSONArray codes = hourly.optJSONArray("weather_code");
        JSONArray precip = hourly.optJSONArray("precipitation");
        JSONArray temps = hourly.optJSONArray("temperature_2m");
        if (times == null || codes == null || times.length() == 0) throw new Exception("no_series");

        List<Night> nights = new ArrayList<>();
        Calendar nightCal = Calendar.getInstance(zone);
        nightCal.setTimeInMillis(nightWindowStartMs(zone, nightStartHour, nightEndHour));
        for (int n = 0; n < NIGHT_FORECAST_DAYS - 1; n++) {
            if (n > 0) nightCal.add(Calendar.DAY_OF_YEAR, 1);
            long startMs = nightCal.getTimeInMillis();
            long endMs = nightEndMs(zone, startMs, nightEndHour);
            Night night = new Night(startMs);
            for (int i = 0; i < times.length() && i < codes.length(); i++) {
                long epochMs = times.optLong(i) * 1000L;
                if (epochMs < startMs || epochMs > endMs) continue;
                if (!isSlot(zone, epochMs, endMs, nightEndHour)) continue;
                double mm = 0d;
                for (int h = i; h < times.length(); h++) {
                    long tMs = times.optLong(h) * 1000L;
                    if (tMs > endMs || (h > i && isSlot(zone, tMs, endMs, nightEndHour))) break;
                    if (precip != null && h < precip.length() && !precip.isNull(h)) mm += precip.optDouble(h, 0d);
                }
                double temp = temps == null || i >= temps.length() || temps.isNull(i)
                        ? Double.NaN : temps.optDouble(i, Double.NaN);
                night.slots.add(new Slot(epochMs, codes.optInt(i, 0), mm, temp));
            }
            if (!night.slots.isEmpty()) nights.add(night);
        }
        if (nights.isEmpty()) throw new Exception("no_series");
        return new Object[]{zone, nights};
    }

    private static boolean isSlot(TimeZone zone, long epochMs, long nightEndMs, int nightEndHour) {
        Calendar cal = Calendar.getInstance(zone);
        cal.setTimeInMillis(epochMs);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour % 3 == 0 || (hour == nightEndHour && epochMs == nightEndMs);
    }

    /** La notte in corso, oppure la prossima dopo l'alba. */
    private static long nightWindowStartMs(TimeZone zone, int nightStartHour, int nightEndHour) {
        long nowMs = System.currentTimeMillis();
        Calendar start = Calendar.getInstance(zone);
        start.setTimeInMillis(nowMs);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        start.set(Calendar.HOUR_OF_DAY, nightStartHour);
        Calendar end = (Calendar) start.clone();
        end.set(Calendar.HOUR_OF_DAY, nightEndHour);
        if (end.getTimeInMillis() <= start.getTimeInMillis()) end.add(Calendar.DAY_OF_YEAR, 1);
        if (nowMs < start.getTimeInMillis()) {
            Calendar prevStart = (Calendar) start.clone();
            prevStart.add(Calendar.DAY_OF_YEAR, -1);
            Calendar prevEnd = (Calendar) end.clone();
            prevEnd.add(Calendar.DAY_OF_YEAR, -1);
            if (nowMs >= prevStart.getTimeInMillis() && nowMs < prevEnd.getTimeInMillis()) {
                return prevStart.getTimeInMillis();
            }
            return start.getTimeInMillis();
        }
        if (nowMs < end.getTimeInMillis()) return start.getTimeInMillis();
        start.add(Calendar.DAY_OF_YEAR, 1);
        return start.getTimeInMillis();
    }

    private static long nightEndMs(TimeZone zone, long nightStartMs, int nightEndHour) {
        Calendar cal = Calendar.getInstance(zone);
        cal.setTimeInMillis(nightStartMs);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.HOUR_OF_DAY, nightEndHour);
        if (cal.getTimeInMillis() <= nightStartMs) cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    // ------------------------------------------------------------------ testi e icone

    /** «9 ott · Oggi» per la sera in cui inizia la notte. */
    private String dayCaption(long nightStartMs) {
        Calendar nightDay = Calendar.getInstance(zone);
        nightDay.setTimeInMillis(nightStartMs);
        clearTime(nightDay);
        Calendar today = Calendar.getInstance(zone);
        clearTime(today);
        int days = (int) Math.round((nightDay.getTimeInMillis() - today.getTimeInMillis()) / 86_400_000d);
        if (days < 0) days = 0;
        String relative;
        if (days == 0) relative = app.getString(R.string.forecast_day_today);
        else if (days == 1) relative = app.getString(R.string.forecast_day_tomorrow);
        else if (days == 2) relative = app.getString(R.string.forecast_day_after_tomorrow);
        else relative = app.getString(R.string.forecast_day_in_days, days);
        SimpleDateFormat dateFmt = new SimpleDateFormat("d MMM", Locale.getDefault());
        dateFmt.setTimeZone(zone);
        return dateFmt.format(new Date(nightStartMs)) + " · " + relative;
    }

    private static void clearTime(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    /** «12° · 0,4 mm»: i mm solo se è prevista pioggia/neve nelle 3 h. */
    private static String slotMeta(Slot slot) {
        String temp = Double.isNaN(slot.temperatureC) ? "" : Math.round(slot.temperatureC) + "°";
        String mm = "";
        if (slot.precipitationMm >= 0.05d) {
            mm = String.format(Locale.getDefault(), slot.precipitationMm < 9.95d ? "%.1f mm" : "%.0f mm",
                    slot.precipitationMm);
        }
        if (!temp.isEmpty() && !mm.isEmpty()) return temp + " · " + mm;
        return temp.isEmpty() ? mm : temp;
    }

    /** Come WeatherIcons.drawableFor dell'Helper (codici WMO). */
    private static int iconFor(int code) {
        if (code == 0 || code == 1) return R.drawable.ic_wx_clear;
        if (code == 2 || code == 3) return R.drawable.ic_wx_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95 && code <= 99) return R.drawable.ic_wx_storm;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_cloud;
    }

    private String friendlyError(String err) {
        String lower = err == null ? "" : err.toLowerCase(Locale.US);
        if (lower.isEmpty()) return app.getString(R.string.forecast_footer_empty);
        if (lower.contains("unable to resolve host") || lower.contains("unreachable")
                || lower.contains("no route to host")) {
            return app.getString(R.string.forecast_footer_no_route);
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return app.getString(R.string.forecast_footer_timeout);
        }
        return app.getString(R.string.forecast_footer_error);
    }
}
