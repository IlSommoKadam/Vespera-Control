package com.vaonis.vesperacontrol.catalog;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Nomi comuni italiano/inglese → identificatore che SESAME risolve. */
final class SkyNames {

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    private static final String[][] PHRASES = {
            {"testa di cavallo", "horsehead"},
            {"nord america", "north america"},
            {"sette sorelle", "seven sisters"},
            {"doppio ammasso", "double cluster"},
            {"stella polare", "polaris"},
            {"nebulosa", "nebula"},
            {"galassia", "galaxy"},
            {"ammasso", "cluster"},
            {"stella", "star"},
            {"pianeta", "planet"},
            {"orione", "orion"},
            {"pleiadi", "pleiades"},
            {"granchio", "crab"},
            {"aquila", "eagle"},
            {"cigno", "swan"},
            {"laguna", "lagoon"},
            {"velo", "veil"},
            {"rosetta", "rosette"},
            {"manubrio", "dumbbell"},
            {"anello", "ring"},
            {"vortice", "whirlpool"},
            {"girandola", "pinwheel"},
            {"ercole", "hercules"},
            {"triangolo", "triangulum"},
            {"cavallo", "horse"},
            {"giove", "jupiter"},
            {"saturno", "saturn"},
            {"marte", "mars"},
            {"venere", "venus"},
            {"luna", "moon"},
            {"sirio", "sirius"},
    };

    private static final String[] STOP = {
            "di", "del", "della", "delle", "dei", "degli", "of", "the", "da", "in",
            "sul", "sulla", "nebula", "nebulosa", "galaxy", "galassia", "cluster",
            "ammasso", "star", "stella", "object", "oggetto", "open", "aperto",
            "globular", "globulare", "planet", "pianeta"
    };

    static {
        alias("orione", "Orion Nebula");
        alias("nebulosa di orione", "Orion Nebula");
        alias("nebulosa orione", "Orion Nebula");
        alias("orion", "Orion Nebula");
        alias("orion nebula", "Orion Nebula");
        alias("andromeda", "Andromeda");
        alias("galassia di andromeda", "Andromeda");
        alias("galassia andromeda", "Andromeda");
        alias("andromeda galaxy", "Andromeda");
        alias("pleiadi", "Pleiades");
        alias("pleiades", "Pleiades");
        alias("sette sorelle", "Pleiades");
        alias("seven sisters", "Pleiades");
        alias("nebulosa granchio", "Crab Nebula");
        alias("granchio", "Crab Nebula");
        alias("crab nebula", "Crab Nebula");
        alias("nebulosa anello", "Ring Nebula");
        alias("anello", "Ring Nebula");
        alias("ring nebula", "Ring Nebula");
        alias("nebulosa manubrio", "Dumbbell");
        alias("manubrio", "Dumbbell");
        alias("dumbbell", "Dumbbell");
        alias("dumbbell nebula", "Dumbbell");
        alias("nebulosa laguna", "Lagoon Nebula");
        alias("laguna", "Lagoon Nebula");
        alias("lagoon nebula", "Lagoon Nebula");
        alias("nebulosa trifida", "Trifid Nebula");
        alias("trifida", "Trifid Nebula");
        alias("trifid nebula", "Trifid Nebula");
        alias("nebulosa aquila", "Eagle Nebula");
        alias("aquila", "Eagle Nebula");
        alias("eagle nebula", "Eagle Nebula");
        alias("nebulosa omega", "Omega Nebula");
        alias("omega nebula", "Omega Nebula");
        alias("nebulosa cigno", "Omega Nebula");
        alias("nord america", "North America Nebula");
        alias("nebulosa nord america", "North America Nebula");
        alias("north america nebula", "North America Nebula");
        alias("nebulosa velo", "Veil Nebula");
        alias("velo", "Veil Nebula");
        alias("veil nebula", "Veil Nebula");
        alias("nebulosa rosetta", "Rosette Nebula");
        alias("rosetta", "Rosette Nebula");
        alias("rosette nebula", "Rosette Nebula");
        alias("testa di cavallo", "Horsehead Nebula");
        alias("horsehead", "Horsehead Nebula");
        alias("horsehead nebula", "Horsehead Nebula");
        alias("galassia vortice", "Whirlpool Galaxy");
        alias("vortice", "Whirlpool Galaxy");
        alias("whirlpool", "Whirlpool Galaxy");
        alias("whirlpool galaxy", "Whirlpool Galaxy");
        alias("galassia sombrero", "Sombrero Galaxy");
        alias("sombrero", "Sombrero Galaxy");
        alias("sombrero galaxy", "Sombrero Galaxy");
        alias("galassia girandola", "Pinwheel Galaxy");
        alias("girandola", "Pinwheel Galaxy");
        alias("pinwheel", "Pinwheel Galaxy");
        alias("pinwheel galaxy", "Pinwheel Galaxy");
        alias("ammasso di ercole", "Hercules Cluster");
        alias("ercole", "Hercules Cluster");
        alias("hercules cluster", "Hercules Cluster");
        alias("galassia triangolo", "Triangulum Galaxy");
        alias("triangolo", "Triangulum Galaxy");
        alias("triangulum", "Triangulum Galaxy");
        alias("triangulum galaxy", "Triangulum Galaxy");
        alias("doppio ammasso", "Double Cluster");
        alias("double cluster", "Double Cluster");
        alias("sirio", "Sirius");
        alias("sirius", "Sirius");
        alias("betelgeuse", "Betelgeuse");
        alias("aldebaran", "Aldebaran");
        alias("polare", "Polaris");
        alias("stella polare", "Polaris");
        alias("polaris", "Polaris");
        alias("vega", "Vega");
        alias("giove", "Jupiter");
        alias("jupiter", "Jupiter");
        alias("saturno", "Saturn");
        alias("saturn", "Saturn");
        alias("marte", "Mars");
        alias("mars", "Mars");
        alias("venere", "Venus");
        alias("venus", "Venus");
        alias("luna", "Moon");
        alias("moon", "Moon");
    }

