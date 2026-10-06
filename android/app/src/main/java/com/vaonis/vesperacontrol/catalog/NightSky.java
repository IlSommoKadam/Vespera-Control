package com.vaonis.vesperacontrol.catalog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Visibilità della notte corrente al sito. Un unico giudizio ogni 15 minuti
 * che combina meteo (nubi/pioggia) e, se c'è un oggetto, la sua altezza:
 * vale sempre il fattore peggiore dei due.
 * Livelli: 3 ottima (blu), 2 buona (verde), 1 scarsa (giallo), 0 non visibile (rosso).
 */
public final class NightSky {

    public static final int NONE = -1;
    public static final int NOT_VISIBLE = 0;
    public static final int POOR = 1;
    public static final int GOOD = 2;
    public static final int GREAT = 3;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALY);
    private static final int STEP_MIN = 15;
    private static final int HEADLINE_MIN = 30;

    /** Una riga del rapporto; {@code level} ≥ 0 vuol dire icona colorata davanti. */
    public static final class Line {
        public final int level;
        public final String text;

        Line(int level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    private NightSky() {
    }

    /**
     * @param objectName nome dell'oggetto, oppure null per il solo cielo
     */
    public static List<Line> report(double latDeg, double lonDeg,
                                    String objectName, double raDeg, double decDeg) {
        boolean hasObject = objectName != null;
        ZoneId zone = ZoneId.systemDefault();
        Map<LocalDateTime, Integer> weather = null;
        Weather w = weatherCached(latDeg, lonDeg);
        if (w != null) {
            weather = w.levels;
            if (w.zone != null) zone = w.zone;
        }

        ZonedDateTime start = nightStart(zone);
        List<Slot> slots = new ArrayList<>();
        double maxAlt = -90;
        for (int attempt = 0; attempt < 2; attempt++) {
            slots.clear();
            maxAlt = -90;
            ZonedDateTime end = start.plusDays(1);
            for (ZonedDateTime cursor = start; cursor.isBefore(end); cursor = cursor.plusMinutes(STEP_MIN)) {
                long utc = cursor.toInstant().toEpochMilli();
                double[] sun = sunRaDec(utc);
                double sunAlt = SkyCatalog.altAz(latDeg, lonDeg, sun[0], sun[1], utc)[0];
                if (sunAlt >= -6) continue;
                int sky = GREAT;
                if (weather != null) {
                    Integer v = weather.get(cursor.toLocalDateTime().truncatedTo(ChronoUnit.HOURS));
                    sky = v == null ? GREAT : v;
                }
                int high = GREAT;
                if (hasObject) {
                    double alt = SkyCatalog.altAz(latDeg, lonDeg, raDeg, decDeg, utc)[0];
                    if (alt > maxAlt) maxAlt = alt;
                    high = altitudeLevel(alt);
                }
                slots.add(new Slot(cursor, sky, high));
            }
            // Notte già finita (mattina): mostra la prossima.
            boolean over = !slots.isEmpty()
                    && !slots.get(slots.size() - 1).when.plusMinutes(STEP_MIN).isAfter(ZonedDateTime.now(zone));
            if (!over) break;
            start = start.plusDays(1);
        }

        List<Line> lines = new ArrayList<>();
        if (slots.isEmpty()) {
            lines.add(new Line(NONE, "Stanotte il cielo non diventa abbastanza buio."));
            return lines;
        }
        lines.add(new Line(NONE, "Notte " + CLOCK.format(slots.get(0).when) + "–"
                + CLOCK.format(slots.get(slots.size() - 1).when.plusMinutes(STEP_MIN))));

        int[] minutes = new int[4];
        for (Slot s : slots) minutes[s.level()] += STEP_MIN;
        int best = NOT_VISIBLE;
        for (int level = GREAT; level >= POOR; level--) {
            if (minutes[level] >= HEADLINE_MIN) {
                best = level;
                break;
            }
        }
        String who = hasObject ? (objectName.trim().isEmpty() ? "Oggetto" : objectName.trim().replaceAll("\\s+", " ")) : "Cielo";
        String head = who + " stanotte: " + (best == NOT_VISIBLE ? "non visibile" : "al meglio " + grade(best));
        if (hasObject && maxAlt > -90) head += String.format(Locale.ITALY, " · max %.0f°", maxAlt);
        lines.add(new Line(best, head));

        for (int level = GREAT; level >= NOT_VISIBLE; level--) {
            final int wanted = level;
            String r = ranges(slots, s -> s.level() == wanted);
            if (!r.isEmpty()) lines.add(new Line(level, label(level) + "  " + r));
        }

        if (weather == null) {
            lines.add(new Line(NONE, "Meteo non raggiungibile: conta solo l'altezza dell'oggetto."));
        } else if (hasObject) {
            String clouds = ranges(slots, s -> s.sky < GOOD && s.sky <= s.high);
            String low = ranges(slots, s -> s.high < GOOD && s.high <= s.sky);
            if (!clouds.isEmpty() || !low.isEmpty()) lines.add(new Line(NONE, "Perché:"));
            if (!clouds.isEmpty()) lines.add(new Line(NONE, "  nuvole/pioggia  " + clouds));
            if (!low.isEmpty()) lines.add(new Line(NONE, "  oggetto basso  " + low));
        }
        return lines;
    }

    private static final class Weather {
        Map<LocalDateTime, Integer> levels;
        /** nubi % e probabilità di pioggia % per ora (ora del sito). */
        Map<LocalDateTime, int[]> raw;
        ZoneId zone;
    }

    // ------------------------------------------------------------------ Preferiti

    /** Un oggetto preferito con il suo giudizio per una notte. */
    public static final class Rank {
        public final String name;
        public final String typeCode;
        public final double raDeg;
        public final double decDeg;
        /** Livello migliore tenuto almeno 30 minuti (0..3). */
        public final int best;
        /** Minuti pesati: ottima×3 + buona×2 + scarsa×1. */
        public final int score;
        public final int bestMinutes;
        public final double maxAlt;
        public final String bestRanges;

        Rank(String name, String typeCode, double raDeg, double decDeg, int best, int score,
             int bestMinutes, double maxAlt, String bestRanges) {
            this.name = name;
            this.typeCode = typeCode;
            this.raDeg = raDeg;
            this.decDeg = decDeg;
            this.best = best;
            this.score = score;
            this.bestMinutes = bestMinutes;
            this.maxAlt = maxAlt;
            this.bestRanges = bestRanges;
        }

        /** "NGC 6888 · ottima 2h15 · max 87°" */
        public String headline() {
            String grade = best == NOT_VISIBLE ? "non visibile" : grade(best) + " " + duration(bestMinutes);
            return String.format(Locale.ITALY, "%s · %s · max %.0f°", name, grade, maxAlt);
        }
    }

    /** Classifica dei preferiti per una notte. */
    public static final class Ranking {
        public final LocalDate night;
        public final String nightLine;
        public final boolean weather;
        public final List<Rank> rows;

        Ranking(LocalDate night, String nightLine, boolean weather, List<Rank> rows) {
            this.night = night;
            this.nightLine = nightLine;
            this.weather = weather;
            this.rows = rows;
        }
    }

    /** Oggetto da classificare: nome, tipo, RA, Dec. */
    public static final class Item {
        public final String name;
        public final String typeCode;
        public final double raDeg;
        public final double decDeg;

        public Item(String name, String typeCode, double raDeg, double decDeg) {
            this.name = name == null ? "" : name;
            this.typeCode = typeCode == null ? "" : typeCode;
            this.raDeg = raDeg;
            this.decDeg = decDeg;
        }
    }

    /** Data (sera) della notte in corso o della prossima, se quella di stanotte è finita. */
    public static LocalDate defaultNight(double latDeg, double lonDeg) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();
        if (now.getHour() >= 12) return today;
        long utc = now.toInstant().toEpochMilli();
        double[] sun = sunRaDec(utc);
        double sunAlt = SkyCatalog.altAz(latDeg, lonDeg, sun[0], sun[1], utc)[0];
        return sunAlt < -6 ? today.minusDays(1) : today;
    }

    /**
     * Classifica gli oggetti per la notte che inizia la sera di {@code night}:
     * prima il livello migliore, poi i minuti pesati, poi l'altezza massima.
     * Oltre i 16 giorni del meteo conta solo l'altezza.
     */
    public static Ranking rank(double latDeg, double lonDeg, List<Item> items, LocalDate night) {
        ZoneId zone = ZoneId.systemDefault();
        Weather w = weatherCached(latDeg, lonDeg);
        Map<LocalDateTime, Integer> weather = w == null ? null : w.levels;
        if (w != null && w.zone != null) zone = w.zone;
        ZonedDateTime start = night.atTime(12, 0).atZone(zone);
        ZonedDateTime end = start.plusDays(1);
        List<ZonedDateTime> dark = new ArrayList<>();
        List<Integer> sky = new ArrayList<>();
        boolean weatherSeen = false;
        for (ZonedDateTime cursor = start; cursor.isBefore(end); cursor = cursor.plusMinutes(STEP_MIN)) {
            long utc = cursor.toInstant().toEpochMilli();
            double[] sun = sunRaDec(utc);
            if (SkyCatalog.altAz(latDeg, lonDeg, sun[0], sun[1], utc)[0] >= -6) continue;
            Integer v = weather == null ? null
                    : weather.get(cursor.toLocalDateTime().truncatedTo(ChronoUnit.HOURS));
            if (v != null) weatherSeen = true;
            dark.add(cursor);
            sky.add(v == null ? GREAT : v);
        }
        String nightLine = dark.isEmpty()
                ? "Il cielo non diventa abbastanza buio."
                : "Notte " + CLOCK.format(dark.get(0)) + "–"
                + CLOCK.format(dark.get(dark.size() - 1).plusMinutes(STEP_MIN));
        List<Rank> rows = new ArrayList<>();
        for (Item item : items) {
            List<Slot> slots = new ArrayList<>();
            double maxAlt = -90;
            for (int i = 0; i < dark.size(); i++) {
                long utc = dark.get(i).toInstant().toEpochMilli();
                double alt = SkyCatalog.altAz(latDeg, lonDeg, item.raDeg, item.decDeg, utc)[0];
                if (alt > maxAlt) maxAlt = alt;
                slots.add(new Slot(dark.get(i), sky.get(i), altitudeLevel(alt)));
            }
            int[] minutes = new int[4];
            for (Slot s : slots) minutes[s.level()] += STEP_MIN;
            int best = NOT_VISIBLE;
            for (int level = GREAT; level >= POOR; level--) {
                if (minutes[level] >= HEADLINE_MIN) {
                    best = level;
                    break;
                }
            }
            final int wanted = best;
            String ranges = best == NOT_VISIBLE ? "" : ranges(slots, s -> s.level() == wanted);
            int score = minutes[GREAT] * 3 + minutes[GOOD] * 2 + minutes[POOR];
            rows.add(new Rank(item.name, item.typeCode, item.raDeg, item.decDeg, best, score,
                    best == NOT_VISIBLE ? 0 : minutes[best], maxAlt, ranges));
        }
        Collections.sort(rows, (a, b) -> {
            if (a.best != b.best) return b.best - a.best;
            if (a.score != b.score) return b.score - a.score;
            return Double.compare(b.maxAlt, a.maxAlt);
        });
        return new Ranking(night, nightLine, weatherSeen, rows);
    }

    // ------------------------------------------------------------------ Piano della notte

    /** 60 minuti minimi per oggetto. */
    public static final int PLAN_PERIOD_MIN = 60;
    public static final int PLAN_PERIOD_IDEAL = 75;
    private static final int PLAN_SAMPLE_MIN = 5;

    public static final String SKY_SUNNY = "sereno";
    public static final String SKY_FEW = "poco_nuvoloso";
    public static final String SKY_CLOUDY = "nuvoloso";
    public static final String SKY_RAIN = "pioggia";

    /** Cielo del periodo: pioggia se probabilità ≥ 40%, nuvoloso con nubi ≥ 50%,
     * poco nuvoloso con nubi ≥ 20%, altrimenti sereno. Come sky.sky_kind su Windows. */
    public static String skyKind(int cloud, int pop) {
        if (pop >= 40) return SKY_RAIN;
        if (cloud >= 50) return SKY_CLOUDY;
        if (cloud >= 20) return SKY_FEW;
        return SKY_SUNNY;
    }

    /** "☁ Nuvoloso · nubi 75% · pioggia 10%" oppure "meteo n.d.". */
    public static String skyText(JSONObject sky) {
        if (sky == null) return "meteo n.d.";
        String kind = sky.optString("kind");
        String head;
        switch (kind) {
            case SKY_SUNNY: head = "☀ Sereno"; break;
            case SKY_FEW: head = "⛅ Poco nuvoloso"; break;
            case SKY_CLOUDY: head = "☁ Nuvoloso"; break;
            case SKY_RAIN: head = "☂ Pioggia"; break;
            default: head = "?";
        }
        return head + " · nubi " + sky.optInt("cloud") + "% · pioggia " + sky.optInt("pop") + "%";
    }

    private static double[] alts(JSONObject period, int o) {
        JSONArray all = period.optJSONArray("alts");
        JSONArray a = all == null ? null : all.optJSONArray(o);
        if (a == null) return new double[]{-90, -90, -90};
        return new double[]{a.optDouble(0, -90), a.optDouble(1, -90), a.optDouble(2, -90)};
    }

    /** Media dell'altezza, penalizzata se scende sotto 30°; -1000 se non sale mai a 15°. */
    private static double periodScore(double[] a) {
        if (a[2] < 15) return -1000;
        return a[0] - 2.0 * Math.max(0, 30 - a[1]);
    }

    /** Assegnazione a costo minimo (righe ≤ colonne): colonna di ogni riga. */
    private static int[] hungarian(double[][] cost) {
        int n = cost.length;
        int m = n == 0 ? 0 : cost[0].length;
        double[] u = new double[n + 1];
        double[] v = new double[m + 1];
        int[] p = new int[m + 1];
        int[] way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[m + 1];
            java.util.Arrays.fill(minv, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[m + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= m; j++) {
                    if (used[j]) continue;
                    double cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] out = new int[n];
        java.util.Arrays.fill(out, -1);
        for (int j = 1; j <= m; j++) if (p[j] != 0) out[p[j] - 1] = j - 1;
        return out;
    }

    private static void combos(int v, int k, int from, List<Integer> cur, List<List<Integer>> out) {
        if (out.size() >= 300) return;
        if (cur.size() == k) {
            out.add(new ArrayList<>(cur));
            return;
        }
        for (int i = from; i < v; i++) {
            cur.add(i);
            combos(v, k, i + 1, cur, out);
            cur.remove(cur.size() - 1);
        }
    }

    /**
     * Proposta automatica: ogni oggetto visibile riceve lo stesso numero di periodi attivi (±1),
     * nei periodi dove è più alto; poi scambi che riducono i cambi di oggetto senza perdere quota.
     * I periodi spenti propongono l'oggetto migliore. Scrive "proposed" e "choice".
     * Come sky.plan_propose su Windows.
     */
    public static void propose(JSONObject plan) {
        try {
            JSONArray periods = plan.optJSONArray("periods");
            JSONArray items = plan.optJSONArray("items");
            int k = periods == null ? 0 : periods.length();
            int m = items == null ? 0 : items.length();
            if (k == 0) return;
            if (m == 0) {
                for (int i = 0; i < k; i++) {
                    periods.getJSONObject(i).put("proposed", -1).put("choice", -1);
                }
                return;
            }
            double[][] score = new double[k][m];
            for (int i = 0; i < k; i++) {
                for (int o = 0; o < m; o++) score[i][o] = periodScore(alts(periods.getJSONObject(i), o));
            }
            int[] pick = new int[k];
            List<Integer> active = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                pick[i] = bestOf(score[i]);
                if (periods.getJSONObject(i).optBoolean("enabled")) active.add(i);
            }
            List<Integer> visible = new ArrayList<>();
            for (int o = 0; o < m; o++) {
                for (int i : active) {
                    if (score[i][o] > -1000) {
                        visible.add(o);
                        break;
                    }
                }
            }
            if (!active.isEmpty() && !visible.isEmpty()) {
                int e = active.size();
                int v = visible.size();
                List<List<Integer>> extras = new ArrayList<>();
                combos(v, e % v, 0, new ArrayList<>(), extras);
                double bestTotal = 0;
                int[] best = null;
                for (List<Integer> extra : extras) {
                    List<Integer> cols = new ArrayList<>();
                    for (int c = 0; c < v; c++) {
                        int copies = e / v + (extra.contains(c) ? 1 : 0);
                        for (int r = 0; r < copies; r++) cols.add(visible.get(c));
                    }
                    double[][] cost = new double[e][cols.size()];
                    for (int r = 0; r < e; r++) {
                        for (int c = 0; c < cols.size(); c++) cost[r][c] = -score[active.get(r)][cols.get(c)];
                    }
                    int[] colOf = hungarian(cost);
                    double total = 0;
                    int[] chosen = new int[e];
                    for (int r = 0; r < e; r++) {
                        chosen[r] = cols.get(colOf[r]);
                        total += score[active.get(r)][chosen[r]];
                    }
                    if (best == null || total > bestTotal + 1e-9) {
                        best = chosen;
                        bestTotal = total;
                    }
                }
                for (int r = 0; r < e; r++) {
                    int i = active.get(r);
                    int o = best[r];
                    pick[i] = score[i][o] > -1000 ? o : bestOf(score[i]);
                }
                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (int x = 0; x < e; x++) {
                        for (int y = x + 1; y < e; y++) {
                            int i = active.get(x);
                            int j = active.get(y);
                            int a = pick[i];
                            int b = pick[j];
                            if (a == b || a < 0 || b < 0) continue;
                            double before = score[i][a] + score[j][b];
                            double after = score[i][b] + score[j][a];
                            if (after < before - 4.0 || Math.min(score[i][b], score[j][a]) <= -1000) continue;
                            int old = switches(pick, active);
                            pick[i] = b;
                            pick[j] = a;
                            if (switches(pick, active) < old) {
                                changed = true;
                            } else {
                                pick[i] = a;
                                pick[j] = b;
                            }
                        }
                    }
                }
            }
            for (int i = 0; i < k; i++) periods.getJSONObject(i).put("proposed", pick[i]).put("choice", pick[i]);
        } catch (Exception ignored) {
        }
    }

    private static int bestOf(double[] row) {
        int best = 0;
        for (int o = 1; o < row.length; o++) if (row[o] > row[best]) best = o;
        return row[best] > -1000 ? best : -1;
    }

    private static int switches(int[] pick, List<Integer> active) {
        int n = 0;
        for (int r = 1; r < active.size(); r++) if (pick[active.get(r)] != pick[active.get(r - 1)]) n++;
        return n;
    }

    /** Ricava "steps" (per l'Helper, periodi consecutivi uguali uniti) e "lines" dai periodi. */
    public static JSONObject finalizePlan(JSONObject plan) {
        try {
            JSONArray items = plan.optJSONArray("items");
            JSONArray periods = plan.optJSONArray("periods");
            int m = items == null ? 0 : items.length();
            int k = periods == null ? 0 : periods.length();
            JSONArray steps = new JSONArray();
            JSONArray lines = new JSONArray();
            ZoneId zone = ZoneId.systemDefault();
            for (int i = 0; i < k; i++) {
                JSONObject p = periods.getJSONObject(i);
                long start = p.optLong("start");
                long end = p.optLong("end");
                String span = CLOCK.format(java.time.Instant.ofEpochMilli(start).atZone(zone)) + "–"
                        + CLOCK.format(java.time.Instant.ofEpochMilli(end).atZone(zone));
                JSONObject sky = p.optJSONObject("sky");
                String head = span + "  " + skyText(sky) + "  →  ";
                int o = p.optInt("choice", -1);
                boolean on = p.optBoolean("enabled");
                if (!on || o < 0 || o >= m) {
                    lines.put(new JSONArray().put(NONE).put(head + (!on ? "saltato" : "nessun oggetto")));
                    continue;
                }
                JSONObject item = items.getJSONObject(o);
                double[] a = alts(p, o);
                if (a[2] < 15) {
                    lines.put(new JSONArray().put(NONE).put(head + item.optString("name")
                            + " non visibile: periodo non ripreso"));
                    continue;
                }
                int level = altitudeLevel(a[0]); // solo altezza: il meteo è nel testo della riga
                lines.put(new JSONArray().put(level).put(head + String.format(Locale.ITALY,
                        "%s · %.0f° (min %.0f°)", item.optString("name"), a[0], a[1])));
                JSONObject last = steps.length() == 0 ? null : steps.getJSONObject(steps.length() - 1);
                if (last != null && last.optString("name").equals(item.optString("name"))
                        && last.optLong("end") == start) {
                    last.put("end", end);
                    last.put("level", Math.min(last.optInt("level"), level));
                    continue;
                }
                JSONObject step = new JSONObject();
                step.put("start", start);
                step.put("end", end);
                step.put("name", item.optString("name"));
                step.put("type", item.optString("type"));
                step.put("ra", item.optDouble("ra"));
                step.put("dec", item.optDouble("dec"));
                step.put("level", level);
                steps.put(step);
            }
            if (k == 0) {
                lines.put(new JSONArray().put(NONE).put("Nessuna ora buia rimasta per questa notte."));
            } else if (steps.length() == 0) {
                lines.put(new JSONArray().put(NONE).put("Nessun periodo attivo con un oggetto: il piano è vuoto."));
            }
            plan.put("steps", steps);
            plan.put("lines", lines);
            plan.put("totals", totals(plan));
        } catch (Exception ignored) {
        }
        return plan;
    }

    private static final String[] SKY_ORDER = {SKY_SUNNY, SKY_FEW, SKY_CLOUDY, SKY_RAIN};

    /** Nome della visibilità per altezza: ottima ≥ 50°, buona ≥ 30°, scarsa ≥ 15°, bassa sotto. */
    private static String altGrade(int level) {
        if (level >= GREAT) return "ottima";
        if (level == GOOD) return "buona";
        if (level == POOR) return "scarsa";
        return "bassa <15°";
    }

    private static String skyLabel(String kind) {
        switch (kind) {
            case SKY_SUNNY: return "☀ sereno";
            case SKY_FEW: return "⛅ poco nuvoloso";
            case SKY_CLOUDY: return "☁ nuvoloso";
            case SKY_RAIN: return "☂ pioggia";
            default: return kind;
        }
    }

    /**
     * Tempo di osservazione per oggetto dai periodi attivi in cui sale sopra 15°.
     * {@code levels} = minuti per visibilità, SOLO dall'altezza media (ottima ≥ 50°, buona ≥ 30°,
     * scarsa ≥ 15°); il meteo è a parte in {@code sky} (minuti per sereno / poco nuvoloso /
     * nuvoloso / pioggia) con {@code cloud} = nubi medie % e {@code pop} = pioggia max %.
     * {@code visible} = minuti della notte in cui l'oggetto sta a ≥ 30° di media (solo altezza).
     * Come sky.plan_totals su Windows.
     */
    public static JSONArray totals(JSONObject plan) {
        JSONArray out = new JSONArray();
        try {
            JSONArray items = plan.optJSONArray("items");
            JSONArray periods = plan.optJSONArray("periods");
            int m = items == null ? 0 : items.length();
            int[] minutes = new int[m];
            int[] visible = new int[m];
            int[][] levels = new int[m][GREAT + 1];
            List<Map<String, Integer>> skies = new ArrayList<>();
            double[] cloudSum = new double[m];
            int[] cloudMin = new int[m];
            int[] popMax = new int[m];
            for (int o = 0; o < m; o++) skies.add(new HashMap<>());
            for (int i = 0; periods != null && i < periods.length(); i++) {
                JSONObject p = periods.getJSONObject(i);
                int mins = (int) Math.round((p.optLong("end") - p.optLong("start")) / 60000.0);
                for (int k = 0; k < m; k++) if (alts(p, k)[0] >= 30) visible[k] += mins;
                int o = p.optInt("choice", -1);
                if (!p.optBoolean("enabled") || o < 0 || o >= m) continue;
                double[] a = alts(p, o);
                if (a[2] < 15) continue;
                minutes[o] += mins;
                levels[o][altitudeLevel(a[0])] += mins;
                JSONObject sky = p.optJSONObject("sky");
                if (sky != null) {
                    skies.get(o).merge(sky.optString("kind"), mins, Integer::sum);
                    cloudSum[o] += sky.optInt("cloud") * (double) mins;
                    cloudMin[o] += mins;
                    popMax[o] = Math.max(popMax[o], sky.optInt("pop"));
                }
            }
            for (int o = 0; o < m; o++) {
                JSONObject lv = new JSONObject();
                for (int level = GREAT; level >= NOT_VISIBLE; level--) {
                    if (levels[o][level] > 0) lv.put(String.valueOf(level), levels[o][level]);
                }
                JSONObject sk = new JSONObject();
                for (Map.Entry<String, Integer> e : skies.get(o).entrySet()) sk.put(e.getKey(), e.getValue());
                JSONObject row = new JSONObject().put("name", items.getJSONObject(o).optString("name"))
                        .put("minutes", minutes[o]).put("levels", lv).put("sky", sk).put("visible", visible[o]);
                if (cloudMin[o] > 0) {
                    row.put("cloud", (int) Math.round(cloudSum[o] / cloudMin[o]));
                    row.put("pop", popMax[o]);
                }
                out.put(row);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String levelsText(int[] levels) {
        List<String> parts = new ArrayList<>();
        for (int level = GREAT; level >= NOT_VISIBLE; level--) {
            if (levels[level] > 0) parts.add(altGrade(level) + " " + duration(levels[level]));
        }
        return String.join(", ", parts);
    }

    private static String skyMinutesText(Map<String, Integer> sky, int cloud, int pop) {
        List<String> parts = new ArrayList<>();
        for (String kind : SKY_ORDER) {
            Integer v = sky.get(kind);
            if (v != null && v > 0) parts.add(skyLabel(kind) + " " + duration(v));
        }
        if (parts.isEmpty()) return "meteo n.d.";
        List<String> extra = new ArrayList<>();
        if (cloud >= 0) extra.add("nubi medie " + cloud + "%");
        if (pop > 0) extra.add("pioggia max " + pop + "%");
        return "meteo: " + String.join(", ", parts)
                + (extra.isEmpty() ? "" : " (" + String.join(", ", extra) + ")");
    }

    /**
     * Per oggetto: "NGC 7380 · 6h08 · visibilità: ottima 4h00, buona 2h08 · meteo: ☁ nuvoloso 6h08
     * (nubi medie 78%) · alto ≥30° in tutta la notte 7h00", poi "Totale piano …" con le stesse
     * divisioni. Visibilità = solo altezza; il meteo è a parte. Come sky.totals_text.
     */
    public static List<String> totalsText(JSONArray totals) {
        List<String> lines = new ArrayList<>();
        int sum = 0;
        int[] allLevels = new int[GREAT + 1];
        Map<String, Integer> allSky = new HashMap<>();
        double cloudSum = 0;
        int cloudMin = 0;
        int popMax = 0;
        for (int i = 0; totals != null && i < totals.length(); i++) {
            JSONObject t = totals.optJSONObject(i);
            if (t == null) continue;
            int mins = t.optInt("minutes");
            sum += mins;
            StringBuilder sb = new StringBuilder(t.optString("name")).append(" · ")
                    .append(mins > 0 ? duration(mins) : "non in piano");
            JSONObject lv = t.optJSONObject("levels");
            int[] levels = new int[GREAT + 1];
            for (int level = GREAT; level >= NOT_VISIBLE && lv != null; level--) {
                levels[level] = lv.optInt(String.valueOf(level), 0);
                allLevels[level] += levels[level];
            }
            Map<String, Integer> sky = new HashMap<>();
            JSONObject sk = t.optJSONObject("sky");
            int skyMin = 0;
            for (String kind : SKY_ORDER) {
                int v = sk == null ? 0 : sk.optInt(kind, 0);
                if (v > 0) {
                    sky.put(kind, v);
                    allSky.merge(kind, v, Integer::sum);
                    skyMin += v;
                }
            }
            if (mins > 0) {
                String lt = levelsText(levels);
                if (!lt.isEmpty()) sb.append(" · visibilità: ").append(lt);
                sb.append(" · ").append(skyMinutesText(sky, t.has("cloud") ? t.optInt("cloud") : -1,
                        t.optInt("pop", 0)));
            }
            if (t.has("cloud")) {
                cloudSum += t.optInt("cloud") * (double) skyMin;
                cloudMin += skyMin;
            }
            popMax = Math.max(popMax, t.optInt("pop", 0));
            if (t.has("visible")) {
                sb.append(" · alto ≥30° in tutta la notte ").append(duration(t.optInt("visible")));
            }
            lines.add(sb.toString());
        }
        if (lines.size() > 1) {
            StringBuilder sb = new StringBuilder("Totale piano ").append(duration(sum));
            String lt = levelsText(allLevels);
            if (!lt.isEmpty()) sb.append(" · visibilità: ").append(lt);
            if (sum > 0) {
                sb.append(" · ").append(skyMinutesText(allSky,
                        cloudMin > 0 ? (int) Math.round(cloudSum / cloudMin) : -1, popMax));
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    /** Voce del menu oggetti di un periodo: "★ NGC 7000 · 62° (min 48°)". */
    public static String optionText(JSONObject plan, JSONObject period, int o) {
        JSONArray items = plan.optJSONArray("items");
        JSONObject item = items == null ? null : items.optJSONObject(o);
        String name = item == null ? "?" : item.optString("name");
        double[] a = alts(period, o);
        String star = o == period.optInt("proposed", -1) ? "★ " : "";
        if (a[2] < 15) return String.format(Locale.ITALY, "%s%s · non visibile (max %.0f°)", star, name, a[2]);
        return String.format(Locale.ITALY, "%s%s · %.0f° (min %.0f°)", star, name, a[0], a[1]);
    }

    /**
     * Piano per la notte che inizia la sera di {@code night} con gli oggetti scelti.
     * La notte (sole sotto -6°, da adesso se è già iniziata) è divisa in periodi tutti uguali:
     * un multiplo del numero di oggetti, lunghi circa 75 min e mai meno di 60. Ogni periodo ha
     * il suo meteo (sereno / poco nuvoloso / nuvoloso / pioggia, con nubi e pioggia %), l'altezza
     * di ogni oggetto e la proposta automatica; nuvoloso e pioggia partono spenti.
     * L'utente accende/spegne i periodi e cambia l'oggetto (poi {@link #finalizePlan}).
     * Come sky.plan_night su Windows. JSON: title, night, created, items, periods
     * [{start,end,sky{kind,cloud,pop,level},enabled,alts[[media,min,max]],proposed,choice}],
     * steps [{start,end,name,type,ra,dec,level}], lines [[level,text]], weather, parkAtEnd.
     */
    public static JSONObject plan(double latDeg, double lonDeg, List<Item> items, LocalDate night) {
        ZoneId zone = ZoneId.systemDefault();
        Weather w = weatherCached(latDeg, lonDeg);
        if (w != null && w.zone != null) zone = w.zone;
        ZonedDateTime noon = night.atTime(12, 0).atZone(zone);
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime first = null;
        ZonedDateTime last = null;
        for (ZonedDateTime c = noon; c.isBefore(noon.plusDays(1)); c = c.plusMinutes(PLAN_SAMPLE_MIN)) {
            long utc = c.toInstant().toEpochMilli();
            double[] sun = sunRaDec(utc);
            if (SkyCatalog.altAz(latDeg, lonDeg, sun[0], sun[1], utc)[0] < -6) {
                if (first == null) first = c;
                last = c;
            }
        }
        JSONObject out = new JSONObject();
        try {
            JSONArray periods = new JSONArray();
            boolean seen = false;
            int m = Math.max(1, items.size());
            if (first != null) {
                ZonedDateTime start = first;
                ZonedDateTime end = last.plusMinutes(PLAN_SAMPLE_MIN);
                if (now.isAfter(start)) start = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
                double total = ChronoUnit.SECONDS.between(start, end) / 60.0;
                if (total >= PLAN_PERIOD_MIN / 2.0) {
                    int perObj = Math.max(1, (int) Math.round(total / (m * PLAN_PERIOD_IDEAL)));
                    int k = m * perObj;
                    while (k > 1 && total / k < PLAN_PERIOD_MIN) {
                        perObj = perObj > 1 ? perObj - 1 : 0;
                        k = perObj > 0 ? m * perObj : k - 1;
                    }
                    k = Math.max(1, k);
                    double length = total / k;
                    for (int i = 0; i < k; i++) {
                        ZonedDateTime p0 = start.plusMinutes(Math.round(i * length));
                        ZonedDateTime p1 = i == k - 1 ? end : start.plusMinutes(Math.round((i + 1) * length));
                        List<ZonedDateTime> samples = new ArrayList<>();
                        for (ZonedDateTime t = p0; t.isBefore(p1); t = t.plusMinutes(PLAN_SAMPLE_MIN)) samples.add(t);
                        int cloudSum = 0;
                        int cloudN = 0;
                        int popMax = 0;
                        for (ZonedDateTime t : samples) {
                            int[] v = w == null || w.raw == null ? null
                                    : w.raw.get(t.toLocalDateTime().truncatedTo(ChronoUnit.HOURS));
                            if (v == null) continue;
                            cloudSum += v[0];
                            cloudN++;
                            popMax = Math.max(popMax, v[1]);
                        }
                        JSONObject p = new JSONObject();
                        p.put("start", p0.toInstant().toEpochMilli());
                        p.put("end", p1.toInstant().toEpochMilli());
                        boolean enabled = true;
                        if (cloudN > 0) {
                            seen = true;
                            int cloud = Math.round(cloudSum / (float) cloudN);
                            String kind = skyKind(cloud, popMax);
                            p.put("sky", new JSONObject().put("kind", kind).put("cloud", cloud)
                                    .put("pop", popMax).put("level", weatherLevel(cloud, popMax)));
                            enabled = kind.equals(SKY_SUNNY) || kind.equals(SKY_FEW);
                        }
                        JSONArray alts = new JSONArray();
                        for (Item item : items) {
                            double sum = 0;
                            double lo = 90;
                            double hi = -90;
                            for (ZonedDateTime t : samples) {
                                double a = SkyCatalog.altAz(latDeg, lonDeg, item.raDeg, item.decDeg,
                                        t.toInstant().toEpochMilli())[0];
                                sum += a;
                                lo = Math.min(lo, a);
                                hi = Math.max(hi, a);
                            }
                            double avg = samples.isEmpty() ? -90 : sum / samples.size();
                            alts.put(new JSONArray().put(Math.round(avg * 10) / 10.0)
                                    .put(Math.round(lo * 10) / 10.0).put(Math.round(hi * 10) / 10.0));
                        }
                        p.put("enabled", enabled);
                        p.put("alts", alts);
                        p.put("proposed", -1);
                        p.put("choice", -1);
                        periods.put(p);
                    }
                }
            }
            JSONArray itemsJson = new JSONArray();
            for (Item item : items) {
                itemsJson.put(new JSONObject().put("name", item.name).put("type", item.typeCode)
                        .put("ra", item.raDeg).put("dec", item.decDeg));
            }
            out.put("title", "Piano " + DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALY).format(night));
            out.put("night", night.toString());
            out.put("created", System.currentTimeMillis());
            out.put("items", itemsJson);
            out.put("periods", periods);
            out.put("weather", seen);
            out.put("parkAtEnd", true);
            propose(out);
            for (int i = 0; i < periods.length(); i++) {
                JSONObject p = periods.getJSONObject(i);
                if (p.optInt("proposed", -1) < 0) p.put("enabled", false);
            }
            finalizePlan(out);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String duration(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) return m + " min";
        return m == 0 ? h + "h" : String.format(Locale.ITALY, "%dh%02d", h, m);
    }

    private static Weather cachedWeather;
    private static long cachedAt;
    private static String cachedKey = "";

    /** Meteo 16 giorni con cache di 15 minuti e 2 tentativi; null se non raggiungibile. */
    private static synchronized Weather weatherCached(double latDeg, double lonDeg) {
        String key = String.format(Locale.US, "%.2f,%.2f", latDeg, lonDeg);
        long now = System.currentTimeMillis();
        if (cachedWeather != null && key.equals(cachedKey) && now - cachedAt < 15 * 60_000L) {
            return cachedWeather;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Weather w = fetchWeather(latDeg, lonDeg);
                cachedWeather = w;
                cachedAt = now;
                cachedKey = key;
                return w;
            } catch (Exception ignored) {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return key.equals(cachedKey) ? cachedWeather : null;
    }

    private static Weather fetchWeather(double latDeg, double lonDeg) throws Exception {
        String path = String.format(Locale.US,
                "/v1/forecast?latitude=%f&longitude=%f"
                        + "&hourly=cloud_cover,precipitation_probability"
                        + "&forecast_days=16&timezone=auto",
                latDeg, lonDeg);
        HttpsFetch.Result page = HttpsFetch.get("api.open-meteo.com", path);
        if (page.code < 200 || page.code >= 300) throw new Exception("HTTP " + page.code);
        JSONObject root = new JSONObject(page.body);
        Weather out = new Weather();
        String zoneName = root.optString("timezone", "");
        try {
            out.zone = zoneName.isEmpty() ? null : ZoneId.of(zoneName);
        } catch (Exception ignored) {
            out.zone = null;
        }
        JSONObject hourly = root.optJSONObject("hourly");
        if (hourly == null) throw new Exception("meteo");
        JSONArray times = hourly.optJSONArray("time");
        JSONArray clouds = hourly.optJSONArray("cloud_cover");
        JSONArray rain = hourly.optJSONArray("precipitation_probability");
        if (times == null || clouds == null) throw new Exception("meteo");
        out.levels = new HashMap<>();
        out.raw = new HashMap<>();
        for (int i = 0; i < times.length(); i++) {
            LocalDateTime local = LocalDateTime.parse(times.optString(i));
            int cloud = clouds.optInt(i, 100);
            int pop = rain == null ? 0 : rain.optInt(i, 0);
            out.levels.put(local, weatherLevel(cloud, pop));
            out.raw.put(local, new int[]{cloud, pop});
        }
        return out;
    }

    private static int weatherLevel(int cloud, int pop) {
        if (pop >= 50 || cloud >= 80) return NOT_VISIBLE;
        if (pop >= 30 || cloud >= 50) return POOR;
        if (pop >= 15 || cloud >= 20) return GOOD;
        return GREAT;
    }

    private static int altitudeLevel(double altDeg) {
        if (altDeg >= 50) return GREAT;
        if (altDeg >= 30) return GOOD;
        if (altDeg >= 15) return POOR;
        return NOT_VISIBLE;
    }

    private interface SlotTest {
        boolean test(Slot s);
    }

    private static String ranges(List<Slot> slots, SlotTest test) {
        StringBuilder sb = new StringBuilder();
        ZonedDateTime open = null;
        ZonedDateTime prev = null;
        for (Slot s : slots) {
            if (!test.test(s)) continue;
            if (open == null) {
                open = s.when;
            } else if (ChronoUnit.MINUTES.between(prev, s.when) > STEP_MIN) {
                appendRange(sb, open, prev);
                open = s.when;
            }
            prev = s.when;
        }
        if (open != null) appendRange(sb, open, prev);
        return sb.toString();
    }

    private static void appendRange(StringBuilder sb, ZonedDateTime open, ZonedDateTime last) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(CLOCK.format(open)).append("–").append(CLOCK.format(last.plusMinutes(STEP_MIN)));
    }

    private static ZonedDateTime nightStart(ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime noon = now.truncatedTo(ChronoUnit.DAYS).plusHours(12);
        if (now.isBefore(noon)) noon = noon.minusDays(1);
        return noon;
    }

    /** RA/Dec del sole in gradi (approssimazione Meeus). */
    private static double[] sunRaDec(long utcMs) {
        double jd = utcMs / 86_400_000.0 + 2_440_587.5;
        double d = jd - 2_451_545.0;
        double L = norm360(280.460 + 0.9856474 * d);
        double g = Math.toRadians(norm360(357.528 + 0.9856003 * d));
        double lambda = Math.toRadians(norm360(
                L + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)));
        double eps = Math.toRadians(23.439 - 0.0000004 * d);
        double ra = Math.toDegrees(Math.atan2(
                Math.cos(eps) * Math.sin(lambda), Math.cos(lambda)));
        ra = norm360(ra);
        double dec = Math.toDegrees(Math.asin(Math.sin(eps) * Math.sin(lambda)));
        return new double[]{ra, dec};
    }

    private static double norm360(double deg) {
        double v = deg % 360.0;
        return v < 0 ? v + 360.0 : v;
    }

    private static String grade(int level) {
        if (level >= GREAT) return "ottima";
        if (level == GOOD) return "buona";
        if (level == POOR) return "scarsa";
        return "non visibile";
    }

    private static String label(int level) {
        if (level >= GREAT) return "Ottima";
        if (level == GOOD) return "Buona";
        if (level == POOR) return "Scarsa";
        return "Non visibile";
    }

    private static final class Slot {
        final ZonedDateTime when;
        final int sky;
        final int high;

        Slot(ZonedDateTime when, int sky, int high) {
            this.when = when;
            this.sky = sky;
            this.high = high;
        }

        int level() {
            return Math.min(sky, high);
        }
    }
}
