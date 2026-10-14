package com.vaonis.vesperacontrol.ui.target;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.AdbBridgeHolder;
import com.vaonis.vesperacontrol.DevicePrefs;
import com.vaonis.vesperacontrol.Favorites;
import com.vaonis.vesperacontrol.ObservatorySite;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.RemoteState;
import com.vaonis.vesperacontrol.adb.AdbBridge;
import com.vaonis.vesperacontrol.catalog.NightSky;
import com.vaonis.vesperacontrol.catalog.SkyCatalog;
import com.vaonis.vesperacontrol.catalog.SkySearch;
import com.vaonis.vesperacontrol.ui.TabRefreshable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class TargetFragment extends Fragment implements TabRefreshable {

    private EditText editQuery;
    private ImageView imagePreview;
    private TextView textDetails;
    private TextView textSession;
    private TextView textResult;
    private TextView textVisibility;
    private LinearLayout listHits;
    private Button btnSearch;
    private Button btnStart;
    private Button btnContinue;
    private Button btnFavToggle;
    private TextView textFavDay;
    private TextView textFavStatus;
    private LinearLayout listFavorites;
    private ScrollView scroll;
    /** Sera della notte scelta per i preferiti; null = stanotte. */
    private LocalDate favNight;
    private final AtomicInteger favGen = new AtomicInteger();
    private Button btnPlan;
    private TextView textPlan;
    private View rowPlanActions;
    private Button btnPlanDelete;
    private TextView textPlanHelper;
    private Button btnPlanCancel;
    private TextView textPlansTitle;
    private LinearLayout listPlans;
    private LinearLayout listPlanPeriods;
    private Button btnPlanPropose;
    /** Chiavi (Favorites.keyOf) dei preferiti spuntati per il piano. */
    private final Set<String> selected = new HashSet<>();
    /** Piano mostrato ora (nuovo o salvato). */
    private JSONObject currentPlan;
    private boolean currentPlanSaved;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ITALY);
    private AdbBridge adb;
    private final ExecutorService catalogWorker = Executors.newSingleThreadExecutor();

    private SkyCatalog.Target target;
    private SkyCatalog.Session session;
    private String lastRawState = "";
    private JSONObject lastTelescopeJson;
    private Bitmap previewBitmap;
    private boolean searchBusy;
    private final AtomicInteger visibilityGen = new AtomicInteger();

    /** Rilegge osservazione corrente e piano dell'Helper ogni 5 s mentre il tab è aperto. */
    private static final long AUTO_REFRESH_MS = 5_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean pulling;
    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (!isResumed() || isHidden()) return;
            pullState(false);
            handler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_target, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adb = AdbBridgeHolder.get(requireContext());
        editQuery = view.findViewById(R.id.editObserveQuery);
        imagePreview = view.findViewById(R.id.imageObservePreview);
        textDetails = view.findViewById(R.id.textObserveDetails);
        textSession = view.findViewById(R.id.textObserveSession);
        textResult = view.findViewById(R.id.textObserveResult);
        textVisibility = view.findViewById(R.id.textObserveVisibility);
        listHits = view.findViewById(R.id.listObserveHits);
        btnSearch = view.findViewById(R.id.btnObserveSearch);
        btnStart = view.findViewById(R.id.btnObserveStart);
        btnContinue = view.findViewById(R.id.btnObserveContinue);
        btnFavToggle = view.findViewById(R.id.btnFavToggle);
        textFavDay = view.findViewById(R.id.textFavDay);
        textFavStatus = view.findViewById(R.id.textFavStatus);
        listFavorites = view.findViewById(R.id.listFavorites);
        scroll = view.findViewById(R.id.scrollTarget);
        btnFavToggle.setOnClickListener(v -> toggleFavorite());
        btnPlan = view.findViewById(R.id.btnPlan);
        textPlan = view.findViewById(R.id.textPlan);
        rowPlanActions = view.findViewById(R.id.rowPlanActions);
        btnPlanDelete = view.findViewById(R.id.btnPlanDelete);
        textPlanHelper = view.findViewById(R.id.textPlanHelper);
        btnPlanCancel = view.findViewById(R.id.btnPlanCancel);
        textPlansTitle = view.findViewById(R.id.textPlansTitle);
        listPlans = view.findViewById(R.id.listPlans);
        listPlanPeriods = view.findViewById(R.id.listPlanPeriods);
        btnPlanPropose = view.findViewById(R.id.btnPlanPropose);
        btnPlanPropose.setOnClickListener(v -> proposePlan());
        btnPlan.setOnClickListener(v -> makePlan());
        view.<Button>findViewById(R.id.btnPlanSave).setOnClickListener(v -> savePlan());
        view.<Button>findViewById(R.id.btnPlanSend).setOnClickListener(v -> sendPlan());
        btnPlanDelete.setOnClickListener(v -> deletePlan());
        btnPlanCancel.setOnClickListener(v -> cancelHelperPlan());
        showSavedPlans();
        view.<Button>findViewById(R.id.btnFavPrev).setOnClickListener(v -> shiftNight(-1));
        view.<Button>findViewById(R.id.btnFavNext).setOnClickListener(v -> shiftNight(1));

        btnSearch.setOnClickListener(v -> searchTarget());
        editQuery.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                searchTarget();
                return true;
            }
            return false;
        });
        btnStart.setOnClickListener(v -> startObserve(false));
        btnContinue.setOnClickListener(v -> startObserve(true));
        refreshVisibility();
        refreshFavorites();
    }

    @Override
    public void onTabSelected() {
        refreshStatus();
        startAutoRefresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adb != null) {
            refreshStatus();
            if (!isHidden()) startAutoRefresh();
        }
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) handler.removeCallbacks(autoRefresh);
        else startAutoRefresh();
    }

    private void startAutoRefresh() {
        handler.removeCallbacks(autoRefresh);
        handler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    @Override
    public void onDestroyView() {
        if (previewBitmap != null) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        super.onDestroyView();
    }

    private void refreshStatus() {
        refreshVisibility();
        refreshFavorites();
        pullState(true);
    }

    /** Legge remote.state.json; {@code full} = ricalcola anche visibilità e preferiti se il sito viene dal Vespera. */
    private void pullState(boolean full) {
        if (adb == null || !adb.isConnected()) {
            lastTelescopeJson = null;
            return;
        }
        if (pulling) return;
        pulling = true;
        File dest = new File(requireContext().getCacheDir(), "remote.state.target.json");
        adb.pullState(dest, (ok, msg) -> {
            pulling = false;
            postUi(() -> showState(ok, dest, full));
        });
    }

    private void showState(boolean ok, File dest, boolean full) {
        if (textPlanHelper == null) return;
        if (!ok) {
            lastTelescopeJson = null;
            return;
        }
        String body = adb.readPulledText(dest);
        lastRawState = body == null ? "" : body;
        try {
            JSONObject root = new JSONObject(lastRawState);
            lastTelescopeJson = root.optJSONObject("telescope");
            showHelperPlan(root);
        } catch (Exception e) {
            lastTelescopeJson = null;
        }
        if (target != null) {
            session = SkyCatalog.findSession(lastRawState, target.name, target.query);
            showSession();
        }
        ObservatorySite.captureIfUnset(requireContext(), lastTelescopeJson);
        if (full && DevicePrefs.SITE_VESPERA.equals(DevicePrefs.getSiteSource(requireContext()))) {
            refreshVisibility();
            refreshFavorites();
        }
    }

    private void searchTarget() {
        String name = editQuery.getText() == null ? "" : editQuery.getText().toString().trim();
        if (name.isEmpty() || searchBusy) return;
        clearObserve();
        searchBusy = true;
        btnSearch.setEnabled(false);
        textResult.setVisibility(View.VISIBLE);
        textResult.setText(R.string.telescope_observe_searching);
        catalogWorker.execute(() -> {
            try {
                List<SkyCatalog.Hit> found = SkySearch.find(name);
                postUi(() -> {
                    if (found.size() == 1) {
                        loadHit(found.get(0));
                    } else {
                        showChoices(found);
                    }
                });
            } catch (Exception failure) {
                String code = failure.getMessage() == null ? "" : failure.getMessage();
                postUi(() -> {
                    searchBusy = false;
                    btnSearch.setEnabled(true);
                    textResult.setVisibility(View.VISIBLE);
                    int msg = "short".equals(code)
                            ? R.string.telescope_observe_short
                            : ("not_found".equals(code) || "empty".equals(code)
                            ? R.string.telescope_observe_not_found
                            : R.string.telescope_observe_net);
                    textResult.setText(msg);
                });
            }
        });
    }

    private void clearObserve() {
        target = null;
        session = null;
        if (previewBitmap != null) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        imagePreview.setImageDrawable(null);
        imagePreview.setVisibility(View.GONE);
        textDetails.setText("");
        textDetails.setVisibility(View.GONE);
        textSession.setText("");
        textSession.setVisibility(View.GONE);
        textResult.setText("");
        textResult.setVisibility(View.GONE);
        btnContinue.setVisibility(View.GONE);
        btnStart.setVisibility(View.GONE);
        btnFavToggle.setVisibility(View.GONE);
        if (listHits != null) {
            listHits.removeAllViews();
            listHits.setVisibility(View.GONE);
        }
        refreshVisibility();
    }

    private void showChoices(List<SkyCatalog.Hit> found) {
        searchBusy = false;
        btnSearch.setEnabled(true);
        listHits.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (10 * density);
        for (SkyCatalog.Hit hit : found) {
            TextView row = new TextView(requireContext());
            row.setText(hit.choiceLabel());
            row.setTextColor(getResources().getColor(R.color.vespera_text, null));
            row.setTextSize(14);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundColor(getResources().getColor(R.color.vespera_bg, null));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (4 * density);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                if (!searchBusy) loadHit(hit);
            });
            listHits.addView(row);
        }
        listHits.setVisibility(View.VISIBLE);
        textResult.setVisibility(View.VISIBLE);
        textResult.setText(R.string.telescope_observe_choose);
        refreshVisibility();
    }

    private void loadHit(SkyCatalog.Hit hit) {
        searchBusy = true;
        btnSearch.setEnabled(false);
        if (listHits != null) {
            listHits.removeAllViews();
            listHits.setVisibility(View.GONE);
        }
        if (previewBitmap != null) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        imagePreview.setImageDrawable(null);
        imagePreview.setVisibility(View.GONE);
        textDetails.setVisibility(View.GONE);
        textSession.setVisibility(View.GONE);
        btnStart.setVisibility(View.GONE);
        btnContinue.setVisibility(View.GONE);
        btnFavToggle.setVisibility(View.GONE);
        textResult.setVisibility(View.VISIBLE);
        textResult.setText(getString(R.string.telescope_observe_loading, hit.name));
        catalogWorker.execute(() -> {
            SkyCatalog.Target pictured = SkyCatalog.withPreview(hit);
            SkyCatalog.Session stored = pictured == null
                    ? null
                    : SkyCatalog.findSession(lastRawState, pictured.name, pictured.query);
            postUi(() -> {
                if (pictured == null) {
                    searchBusy = false;
                    btnSearch.setEnabled(true);
                    textResult.setText(R.string.telescope_observe_not_found);
                    return;
                }
                showTarget(pictured, stored);
            });
        });
    }

    private void showTarget(SkyCatalog.Target found, SkyCatalog.Session stored) {
        searchBusy = false;
        btnSearch.setEnabled(true);
        target = found;
        session = stored;

        if (previewBitmap != null) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        if (found.previewJpeg != null && found.previewJpeg.length > 0) {
            previewBitmap = BitmapFactory.decodeByteArray(
                    found.previewJpeg, 0, found.previewJpeg.length);
        }
        if (previewBitmap == null) {
            imagePreview.setImageDrawable(null);
            imagePreview.setVisibility(View.GONE);
        } else {
            imagePreview.setImageBitmap(previewBitmap);
            imagePreview.setVisibility(View.VISIBLE);
        }

        String type = SkyCatalog.typeLabel(found.typeCode);
        String typePart = type.isEmpty()
                ? found.typeCode
                : type + " (" + found.typeCode + ")";
        textDetails.setText(getString(R.string.telescope_observe_pointing,
                found.name,
                typePart,
                SkyCatalog.raText(found.raDeg),
                SkyCatalog.decText(found.decDeg),
                found.raDeg,
                found.decDeg));
        textDetails.setVisibility(View.VISIBLE);
        updateFavButton();
        showSession();
        if (found.previewJpeg == null) {
            textResult.setVisibility(View.VISIBLE);
            textResult.setText(R.string.telescope_observe_preview_fail);
        } else {
            textResult.setVisibility(View.GONE);
        }
        refreshVisibility();
    }

    private void showSession() {
        if (target == null) return;
        String text = currentObservationLine(lastTelescopeJson) + "\n";
        if (session != null) {
            text += getString(R.string.telescope_observe_store, session.stacks, session.objectName);
            btnContinue.setVisibility(View.VISIBLE);
        } else {
            text += getString(R.string.telescope_observe_no_store);
            btnContinue.setVisibility(View.GONE);
        }
        textSession.setText(text);
        textSession.setVisibility(View.VISIBLE);
        btnStart.setVisibility(View.VISIBLE);
    }

    /** Osservazione in corso sul telescopio: tempo integrato = pose × esposizione. */
    static String currentObservationLine(@Nullable JSONObject tel) {
        if (tel == null || !tel.optBoolean("reachable")) return "Osservazione corrente: telescopio non raggiungibile.";
        boolean observing = tel.has("isObserving")
                ? tel.optBoolean("isObserving")
                : "RUNNING".equals(tel.optString("observationStatus"));
        int stacks = tel.optInt("stackingCount", 0);
        if (!observing) return "Osservazione corrente: nessuna.";
        String name = tel.optString("targetName", "").trim();
        StringBuilder sb = new StringBuilder("Osservazione corrente: ")
                .append(name.isEmpty() ? "in corso" : name);
        long exp = tel.optLong("exposureMicroSec", 0);
        if (stacks > 0 && exp > 0) {
            long m = Math.round(stacks * (exp / 1_000_000.0) / 60.0);
            String time = m < 60 ? m + " min"
                    : m % 60 == 0 ? (m / 60) + "h" : String.format(Locale.ITALY, "%dh%02d", m / 60, m % 60);
            sb.append(" · ").append(time).append(" integrati (")
                    .append(stacks).append(String.format(Locale.ITALY, " pose da %.0f s)", exp / 1_000_000.0));
        } else if (stacks > 0) {
            sb.append(" · ").append(stacks).append(" pose");
        }
        return sb.toString();
    }

    private void refreshVisibility() {
        if (textVisibility == null || !isAdded()) return;
        if (!DevicePrefs.hasSite(requireContext())) {
            textVisibility.setVisibility(View.VISIBLE);
            textVisibility.setText(R.string.telescope_visibility_need_site);
            return;
        }
        double lat = DevicePrefs.getLat(requireContext());
        double lon = DevicePrefs.getLon(requireContext());
        SkyCatalog.Target current = target;
        final int gen = visibilityGen.incrementAndGet();
        final String where = siteLine(lat, lon);
        textVisibility.setVisibility(View.VISIBLE);
        textVisibility.setText(getString(R.string.telescope_visibility_loading) + "\n" + where);
        catalogWorker.execute(() -> {
            List<NightSky.Line> lines;
            if (current == null) {
                lines = NightSky.report(lat, lon, null, 0, 0);
            } else {
                lines = NightSky.report(lat, lon, current.name, current.raDeg, current.decDeg);
            }
            postUi(() -> {
                if (gen != visibilityGen.get() || textVisibility == null || !isAdded()) return;
                textVisibility.setVisibility(View.VISIBLE);
                textVisibility.setText(visibilityText(where, lines));
            });
        });
    }

    /** Righe del rapporto con faccina colorata: blu ottima, verde buona, giallo scarsa,
     * arancione non visibile per il meteo, rosso non visibile perché troppo basso. */
    private CharSequence visibilityText(String where, List<NightSky.Line> lines) {
        SpannableStringBuilder sb = new SpannableStringBuilder(where);
        int size = Math.round(textVisibility.getTextSize() * 1.2f);
        for (NightSky.Line line : lines) {
            sb.append('\n');
            if (line.level >= 0) {
                Drawable icon = ContextCompat.getDrawable(requireContext(),
                        visibilityIcon(line.level, line.weather));
                if (icon != null) {
                    icon = icon.mutate();
                    icon.setBounds(0, 0, size, size);
                    int at = sb.length();
                    sb.append("\u25CF");
                    sb.setSpan(new ImageSpan(icon, DynamicDrawableSpan.ALIGN_CENTER),
                            at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.append("  ");
                }
            }
            sb.append(line.text);
        }
        return sb;
    }

    private static int visibilityIcon(int level) {
        return visibilityIcon(level, false);
    }

    /** {@code weather}: non visibile solo per nubi/pioggia → arancione; altrimenti rosso (troppo basso). */
    private static int visibilityIcon(int level, boolean weather) {
        if (level >= NightSky.GREAT) return R.drawable.ic_vis_3;
        if (level == NightSky.GOOD) return R.drawable.ic_vis_2;
        if (level == NightSky.POOR) return R.drawable.ic_vis_1;
        return weather ? R.drawable.ic_vis_0_weather : R.drawable.ic_vis_0;
    }

    private String siteLine(double lat, double lon) {
        String source = DevicePrefs.getSiteSource(requireContext());
        String from;
        if (DevicePrefs.SITE_GPS.equals(source)) {
            from = getString(R.string.site_source_gps);
        } else if (DevicePrefs.SITE_VESPERA.equals(source)) {
            from = getString(R.string.site_source_vespera);
        } else if (DevicePrefs.SITE_MANUAL.equals(source)) {
            from = getString(R.string.site_source_manual);
        } else {
            from = "";
        }
        return String.format(Locale.ITALY, "%.2f°%s  %.2f°%s%s",
                Math.abs(lat), lat >= 0 ? " N" : " S",
                Math.abs(lon), lon >= 0 ? " E" : " O",
                from.isEmpty() ? "" : " · " + from);
    }

    // ------------------------------------------------------------------ Preferiti

    private void updateFavButton() {
        if (target == null) {
            btnFavToggle.setVisibility(View.GONE);
            return;
        }
        boolean fav = Favorites.contains(requireContext(), target.name);
        btnFavToggle.setText(fav ? R.string.fav_remove : R.string.fav_add);
        btnFavToggle.setVisibility(View.VISIBLE);
    }

    private void toggleFavorite() {
        if (target == null) return;
        boolean now = Favorites.toggle(requireContext(), target.name, target.typeCode,
                target.raDeg, target.decDeg);
        Toast.makeText(requireContext(),
                now ? "Aggiunto ai preferiti" : "Tolto dai preferiti", Toast.LENGTH_SHORT).show();
        updateFavButton();
        refreshFavorites();
    }

    private void shiftNight(int days) {
        if (!DevicePrefs.hasSite(requireContext())) return;
        LocalDate first = NightSky.defaultNight(DevicePrefs.getLat(requireContext()),
                DevicePrefs.getLon(requireContext()));
        LocalDate base = favNight == null ? first : favNight;
        LocalDate next = base.plusDays(days);
        if (next.isBefore(first)) next = first;
        if (next.isAfter(first.plusDays(365))) next = first.plusDays(365);
        favNight = next.equals(first) ? null : next;
        refreshFavorites();
    }

    private void refreshFavorites() {
        if (listFavorites == null || !isAdded()) return;
        List<NightSky.Item> items = Favorites.load(requireContext());
        listFavorites.removeAllViews();
        if (!DevicePrefs.hasSite(requireContext())) {
            textFavDay.setText("");
            textFavStatus.setText(R.string.telescope_visibility_need_site);
            return;
        }
        double lat = DevicePrefs.getLat(requireContext());
        double lon = DevicePrefs.getLon(requireContext());
        LocalDate first = NightSky.defaultNight(lat, lon);
        LocalDate night = favNight == null ? first : favNight;
        String day = DAY.format(night) + " → " + DAY.format(night.plusDays(1));
        textFavDay.setText(night.equals(first) ? "Stanotte · " + day : "Notte " + day);
        if (items.isEmpty()) {
            textFavStatus.setText(R.string.fav_empty);
            return;
        }
        textFavStatus.setText(R.string.fav_loading);
        final int gen = favGen.incrementAndGet();
        catalogWorker.execute(() -> {
            NightSky.Ranking ranking = NightSky.rank(lat, lon, items, night);
            postUi(() -> {
                if (gen != favGen.get() || listFavorites == null || !isAdded()) return;
                showRanking(ranking);
            });
        });
    }

    private void showRanking(NightSky.Ranking ranking) {
        listFavorites.removeAllViews();
        String status = ranking.nightLine;
        if (!ranking.weather) status += "\n" + getString(R.string.fav_no_weather);
        textFavStatus.setText(status);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (10 * density);
        int position = 1;
        for (NightSky.Rank rank : ranking.rows) {
            TextView row = new TextView(requireContext());
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int size = Math.round(row.getTextSize() * 1.3f);
            Drawable icon = ContextCompat.getDrawable(requireContext(),
                    visibilityIcon(rank.best, rank.weatherBlocked));
            if (icon != null) {
                icon = icon.mutate();
                icon.setBounds(0, 0, size, size);
                sb.append("\u25CF");
                sb.setSpan(new ImageSpan(icon, DynamicDrawableSpan.ALIGN_CENTER),
                        0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("  ");
            }
            sb.append(String.valueOf(position++)).append(". ").append(rank.headline());
            String type = SkyCatalog.typeLabel(rank.typeCode);
            // sotto: tipo, poi le ore migliori oppure il motivo meteo se è il meteo a nasconderlo
            String detail = rank.bestRanges.isEmpty() ? rank.reason : rank.bestRanges;
            if (!detail.isEmpty() || !type.isEmpty()) {
                sb.append("\n      ");
                if (!type.isEmpty()) sb.append(type);
                if (!type.isEmpty() && !detail.isEmpty()) sb.append(" · ");
                sb.append(detail);
            }
            row.setText(sb);
            row.setTextColor(getResources().getColor(R.color.vespera_text, null));
            row.setTextSize(14);
            row.setPadding(0, pad, pad, pad);
            row.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(v -> openFavorite(rank));
            // Spunta per il piano della notte
            CheckBox check = new CheckBox(requireContext());
            String key = Favorites.keyOf(rank.name);
            check.setChecked(selected.contains(key));
            check.setOnCheckedChangeListener((b, on) -> {
                if (on) selected.add(key);
                else selected.remove(key);
            });
            LinearLayout line = new LinearLayout(requireContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            line.setBackgroundColor(getResources().getColor(R.color.vespera_bg, null));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (4 * density);
            line.setLayoutParams(lp);
            line.addView(check);
            line.addView(row);
            listFavorites.addView(line);
        }
    }

    // ------------------------------------------------------------------ Piano della notte

    private void makePlan() {
        if (!DevicePrefs.hasSite(requireContext())) {
            showPlanMessage(getString(R.string.telescope_visibility_need_site));
            return;
        }
        List<NightSky.Item> chosen = new ArrayList<>();
        for (NightSky.Item item : Favorites.load(requireContext())) {
            if (selected.contains(Favorites.keyOf(item.name))) chosen.add(item);
        }
        if (chosen.isEmpty()) {
            showPlanMessage(getString(R.string.plan_need_select));
            return;
        }
        double lat = DevicePrefs.getLat(requireContext());
        double lon = DevicePrefs.getLon(requireContext());
        LocalDate first = NightSky.defaultNight(lat, lon);
        LocalDate night = favNight == null ? first : favNight;
        showPlanMessage(getString(R.string.plan_computing));
        btnPlan.setEnabled(false);
        catalogWorker.execute(() -> {
            JSONObject plan = NightSky.plan(lat, lon, chosen, night);
            postUi(() -> {
                btnPlan.setEnabled(true);
                showPlan(plan, false);
            });
        });
    }

    private void showPlanMessage(String text) {
        currentPlan = null;
        textPlan.setText(text);
        textPlan.setVisibility(View.VISIBLE);
        rowPlanActions.setVisibility(View.GONE);
        btnPlanDelete.setVisibility(View.GONE);
        btnPlanPropose.setVisibility(View.GONE);
        listPlanPeriods.removeAllViews();
    }

    private void showPlan(JSONObject plan, boolean saved) {
        currentPlan = plan;
        currentPlanSaved = saved;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(plan.optString("title"));
        JSONArray items = plan.optJSONArray("items");
        if (items != null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null) names.add(it.optString("name"));
            }
            sb.append(" · ").append(TextUtils.join(", ", names));
        }
        if (!plan.optBoolean("weather", true)) sb.append("\n").append(getString(R.string.fav_no_weather));
        int size = Math.round(textPlan.getTextSize() * 1.2f);
        JSONArray periods = plan.optJSONArray("periods");
        boolean hasPeriods = periods != null && periods.length() > 0;
        JSONArray lines = hasPeriods ? null : plan.optJSONArray("lines"); // piani prima della 0.2.31
        if (hasPeriods) {
            JSONObject p0 = periods.optJSONObject(0);
            int mins = (int) Math.round((p0.optLong("end") - p0.optLong("start")) / 60000.0);
            int off = 0;
            for (int i = 0; i < periods.length(); i++) {
                JSONObject p = periods.optJSONObject(i);
                if (p != null && !p.optBoolean("enabled")) off++;
            }
            sb.append("\n").append(getString(R.string.plan_periods_help, periods.length(), mins));
            if (off > 0) sb.append(" ").append(getString(R.string.plan_periods_off, off));
            JSONArray steps = plan.optJSONArray("steps");
            if (steps == null || steps.length() == 0) sb.append("\n").append(getString(R.string.plan_empty));
        }
        for (int i = 0; lines != null && i < lines.length(); i++) {
            JSONArray line = lines.optJSONArray(i);
            if (line == null) continue;
            sb.append('\n');
            int level = line.optInt(0, -1);
            if (level >= 0) {
                Drawable icon = ContextCompat.getDrawable(requireContext(),
                        visibilityIcon(level, line.optBoolean(2)));
                if (icon != null) {
                    icon = icon.mutate();
                    icon.setBounds(0, 0, size, size);
                    int at = sb.length();
                    sb.append("\u25CF");
                    sb.setSpan(new ImageSpan(icon, DynamicDrawableSpan.ALIGN_CENTER),
                            at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.append("  ");
                }
            } else {
                sb.append("      ");
            }
            sb.append(line.optString(1));
        }
        if (planOver(plan)) sb.append("\n").append(getString(R.string.plan_past));
        textPlan.setText(sb);
        textPlan.setVisibility(View.VISIBLE);
        rowPlanActions.setVisibility(View.VISIBLE);
        btnPlanDelete.setVisibility(saved ? View.VISIBLE : View.GONE);
        btnPlanPropose.setVisibility(hasPeriods ? View.VISIBLE : View.GONE);
        renderPlanPeriods(plan);
    }

    /** Un blocco per periodo: spunta (attivo) + faccina + orario + meteo, sotto il menu oggetti (★ = proposto). */
    private void renderPlanPeriods(JSONObject plan) {
        listPlanPeriods.removeAllViews();
        JSONArray periods = plan.optJSONArray("periods");
        JSONArray items = plan.optJSONArray("items");
        JSONArray lines = plan.optJSONArray("lines");
        if (periods == null || items == null) return;
        float density = getResources().getDisplayMetrics().density;
        int n = items.length();
        for (int i = 0; i < periods.length(); i++) {
            JSONObject p = periods.optJSONObject(i);
            if (p == null) continue;
            final int index = i;
            boolean on = p.optBoolean("enabled");
            LinearLayout block = new LinearLayout(requireContext());
            block.setOrientation(LinearLayout.VERTICAL);
            block.setBackgroundColor(getResources().getColor(R.color.vespera_bg, null));
            block.setPadding(0, (int) (2 * density), (int) (6 * density), (int) (6 * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (4 * density);
            block.setLayoutParams(lp);

            LinearLayout head = new LinearLayout(requireContext());
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            CheckBox check = new CheckBox(requireContext());
            check.setChecked(on);
            check.setOnCheckedChangeListener((b, value) -> togglePeriod(index, value));
            head.addView(check);
            TextView label = new TextView(requireContext());
            SpannableStringBuilder sb = new SpannableStringBuilder();
            JSONArray line = lines == null ? null : lines.optJSONArray(i);
            int level = line == null ? -1 : line.optInt(0, -1);
            int size = Math.round(label.getTextSize() * 1.2f);
            Drawable icon = level >= 0 ? ContextCompat.getDrawable(requireContext(),
                    visibilityIcon(level, line.optBoolean(2))) : null;
            if (icon != null) {
                icon = icon.mutate();
                icon.setBounds(0, 0, size, size);
                sb.append("\u25CF");
                sb.setSpan(new ImageSpan(icon, DynamicDrawableSpan.ALIGN_CENTER), 0, 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("  ");
            }
            java.time.format.DateTimeFormatter clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            sb.append(clock.format(java.time.Instant.ofEpochMilli(p.optLong("start")).atZone(zone)))
                    .append("–")
                    .append(clock.format(java.time.Instant.ofEpochMilli(p.optLong("end")).atZone(zone)))
                    .append("   ");
            JSONObject sky = p.optJSONObject("sky");
            String kind = sky == null ? "" : sky.optString("kind");
            int at = sb.length();
            sb.append(NightSky.skyText(sky));
            int color;
            switch (kind) {
                case NightSky.SKY_SUNNY: color = 0xFFB7791F; break;
                case NightSky.SKY_FEW: color = 0xFF4A6F86; break;
                case NightSky.SKY_CLOUDY: color = 0xFF37474F; break;
                case NightSky.SKY_RAIN: color = 0xFF1E5AA8; break;
                default: color = getResources().getColor(R.color.vespera_muted, null);
            }
            sb.setSpan(new android.text.style.ForegroundColorSpan(color), at, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (kind.equals(NightSky.SKY_CLOUDY) || kind.equals(NightSky.SKY_RAIN)) {
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            label.setText(sb);
            label.setTextSize(14);
            label.setTextColor(getResources().getColor(on ? R.color.vespera_text : R.color.vespera_muted, null));
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            label.setOnClickListener(v -> check.toggle());
            head.addView(label);
            block.addView(head);

            List<String> options = new ArrayList<>();
            for (int o = 0; o < n; o++) options.add(NightSky.optionText(plan, p, o));
            options.add(getString(R.string.plan_none_option));
            android.widget.Spinner spinner = new android.widget.Spinner(requireContext());
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                    requireContext(), android.R.layout.simple_spinner_item, options) {
                @NonNull
                @Override
                public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    TextView t = (TextView) super.getView(position, convertView, parent);
                    t.setTextColor(getResources().getColor(R.color.vespera_text, null));
                    t.setTextSize(14);
                    return t;
                }

                @Override
                public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    TextView t = (TextView) super.getDropDownView(position, convertView, parent);
                    t.setTextColor(getResources().getColor(R.color.vespera_text, null));
                    t.setBackgroundColor(getResources().getColor(R.color.vespera_surface, null));
                    t.setPadding((int) (12 * density), (int) (10 * density), (int) (12 * density), (int) (10 * density));
                    return t;
                }
            };
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            int choice = p.optInt("choice", -1);
            final int current = choice >= 0 && choice < n ? choice : n;
            spinner.setSelection(current, false);
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                    if (position != current) choosePeriod(index, position < n ? position : -1);
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                }
            });
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.leftMargin = (int) (40 * density);
            spinner.setLayoutParams(sp);
            block.addView(spinner);
            listPlanPeriods.addView(block);
        }
        List<String> totals = NightSky.totalsText(plan.optJSONArray("totals") != null
                ? plan.optJSONArray("totals") : NightSky.totals(plan));
        if (!totals.isEmpty()) {
            TextView head = new TextView(requireContext());
            head.setText(R.string.plan_totals_title);
            head.setTextColor(getResources().getColor(R.color.vespera_title, null));
            head.setTypeface(null, android.graphics.Typeface.BOLD);
            head.setTextSize(14);
            head.setPadding(0, (int) (6 * density), 0, 0);
            listPlanPeriods.addView(head);
            TextView body = new TextView(requireContext());
            body.setText(TextUtils.join("\n", totals));
            body.setTextColor(getResources().getColor(R.color.vespera_text, null));
            body.setTextSize(14);
            listPlanPeriods.addView(body);
        }
    }

    private void planChanged() {
        if (currentPlan == null) return;
        NightSky.finalizePlan(currentPlan);
        boolean saved = currentPlanSaved;
        listPlanPeriods.post(() -> {
            if (currentPlan != null && isAdded()) showPlan(currentPlan, saved);
        });
    }

    private void togglePeriod(int index, boolean on) {
        if (currentPlan == null) return;
        JSONObject p = currentPlan.optJSONArray("periods").optJSONObject(index);
        try {
            p.put("enabled", on);
            if (on && p.optInt("choice", -1) < 0) p.put("choice", p.optInt("proposed", -1));
        } catch (Exception ignored) {
        }
        planChanged();
    }

    private void choosePeriod(int index, int choice) {
        if (currentPlan == null) return;
        JSONObject p = currentPlan.optJSONArray("periods").optJSONObject(index);
        try {
            p.put("choice", choice);
            if (choice >= 0) p.put("enabled", true);
        } catch (Exception ignored) {
        }
        planChanged();
    }

    /** Rimette le spunte (tutti i periodi con un oggetto alto) e rifà la proposta. */
    private void proposePlan() {
        if (currentPlan == null || currentPlan.optJSONArray("periods") == null) return;
        NightSky.resetPlan(currentPlan);
        planChanged();
    }

    private static boolean planOver(JSONObject plan) {
        long last = 0;
        for (String key : new String[]{"periods", "steps"}) {
            JSONArray arr = plan.optJSONArray(key);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) last = Math.max(last, o.optLong("end"));
            }
        }
        return last <= System.currentTimeMillis();
    }

    private void savePlan() {
        if (currentPlan == null) return;
        Favorites.savePlan(requireContext(), currentPlan);
        currentPlanSaved = true;
        btnPlanDelete.setVisibility(View.VISIBLE);
        Toast.makeText(requireContext(), "Piano salvato", Toast.LENGTH_SHORT).show();
        showSavedPlans();
    }

    private void deletePlan() {
        if (currentPlan == null || !currentPlanSaved) return;
        Favorites.deletePlan(requireContext(), currentPlan.optLong("created"));
        Toast.makeText(requireContext(), "Piano eliminato", Toast.LENGTH_SHORT).show();
        currentPlan = null;
        textPlan.setVisibility(View.GONE);
        rowPlanActions.setVisibility(View.GONE);
        btnPlanDelete.setVisibility(View.GONE);
        showSavedPlans();
    }

    private void sendPlan() {
        if (currentPlan == null) return;
        if (planOver(currentPlan)) {
            Toast.makeText(requireContext(), R.string.plan_past, Toast.LENGTH_LONG).show();
            return;
        }
        JSONArray planSteps = currentPlan.optJSONArray("steps");
        if (planSteps == null || planSteps.length() == 0) {
            Toast.makeText(requireContext(), R.string.plan_empty, Toast.LENGTH_LONG).show();
            return;
        }
        if (adb == null || !adb.isConnected()) {
            textPlanHelper.setText(R.string.telescope_observe_need_wifi);
            return;
        }
        String json;
        try {
            JSONObject body = new JSONObject();
            body.put("title", currentPlan.optString("title"));
            body.put("night", currentPlan.optString("night"));
            body.put("parkAtEnd", currentPlan.optBoolean("parkAtEnd", true));
            body.put("steps", currentPlan.optJSONArray("steps"));
            // per vedere il piano sull'Helper: righe dei periodi e tempo per oggetto
            JSONArray planLines = new JSONArray();
            JSONArray rawLines = currentPlan.optJSONArray("lines");
            for (int i = 0; rawLines != null && i < rawLines.length(); i++) {
                JSONArray l = rawLines.optJSONArray(i);
                if (l != null) planLines.put(l.optString(1));
            }
            body.put("lines", planLines);
            body.put("totals", new JSONArray(NightSky.totalsText(currentPlan.optJSONArray("totals"))));
            json = body.toString();
        } catch (Exception e) {
            return;
        }
        textPlanHelper.setText("Invio del piano all'Helper…");
        adb.sendLine("cmd|plan|load|" + json, (ok, msg) -> postUi(() -> {
            textPlanHelper.setText(ok ? "Piano caricato sull'Helper: parte da solo agli orari."
                    : "Piano non caricato: " + msg
                    + (msg != null && msg.contains("unknown") ? " (aggiorna Vespera Helper ≥ 0.8.23)" : ""));
            if (ok) refreshStatus();
        }));
    }

    private void cancelHelperPlan() {
        if (adb == null || !adb.isConnected()) return;
        adb.sendLine("cmd|plan|cancel", (ok, msg) -> postUi(() -> {
            if (ok) refreshStatus();
            else textPlanHelper.setText("Piano non annullato: " + msg);
        }));
    }

    /** Stato del piano sull'Helper, da remote.state.json. */
    private void showHelperPlan(JSONObject root) {
        if (textPlanHelper == null) return;
        JSONObject plan = root.optJSONObject("plan");
        if (plan == null) {
            textPlanHelper.setText("L'Helper non gestisce i piani: aggiorna Vespera Helper (≥ 0.8.23).");
            btnPlanCancel.setVisibility(View.GONE);
            return;
        }
        JSONArray steps = plan.optJSONArray("steps");
        if (steps == null || steps.length() == 0) {
            String msg = plan.optString("message", "");
            textPlanHelper.setText("Helper: nessun piano" + (msg.isEmpty() ? "" : " · " + msg));
            btnPlanCancel.setVisibility(View.GONE);
            return;
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("Helper: ")
                .append(plan.optString("title")).append(" · ");
        // Stato del piano (e messaggio) in neretto
        int boldStart = sb.length();
        sb.append(plan.optBoolean("done") ? "terminato" : "attivo");
        String msg = plan.optString("message", "");
        // "Errore su X: ..." è già nella riga del passo, non ripeterlo
        if (!msg.isEmpty() && !msg.startsWith("Errore su ")) sb.append("\n").append(readablePlanError(msg));
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), boldStart, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int current = plan.optInt("current", -1);
        java.time.format.DateTimeFormatter clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s == null) continue;
            String from = clock.format(java.time.Instant.ofEpochMilli(s.optLong("start"))
                    .atZone(java.time.ZoneId.systemDefault()));
            String to = clock.format(java.time.Instant.ofEpochMilli(s.optLong("end"))
                    .atZone(java.time.ZoneId.systemDefault()));
            sb.append("\n").append(i == current ? "▶ " : "   ").append(from).append("–").append(to)
                    .append("  ").append(s.optString("name"));
            String st = readablePlanError(s.optString("status", ""));
            if (st.startsWith("errore")) sb.append("\n      ⚠ ").append(st);
            else if (!st.isEmpty()) sb.append(" · ").append(st);
            long tracked = s.optLong("trackedMs");
            if (tracked >= 60_000L) sb.append(" · tracking ").append(minutes(tracked));
        }
        // Tracking reale misurato dall'Helper (≥ 0.8.47): per oggetto somma dei suoi passi, poi il piano
        java.util.LinkedHashMap<String, Long> byName = new java.util.LinkedHashMap<>();
        long all = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s == null) continue;
            Long sum = byName.get(s.optString("name"));
            byName.put(s.optString("name"), (sum == null ? 0 : sum) + s.optLong("trackedMs"));
            all += s.optLong("trackedMs");
        }
        if (all >= 60_000L) {
            sb.append("\nTracking reale (somma dei passi):");
            for (java.util.Map.Entry<String, Long> e : byName.entrySet()) {
                sb.append("\n   ").append(e.getKey()).append(" · ").append(minutes(e.getValue()));
            }
            sb.append("\n   Totale piano ").append(minutes(all));
        }
        JSONArray totals = plan.optJSONArray("totals");
        if (totals != null && totals.length() > 0) {
            sb.append("\nTempo per oggetto:");
            for (int i = 0; i < totals.length(); i++) sb.append("\n   ").append(totals.optString(i));
        }
        textPlanHelper.setText(sb);
        btnPlanCancel.setVisibility(plan.optBoolean("active") ? View.VISIBLE : View.GONE);
    }

    /** "40 min", "1h20" (come l'Helper). */
    static String minutes(long ms) {
        long m = Math.round(ms / 60000.0);
        if (m < 60) return m + " min";
        return m % 60 == 0 ? (m / 60) + "h" : String.format(java.util.Locale.ITALY, "%dh%02d", m / 60, m % 60);
    }

    /** Sostituisce il JSON d'errore del firmware (Helper < 0.8.31) con una frase leggibile. */
    static String readablePlanError(String text) {
        int start = text.indexOf("{\"");
        if (start < 0) return text;
        String prefix = text.substring(0, start);
        String raw = text.substring(start);
        String detail;
        if (raw.contains("RESOURCE_IS_NOT_AVAILABLE") || raw.contains("REQUIRE_RESOURCES_FAILED")) {
            java.util.LinkedHashSet<String> res = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"resourceName\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
            while (m.find()) res.add(m.group(1));
            detail = "Vespera occupato da un'altra osservazione"
                    + (res.isEmpty() ? "" : " (in uso: " + TextUtils.join(", ", res) + ")");
        } else {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
            detail = m.find() ? "firmware " + m.group(1) : "firmware: comando rifiutato";
        }
        return prefix + detail;
    }

    private void showSavedPlans() {
        if (listPlans == null || !isAdded()) return;
        listPlans.removeAllViews();
        List<JSONObject> plans = Favorites.plans(requireContext());
        textPlansTitle.setVisibility(plans.isEmpty() ? View.GONE : View.VISIBLE);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (10 * density);
        for (JSONObject plan : plans) {
            TextView row = new TextView(requireContext());
            JSONArray items = plan.optJSONArray("items");
            List<String> names = new ArrayList<>();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null) names.add(it.optString("name"));
            }
            row.setText(plan.optString("title") + "  ·  " + TextUtils.join(", ", names));
            row.setTextColor(getResources().getColor(R.color.vespera_text, null));
            row.setTextSize(14);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundColor(getResources().getColor(R.color.vespera_bg, null));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (4 * density);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> openSavedPlan(plan));
            listPlans.addView(row);
        }
    }

    /** Apre un piano salvato: spunta i suoi oggetti e, se la sua notte non è passata, la seleziona. */
    private void openSavedPlan(JSONObject plan) {
        selected.clear();
        JSONArray items = plan.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) selected.add(Favorites.keyOf(it.optString("name")));
        }
        try {
            LocalDate night = LocalDate.parse(plan.optString("night"));
            LocalDate first = NightSky.defaultNight(DevicePrefs.getLat(requireContext()),
                    DevicePrefs.getLon(requireContext()));
            if (!night.isBefore(first)) favNight = night.equals(first) ? null : night;
        } catch (Exception ignored) {
        }
        showPlan(plan, true);
        refreshFavorites();
    }

    private void openFavorite(NightSky.Rank rank) {
        if (searchBusy) return;
        editQuery.setText(rank.name);
        clearObserve();
        loadHit(new SkyCatalog.Hit(rank.name, rank.typeCode, rank.raDeg, rank.decDeg));
        if (scroll != null) scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, editQuery.getTop() - 40)));
    }

    private void startObserve(boolean resume) {
        if (target == null) return;
        if (resume && (session == null || TextUtils.isEmpty(session.storeId))) return;
        if (adb == null || !adb.isConnected()) {
            textResult.setVisibility(View.VISIBLE);
            textResult.setText(R.string.telescope_observe_need_wifi);
            return;
        }
        String line;
        if (resume) {
            line = "cmd|telescope|observeResume|" + session.storeId;
        } else {
            String json = SkyCatalog.startBody(target, lastTelescopeJson);
            if (TextUtils.isEmpty(json)) {
                textResult.setVisibility(View.VISIBLE);
                textResult.setText(R.string.telescope_observe_fail_empty);
                return;
            }
            line = "cmd|telescope|observe|" + json;
        }
        textResult.setVisibility(View.VISIBLE);
        textResult.setText(R.string.telescope_observe_sending);
        adb.sendLine(line, (ok, msg) -> postUi(() -> {
            textResult.setText(ok
                    ? getString(resume
                    ? R.string.telescope_observe_ok_resume
                    : R.string.telescope_observe_ok_new, target.name)
                    : getString(R.string.telescope_observe_fail, msg));
            Toast.makeText(requireContext(), ok ? "OK" : "Errore", Toast.LENGTH_SHORT).show();
            if (ok) {
                refreshStatus();
            }
        }));
    }

    private void postUi(Runnable r) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(r);
    }
}