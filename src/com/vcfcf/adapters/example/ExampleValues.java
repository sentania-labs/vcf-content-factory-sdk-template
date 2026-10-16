package com.vcfcf.adapters.example;

import java.util.regex.Pattern;

/**
 * Value checks for publishing (issue #10). No VCF Ops SDK and no
 * framework-jar dependency, so the plain-main tests exercise it directly.
 */
final class ExampleValues {

    private ExampleValues() {}

    private static final Pattern PLAIN_NUMBER =
            Pattern.compile("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?");

    /**
     * The number in {@code raw} (the text of a JSON field, as
     * {@code SimpleJson.asString()} returns it), or {@code null} when the
     * field is absent, JSON null, not a number, or not finite.
     *
     * <p>{@code SimpleJson.asDouble()} answers {@code 0.0} for all of those,
     * which would publish a missing value as a real measurement of zero.
     * Use this, through {@code metricIfPresent}, instead.
     */
    static Double numericOrNull(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        // Double.parseDouble also takes "NaN", "Infinity", hex ("0x1p3") and a
        // trailing type suffix ("42d", "1f"); only plain decimal numbers count.
        // Same check as UniFiValues.number.
        if (!PLAIN_NUMBER.matcher(t).matches()) return null;
        double d;
        try {
            d = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
        return Double.isFinite(d) ? d : null;
    }
}
