package com.vaonis.vesperacontrol.ui.telescopio;

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

public class TelescopioFragment extends Fragment implements TabRefreshable {

    private TextView textTelStatus;
    private TextView textTelDetails;
    private TextView textLog;
    private AdbBridge adb;
    private JSONObject lastTelescopeJson;

    /** Rilegge lo stato del telescopio ogni secondo mentre il tab è aperto. */
    private static final long AUTO_REFRESH_MS = 1_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean pulling;
    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (!isResumed() || isHidden()) return;
            pullState();
            handler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_telescopio, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adb = AdbBridgeHolder.get(requireContext());
        textTelStatus = view.findViewById(R.id.textTelStatus);
        textTelDetails = view.findViewById(R.id.textTelDetails);
        textLog = view.findViewById(R.id.textTelescopioLog);

        telButtons.clear();
        bindTelButton(view, R.id.btnUnpark, "init", v -> confirmTelescope("init"));
        bindTelButton(view, R.id.btnPark, "park", v -> confirmTelescope("park"));
        bindTelButton(view, R.id.btnAbort, "stop", v -> confirmTelescope("stop"));
        bindTelButton(view, R.id.btnGoto, "resume", v -> confirmTelescope("resume"));
        bindTelButton(view, R.id.btnShutdown, "shutdown", v -> confirmTelescope("shutdown"));
        editAzStep = view.findViewById(R.id.editAzStep);
        // "Vai" attivo solo a telescopio fermo: vedi updateTelButtons / motionBusy.
        bindTelButton(view, R.id.btnMotorGo, "azRelative", v -> confirmMotorMove(
                radioModeAbs != null && radioModeAbs.isChecked() ? "abs" : "rel"));
        radioModeAbs = view.findViewById(R.id.radioModeAbs);
        textMotorPos = view.findViewById(R.id.textMotorPos);
        radioAxisAlt = view.findViewById(R.id.radioAxisAlt);
        // "Stop movimento" attivo solo se qualcosa si muove: vedi stopMoveAllowed.
        bindTelButton(view, R.id.btnAzStop, "azStop", v -> sendAzStop());
        textCable = view.findViewById(R.id.textCable);
        textMotionLog = view.findViewById(R.id.textMotionLog);
        view.<Button>findViewById(R.id.btnCableZero).setOnClickListener(v -> confirmCableZero());
        checkCableAuto = view.findViewById(R.id.checkCableAuto);
        checkCableAuto.setOnClickListener(v -> sendCableLine(
                "cmd|cable|auto|" + (checkCableAuto.isChecked() ? "on" : "off")));
        updateTelButtons();
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

    private void refreshStatus() {
        pullState();
    }

    /** Legge remote.state.json e aggiorna stato, posizione, cavo e pulsanti. */
    private void pullState() {
        if (adb == null || !adb.isConnected()) {
            if (textTelStatus != null) {
                textTelStatus.setText(R.string.conn_status_fail);
                textTelStatus.setVisibility(View.VISIBLE);
            }
            lastTelescopeJson = null;
            updateTelButtons();
            return;
        }
        if (pulling) return;
        pulling = true;
        File dest = new File(requireContext().getCacheDir(), "remote.state.json");
        adb.pullState(dest, (ok, msg) -> {
            pulling = false;
            postUi(() -> showState(ok, msg, dest));
        });
    }

    private void showState(boolean ok, String msg, File dest) {
        if (textTelStatus == null) return;
        if (!ok) {
            textTelStatus.setText(msg);
            textTelStatus.setVisibility(View.VISIBLE);
            lastTelescopeJson = null;
            updateTelButtons();
            return;
        }
        String body = adb.readPulledText(dest);
        RemoteState state = RemoteState.parse(body == null ? "" : body);
        textTelStatus.setText(state.telLine);
        textTelStatus.setVisibility(state.telLine.isEmpty() ? View.GONE : View.VISIBLE);
        textTelDetails.setText(state.telDetails);
        try {
            JSONObject root = new JSONObject(body == null ? "" : body);
            lastTelescopeJson = root.optJSONObject("telescope");
            showCable(root.optJSONObject("cable"));
        } catch (Exception e) {
            lastTelescopeJson = null;
        }
        updateTelButtons();
        ObservatorySite.captureIfUnset(requireContext(), lastTelescopeJson);
    }

