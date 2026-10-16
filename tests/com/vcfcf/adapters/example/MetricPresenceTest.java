package com.vcfcf.adapters.example;

/**
 * Issue #10: a missing, null or non-numeric field yields no metric value
 * (skipped), never a 0.0 sentinel; a real zero is still published. Run from
 * the repo root (ci/run_java_tests.sh).
 */
public final class MetricPresenceTest {

    public static void main(String[] args) {
        missingFieldSkipped();
        numbersKept();
        System.out.println("MetricPresenceTest: all assertions passed");
    }

    static void missingFieldSkipped() {
        // null is what SimpleJson.asString() returns for an absent field or JSON null.
        for (String v : new String[] {null, "", " ", "abc", "true", "{}", "[1]",
                "NaN", "Infinity", "-Infinity"}) {
            T.eq(null, ExampleValues.numericOrNull(v), "<" + v + "> is skipped");
        }
    }

    static void numbersKept() {
        T.eq(0.0, ExampleValues.numericOrNull("0"), "a real zero is published");
        T.eq(0.0, ExampleValues.numericOrNull("0.0"), "a real 0.0 is published");
        T.eq(42.0, ExampleValues.numericOrNull("42"), "integer");
        T.eq(-1.5, ExampleValues.numericOrNull("-1.5"), "negative decimal");
        T.eq(1.0E10, ExampleValues.numericOrNull("1.0E10"),
                "exponent form (how a large Double prints)");
        T.eq(12.0, ExampleValues.numericOrNull(" 12 "), "numeric string, trimmed");
    }
}
