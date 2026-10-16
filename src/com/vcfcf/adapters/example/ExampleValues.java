package com.vcfcf.adapters.example;

/**
 * Value checks for publishing (issue #10). No VCF Ops SDK and no
 * framework-jar dependency, so the plain-main tests exercise it directly.
 */
final class ExampleValues {

    private ExampleValues() {}

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
        double d;
        try {
            d = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return Double.isFinite(d) ? d : null;
    }
}