    private SkyNames() {
    }

    /** Nome SESAME se la frase intera è un alias noto, altrimenti null. */
    static String exact(String query) {
        String key = normalize(query);
        if (key.isEmpty()) return null;
        String hit = ALIASES.get(key);
        if (hit != null) return hit;
        return ALIASES.get(normalize(toEnglish(query)));
    }

    /** Parola più lunga utile per una ricerca parziale (inglese). */
    static String stem(String query) {
        String english = toEnglish(query);
        String[] tokens = normalize(english).split(" ");
        String best = "";
        for (String token : tokens) {
            if (token.length() < 3 || isStop(token)) continue;
            if (token.length() > best.length()) best = token;
        }
        return best;
    }

    /** Alias il cui nome comincia con lo stem (androm → Andromeda). */
    static List<String> aliasTargetsStartingWith(String stem) {
        List<String> out = new ArrayList<>();
        String needle = normalize(stem);
        if (needle.length() < 3) return out;
        for (Map.Entry<String, String> entry : ALIASES.entrySet()) {
            if (!entry.getKey().startsWith(needle)) continue;
            if (!out.contains(entry.getValue())) out.add(entry.getValue());
            if (out.size() >= 6) break;
        }
        return out;
    }

    static String toEnglish(String query) {
        String text = " " + normalize(query) + " ";
        for (String[] phrase : PHRASES) {
            text = text.replace(" " + phrase[0] + " ", " " + phrase[1] + " ");
        }
        return text.trim();
    }

    static String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            out.append(c);
        }
        return out.toString().toLowerCase(Locale.ITALY).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static void alias(String key, String sesame) {
        ALIASES.put(normalize(key), sesame);
    }

    private static boolean isStop(String token) {
        for (String stop : STOP) {
            if (stop.equals(token)) return true;
        }
        return false;
    }
}
