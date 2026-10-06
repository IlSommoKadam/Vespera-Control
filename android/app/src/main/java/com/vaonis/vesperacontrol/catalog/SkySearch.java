package com.vaonis.vesperacontrol.catalog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ricerca catalogo: sigla esatta, prefisso (M4 → M4, M40…) e nome parziale
 * in italiano o inglese. Un solo risultato si apre subito; più risultati
 * restano una lista.
 */
public final class SkySearch {

    private static final Pattern CATALOG = Pattern.compile(
            "(?i)^(m|ngc|ic|ugc|pgc)\\s*(\\d+)\\s*$");
    private static final Pattern COORD = Pattern.compile(
            "([+-]?\\d+(?:\\.\\d+)?)\\s+([+-]?\\d+(?:\\.\\d+)?)");
    private static final int LIMIT = 12;

    private SkySearch() {
    }

    public static List<SkyCatalog.Hit> find(String query) throws Exception {
        String name = query == null ? "" : query.trim();
        if (name.isEmpty()) throw new Exception("empty");
        if (name.length() > 80) name = name.substring(0, 80);

        String exact = SkyNames.exact(name);
        if (exact != null) {
            SkyCatalog.Hit hit = SkyCatalog.sesameHit(exact);
            if (hit != null) {
                List<SkyCatalog.Hit> one = new ArrayList<>();
                one.add(hit);
                return one;
            }
        }

        Matcher catalog = CATALOG.matcher(name);
        if (catalog.matches()) {
            List<SkyCatalog.Hit> hits = catalogPrefix(catalog.group(1), catalog.group(2));
            if (!hits.isEmpty()) return hits;
            SkyCatalog.Hit hit = SkyCatalog.sesameHit(name);
            if (hit != null) {
                List<SkyCatalog.Hit> one = new ArrayList<>();
                one.add(hit);
                return one;
            }
            throw new Exception("not_found");
        }

        String stem = SkyNames.stem(name);
        if (stem.length() < 3) throw new Exception("short");
        Map<String, SkyCatalog.Hit> merged = new LinkedHashMap<>();
        String english = SkyNames.toEnglish(name);
        add(merged, SkyCatalog.sesameHit(english));
        add(merged, SkyCatalog.sesameHit(name));
        for (String alias : SkyNames.aliasTargetsStartingWith(stem)) {
            add(merged, SkyCatalog.sesameHit(alias));
        }
        for (SkyCatalog.Hit hit : wildcard(stem)) {
            add(merged, hit);
        }
        if (merged.isEmpty()) throw new Exception("not_found");
        List<SkyCatalog.Hit> out = new ArrayList<>(merged.values());
        if (out.size() > LIMIT) return out.subList(0, LIMIT);
        return out;
    }

    private static void add(Map<String, SkyCatalog.Hit> merged, SkyCatalog.Hit hit) {
        if (hit == null || merged.size() >= LIMIT) return;
        String key = SkyCatalog.compactKey(hit.name);
        if (key.isEmpty() || merged.containsKey(key)) return;
        merged.put(key, hit);
    }

    private static List<SkyCatalog.Hit> catalogPrefix(String prefix, String number) throws Exception {
        String tag = prefix.toUpperCase(Locale.US);
        List<SkyCatalog.Hit> twoSpaces = tapLike(tag + "  " + number);
        if (!twoSpaces.isEmpty()) return twoSpaces;
        return tapLike(tag + " " + number);
    }

    private static List<SkyCatalog.Hit> tapLike(String prefix) throws Exception {
        String sql = "SELECT TOP 20 basic.main_id, basic.ra, basic.dec, basic.otype, ident.id "
                + "FROM basic JOIN ident ON ident.oidref = basic.oid "
                + "WHERE ident.id LIKE '" + prefix.replace("'", "") + "%'";
        String path = "/simbad/sim-tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=json&MAXREC=20&QUERY="
                + URLEncoder.encode(sql, StandardCharsets.UTF_8.name());
        HttpsFetch.Result page = HttpsFetch.get("simbad.cds.unistra.fr", path);
        if (page.code < 200 || page.code >= 300) throw new Exception("HTTP " + page.code);
        return parseTap(page.body);
    }