    // Comandi telescopio: etichetta, domanda di conferma, pericoloso (stesso testo di Windows).
    private static String[] telescopeAction(String action) {
        switch (action) {
            case "init":
                return new String[] {"Init", "Inizializza il telescopio (apertura braccio, calibrazione e puntamento iniziale).", ""};
            case "park":
                return new String[] {"Park", "Riporta il telescopio in posizione di parcheggio (braccio chiuso).", ""};
            case "stop":
                return new String[] {"Stop", "Ferma l'osservazione in corso. Lo stacking si interrompe.", "!"};
            case "resume":
                return new String[] {"Riprendi", "Riprende l'osservazione dell'ultimo oggetto (fa l'Init se serve).", ""};
            case "shutdown":
                return new String[] {"Shutdown", "Spegne il telescopio. Per riaccenderlo serve il pulsante fisico.", "!"};
            default:
                return null;
        }
    }

    private boolean telescopeBusy;

    /** Pulsanti comando con l'azione che inviano e il colore originale (per riattivarli). */
    private final List<Object[]> telButtons = new ArrayList<>();

    private void bindTelButton(View root, int id, String action, View.OnClickListener click) {
        Button b = root.findViewById(id);
        b.setOnClickListener(click);
        telButtons.add(new Object[] {b, action, b.getBackgroundTintList(), b.getTextColors()});
    }

    /**
     * Comandi eseguibili ora, dallo stato del telescopio (stesse regole del pannello Helper e di Windows).
     * Non raggiungibile o in spegnimento: nessun comando. I campi assenti (Helper vecchi) non bloccano.
     */
    public static Set<String> allowedTelActions(@Nullable JSONObject tel) {
        Set<String> out = new HashSet<>();
        if (tel == null || !tel.optBoolean("reachable") || tel.optBoolean("isShuttingDown")) return out;
        boolean moving = telMoving(tel);
        if (!tel.optBoolean("initialized") && !moving) out.add("init");
        if (!"CLOSED".equals(tel.optString("arm"))) out.add("park");
        if (moving || !tel.has("isObserving")) out.add("stop");
        if (!moving && tel.optBoolean("canResumeObservation", true)) out.add("resume");
        out.add("shutdown");
        if (!moving) out.add("azRelative");
        return out;
    }

    /** Osservazione in corso, tracking acceso (o in avvio) oppure auto-init: il telescopio si muove. */
    static boolean telMoving(@NonNull JSONObject tel) {
        boolean observing = tel.has("isObserving")
                ? tel.optBoolean("isObserving")
                : "RUNNING".equals(tel.optString("observationStatus"));
        String tracking = tel.optString("tracking");
        return observing || "ON".equals(tracking) || "STARTING".equals(tracking)
                || tel.optBoolean("isAutoInitRunning");
    }

    /**
     * «Stop movimento» serve solo se qualcosa si muove: osservazione o tracking, braccio o
     * rotazione in corso, oppure un comando appena inviato. Senza tracking e a osservazione
     * ferma resta spento.
     */
    private boolean stopMoveAllowed() {
        JSONObject tel = lastTelescopeJson;
        if (tel == null || !tel.optBoolean("reachable")) return false;
        return telescopeBusy || motionBusy() || telMoving(tel);
    }

    /** Movimento in corso: rotazione dell'Helper, braccio in movimento o posizione appena cambiata. */
    private boolean motionBusy() {
        if (cableBusy) return true;
        if (lastTelescopeJson != null && "MOVING".equals(lastTelescopeJson.optString("arm"))) return true;
        return System.currentTimeMillis() < motionBusyUntil;
    }

