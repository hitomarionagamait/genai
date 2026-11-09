package com.hito.genai.jira;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utilitas nama versi: v2.15.0 -> v2.15, dan urutan versi alami (v2.9.0 < v2.15.0). */
public final class VersionNames {

    private static final Pattern MAJOR_MINOR = Pattern.compile("^([vV]?\\d+\\.\\d+)(?:\\.\\d+.*)?$");

    private VersionNames() {
    }

    /** v2.15.0 -> v2.15 ; v2.15.1 -> v2.15 ; v2.15 tetap ; bentuk lain dikembalikan apa adanya. */
    public static String shortOf(String v) {
        Matcher m = MAJOR_MINOR.matcher(v == null ? "" : v.trim());
        return m.matches() ? m.group(1) : (v == null ? "" : v.trim());
    }

    /** Urutan alami per segmen angka; huruf dibandingkan tanpa memperhatikan kapitalisasi. */
    public static int compare(String a, String b) {
        String[] pa = a.replaceFirst("^[vV]", "").split("[._ -]");
        String[] pb = b.replaceFirst("^[vV]", "").split("[._ -]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String x = i < pa.length ? pa[i] : "0", y = i < pb.length ? pb[i] : "0";
            boolean nx = x.matches("\\d+"), ny = y.matches("\\d+");
            int c = (nx && ny) ? Long.compare(Long.parseLong(x), Long.parseLong(y)) : x.compareToIgnoreCase(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