    private static List<SkyCatalog.Hit> parseTap(String json) throws Exception {
        List<SkyCatalog.Hit> out = new ArrayList<>();
        if (json == null || json.isEmpty() || json.charAt(0) != '{') return out;
        JSONObject root = new JSONObject(json);
        JSONArray data = root.optJSONArray("data");
        if (data == null) return out;
        Map<String, SkyCatalog.Hit> uniq = new LinkedHashMap<>();
        for (int i = 0; i < data.length(); i++) {
            JSONArray row = data.optJSONArray(i);
            if (row == null || row.length() < 4) continue;
            String id = row.optString(0, "").trim();
            double ra = row.optDouble(1, Double.NaN);
            double dec = row.optDouble(2, Double.NaN);
            String type = row.optString(3, "");
            String matched = SkyCatalog.cleanId(row.optString(4, ""));
            if (id.isEmpty() || Double.isNaN(ra) || Double.isNaN(dec)) continue;
            String key = SkyCatalog.compactKey(id);
            if (uniq.containsKey(key)) continue;
            // Mostra la sigla cercata (M 44, NGC 6888) invece dell'id principale SIMBAD.
            SkyCatalog.Hit hit = new SkyCatalog.Hit(
                    matched.isEmpty() ? SkyCatalog.cleanId(id) : matched, type, ra, dec);
            if (SkyCatalog.isStarType(type) && SkyCatalog.isDsoId(matched)) {
                SkyCatalog.Hit better = SkyCatalog.sesameHit(matched);
                if (better != null) hit = better;
            }
            if (!uniq.containsKey(key)) uniq.put(key, hit);
            if (uniq.size() >= LIMIT) break;
        }
        out.addAll(uniq.values());
        return out;
    }

    private static List<SkyCatalog.Hit> wildcard(String stem) throws Exception {
        String safe = stem.replaceAll("[^A-Za-z0-9 \\-]", "").trim();
        if (safe.length() < 3) return new ArrayList<>();
        String script = "output console=off script=off\n"
                + "format object \"%MAIN_ID|%COO(d;A D)|%OTYPE(3)|%IDLIST(M,NGC,IC,Sh,LBN,LDN,Ced,vdB,RCW,Gum)\"\n"
                + "set limit 18\n"
                + "query id wildcard NAME " + safe + "*\n";
        String path = "/simbad/sim-script?script="
                + URLEncoder.encode(script, StandardCharsets.UTF_8.name());
        HttpsFetch.Result page = HttpsFetch.get("simbad.cds.unistra.fr", path);
        if (page.code < 200 || page.code >= 300) throw new Exception("HTTP " + page.code);
        return parseScript(page.body);
    }

    private static List<SkyCatalog.Hit> parseScript(String body) {
        List<SkyCatalog.Hit> out = new ArrayList<>();
        if (body == null) return out;
        for (String line : body.split("\n")) {
            String row = line.trim();
            if (row.isEmpty() || row.startsWith(":") || row.startsWith("::")) continue;
            int bar = row.indexOf('|');
            int bar2 = row.indexOf('|', bar + 1);
            if (bar <= 0 || bar2 <= bar) continue;
            String id = row.substring(0, bar).trim();
            String coord = row.substring(bar + 1, bar2).trim();
            int bar3 = row.indexOf('|', bar2 + 1);
            String type = (bar3 < 0 ? row.substring(bar2 + 1) : row.substring(bar2 + 1, bar3)).trim();
            List<String> ids = new ArrayList<>();
            if (bar3 >= 0) {
                for (String part : row.substring(bar3 + 1).split(",")) {
                    if (!part.trim().isEmpty()) ids.add(part.trim());
                }
            }
            if (coord.toLowerCase(Locale.US).contains("no coord")) continue;
            Matcher matcher = COORD.matcher(coord);
            if (!matcher.find()) continue;
            try {
                double ra = Double.parseDouble(matcher.group(1));
                double dec = Double.parseDouble(matcher.group(2));
                if (ra < 0 || ra >= 360 || dec < -90 || dec > 90) continue;
                out.add(SkyCatalog.preferDso(id,
                        new SkyCatalog.Hit(SkyCatalog.cleanId(id), type, ra, dec), ids));
            } catch (NumberFormatException ignored) {
            }
            if (out.size() >= LIMIT) break;
        }
        return out;
    }
}