    /** Disattiva e colora in grigio i comandi non eseguibili; tutti spenti mentre un comando è in corso. */
    private void updateTelButtons() {
        if (!isAdded()) return;
        Set<String> allowed = telescopeBusy ? new HashSet<>() : allowedTelActions(lastTelescopeJson);
        if (motionBusy()) allowed.remove("azRelative");
        if (stopMoveAllowed()) allowed.add("azStop");
        android.content.res.ColorStateList grey = android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.vespera_steel, null));
        android.content.res.ColorStateList greyText = android.content.res.ColorStateList.valueOf(0xFFDDE3E8);
        for (Object[] t : telButtons) {
            Button b = (Button) t[0];
            boolean on = allowed.contains((String) t[1]);
            b.setEnabled(on);
            b.setBackgroundTintList(on ? (android.content.res.ColorStateList) t[2] : grey);
            b.setTextColor(on ? (android.content.res.ColorStateList) t[3] : greyText);
            b.setAlpha(on ? 1f : 0.7f);
        }
    }

    private void confirmTelescope(String action) {
        String[] info = telescopeAction(action);
        if (info == null || !isAdded()) return;
        if (telescopeBusy) {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Comando telescopio")
                    .setMessage("C'è già un comando telescopio in corso: aspetta che finisca e riprova.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        boolean danger = !info[2].isEmpty();
        androidx.appcompat.app.AlertDialog.Builder b =
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("Conferma: " + info[0])
                        .setMessage(info[1] + "\n\nInviare il comando al telescopio?")
                        .setNegativeButton("Annulla", null)
                        .setPositiveButton(info[0], (d, w) -> sendTelescope(action, info[0]));
        if (danger) b.setIcon(android.R.drawable.ic_dialog_alert);
        b.show();
    }

    private android.widget.EditText editAzStep;
    private TextView textCable;
    private TextView textMotionLog;
    private android.widget.RadioButton radioModeAbs;
    /** Fino a quando considerare il telescopio in movimento (posizione appena cambiata o comando inviato). */
    private long motionBusyUntil;
    private double prevAz = Double.NaN;
    private double prevAlt = Double.NaN;
    private boolean cableBusy;
    private android.widget.CheckBox checkCableAuto;
    private double cableTurnsDeg;
    private TextView textMotorPos;
    private android.widget.RadioButton radioAxisAlt;

    /** Riga "Cavo" dal blocco {@code cable} di remote.state.json (Helper ≥ 0.8.47). */
    private void showCable(JSONObject cable) {
        if (textCable == null) return;
        showMotionLog(cable == null ? null : cable.optJSONArray("log"));
        if (cable == null) {
            textCable.setText("Cavo: conteggio non disponibile (aggiorna Vespera Helper ≥ 0.8.47).");
            if (textMotorPos != null) textMotorPos.setText("Posizione: AZ —  ·  ALT —");
            cableBusy = false;
            return;
        }
        if (textMotorPos != null) {
            boolean hasAz = cable.has("az") && !cable.isNull("az");
            boolean hasAlt = cable.has("alt") && !cable.isNull("alt");
            double alt = cable.optDouble("alt", 0.0);
            textMotorPos.setText("Posizione: AZ "
                    + (hasAz ? String.format(Locale.ITALY, "%.1f°", cable.optDouble("az")) : "—")
                    + "  ·  ALT " + (hasAlt ? String.format(Locale.ITALY, "%.1f°", alt) : "—")
                    + (hasAlt && alt <= -80 ? "  (braccio chiuso)" : ""));
        }
        cableTurnsDeg = cable.optDouble("turnsDeg", 0.0);
        StringBuilder line = new StringBuilder(String.format(Locale.ITALY,
                "Cavo: %+.1f° (%+.2f giri)  ·  auto %s",
                cableTurnsDeg, cableTurnsDeg / 360.0, cable.optBoolean("auto", true) ? "sì" : "no"));
        cableAz = cable.has("az") && !cable.isNull("az") ? cable.optDouble("az") : Double.NaN;
        line.append("\nSet: ").append(cable.has("refAz") && !cable.isNull("refAz")
                ? String.format(Locale.ITALY, "AZ %.1f°", cable.optDouble("refAz")) : "non fatto");
        String startAz = cable.optString("obsStartAz", "");
        if (!startAz.isEmpty()) {
            line.append("\nInizio osservazione ").append(cable.optString("obsTarget", ""))
                    .append(": AZ ").append(startAz)
                    .append(" · ALT ").append(cable.optString("obsStartAlt", "—"));
        }
        String extra = cable.optString("activity", "");
        if (extra.isEmpty()) extra = cable.optString("lastResult", "");
        if (!extra.isEmpty()) line.append("\n").append(extra);
        textCable.setText(line);
        checkCableAuto.setChecked(cable.optBoolean("auto", true));
        cableBusy = cable.optBoolean("busy", false);
        double az = cable.optDouble("az", Double.NaN);
        double alt = cable.optDouble("alt", Double.NaN);
        if (!Double.isNaN(prevAz) && !Double.isNaN(az)
                && (Math.abs(az - prevAz) > 0.05 || Math.abs(alt - prevAlt) > 0.05)) {
            motionBusyUntil = System.currentTimeMillis() + 7_000L;
        }
        prevAz = az;
        prevAlt = alt;
    }

    /** Ultimi movimenti registrati dall'Helper (≥ 0.8.50), il più recente per primo. */
    private void showMotionLog(@Nullable JSONArray log) {
        if (textMotionLog == null) return;
        if (log == null) {
            textMotionLog.setText("Registro non disponibile: aggiorna Vespera Helper (≥ 0.8.50).");
            return;
        }
        if (log.length() == 0) {
            textMotionLog.setText("Nessun movimento registrato.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < log.length(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(log.optString(i));
        }
        textMotionLog.setText(sb);
    }

    /** Stop immediato di AZ e ALT: nessuna conferma, funziona anche con un comando in corso. */
    private void sendAzStop() {
        if (adb == null) return;
        textLog.setText("Stop movimento…");
        adb.sendLine("cmd|telescope|azStop", 60_000L, (ok, msg) -> postUi(() -> {
            textLog.setText(msg);
            Toast.makeText(requireContext(), ok ? "Stop inviato" : "Stop non riuscito",
                    Toast.LENGTH_SHORT).show();
            pullState();
        }));
    }

    private void sendCableLine(String line) {
        if (adb == null) return;
        textLog.setText("Invio " + line + "…");
        adb.sendLine(line, 30_000L, (ok, msg) -> postUi(() -> {
            textLog.setText(msg);
            pullState();
        }));
    }

    private double cableAz = Double.NaN;

    private void confirmCableZero() {
        if (!isAdded()) return;
        String here = Double.isNaN(cableAz) ? "La posizione attuale"
                : String.format(Locale.ITALY, "AZ %.1f°", cableAz);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Set posizione iniziale")
                .setMessage("Confermi che ora il cavo di alimentazione è dritto (non attorcigliato)?\n\n"
                        + here + " diventa il riferimento: il conteggio del cavo riparte da 0 e a fine "
                        + "osservazione il telescopio torna qui.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Set", (d, w) -> sendCableLine("cmd|cable|set"))
                .show();
    }

    /** Muove AZ o ALT: {@code kind} "rel" (± gradi scritti) o "abs" (posizione), cmd|telescope|motor. */
    private void confirmMotorMove(String kind) {
        if (!isAdded()) return;
        String axis = radioAxisAlt != null && radioAxisAlt.isChecked() ? "ALT" : "AZ";
        String name = "AZ".equals(axis) ? "azimut" : "elevazione";
        double deg;
        try {
            deg = Double.parseDouble(editAzStep.getText().toString().trim()
                    .replace(',', '.').replace("+", "").replace('−', '-'));
        } catch (NumberFormatException e) {
            deg = Double.NaN;
        }
        String what;
        String line;
        String invalid = null;
        if ("abs".equals(kind)) {
            double lo = "AZ".equals(axis) ? 0.0 : -90.0;
            double hi = "AZ".equals(axis) ? 360.0 : 90.0;
            if (Double.isNaN(deg) || deg < lo || deg > hi) {
                invalid = String.format(Locale.ITALY, "Assoluto: per %s usa da %.0f° a %.0f°.", axis, lo, hi);
            }
            what = String.format(Locale.ITALY, "Porta l'%s (%s) a %.1f°.", name, axis, deg);
            line = String.format(Locale.US, "motor|%s|abs|%.2f", axis, deg);
        } else {
            if (Double.isNaN(deg) || deg == 0 || Math.abs(deg) > 180) {
                invalid = "Relativo: da ±0,1° a ±180° (es. +5 o −10).";
            }
            what = String.format(Locale.ITALY, "Muovi l'%s (%s) di %+.1f° dalla posizione attuale.", name, axis, deg);
            line = String.format(Locale.US, "motor|%s|rel|%.2f", axis, deg);
        }
        if (invalid != null) {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Movimento")
                    .setMessage(invalid)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (telescopeBusy || motionBusy()) {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Comando telescopio")
                    .setMessage("Il telescopio è in movimento o c'è un comando in corso: aspetta che finisca.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String warn = "ALT".equals(axis)
                ? "\n\nATTENZIONE: alzare il braccio di giorno può puntare il telescopio verso il Sole "
                        + "e danneggiare il sensore. Il firmware non fa controlli in questo comando."
                : "";
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Conferma: Movimento")
                .setMessage(what + "\nIl telescopio si muove davvero: controlla che nulla ostacoli il movimento."
                        + warn + "\n\nPuoi fermarlo con «Stop movimento».\n\nInviare il comando al telescopio?")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Muovi", (d, w) -> {
                    motionBusyUntil = System.currentTimeMillis() + 10_000L;
                    sendTelescope(line, "Movimento");
                })
                .show();
    }

    private void sendTelescope(String action, String label) {
        String line = "cmd|telescope|" + action;
        if (("init".equals(action) || "resume".equals(action))
                && DevicePrefs.hasSite(requireContext())) {
            try {
                JSONObject site = new JSONObject();
                site.put("lat", DevicePrefs.getLat(requireContext()));
                site.put("lon", DevicePrefs.getLon(requireContext()));
                line += "|" + site;
            } catch (Exception ignored) {
            }
        }
        long wait = ("init".equals(action) || "resume".equals(action)) ? 150_000L
                : 45_000L;
        telescopeBusy = true;
        updateTelButtons();
        textLog.setText("Invio " + label + "…");
        adb.sendLine(line, wait, (ok, msg) -> postUi(() -> {
            telescopeBusy = false;
            updateTelButtons();
            textLog.setText(msg);
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(ok ? label : label + ": non riuscito")
                    .setMessage(ok
                            ? label + ": comando accettato dal telescopio.\n\n" + msg
                            : telescopeErrorText(msg))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            if (ok) refreshStatus();
        }));
    }

    /** Spiega in italiano gli errori più comuni (come tel_error_text su Windows). */
    public static String telescopeErrorText(String ack) {
        String low = ack == null ? "" : ack.toLowerCase(Locale.ROOT);
        String hint;
        if (low.contains("no_site")) {
            hint = "Posizione dell'osservatorio sconosciuta.\nImpostala in Impostazioni (o in Sistema dell'Helper) e aggiorna Vespera Helper sul Pi (≥ 0.8.26).";
        } else if (low.contains("status_unavailable")) {
            hint = "L'Helper non raggiunge il Vespera: controlla il Wi‑Fi del telescopio (tab Connessioni).";
        } else if (low.contains("auth")) {
            hint = "Il Vespera non accetta i comandi firmati dall'Helper (autenticazione).";
        } else if (low.contains("init_not_ready")) {
            hint = "L'inizializzazione non è ancora finita: riprova tra poco.";
        } else if (low.contains("unknown|azrelative")) {
            hint = "Vespera Helper sul Pi troppo vecchio: aggiornalo (≥ 0.8.44) per ruotare l'azimut.";
        } else if (low.contains("unknown|motor")) {
            hint = "Vespera Helper sul Pi troppo vecchio: aggiornalo (≥ 0.8.48) per il movimento manuale.";
        } else if (low.contains("angle_out_of_range")) {
            hint = "Posizione fuori dai limiti: AZ 0…360°, ALT −90…90°.";
        } else if (low.contains("angle_too_large") || low.contains("invalid_angle")) {
            hint = "Angolo non valido: da 0,1° a 180° per comando.";
        } else if (low.contains("not_calibrated")) {
            hint = "Il motore non è calibrato: fai prima l'Init.";
        } else if (low.contains("|busy")) {
            hint = "C'è già un movimento in corso: aspetta o premi «Stop movimento».";
        } else if (low.contains("nessuna risposta")) {
            hint = "Nessuna risposta dall'Helper sul Pi: è avviato? (tab Connessioni → Aggiorna stato).";
        } else {
            hint = "Il telescopio ha rifiutato il comando.";
        }
        return hint + "\n\nRisposta: " + ack;
    }

    private void postUi(Runnable r) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(r);
    }
}