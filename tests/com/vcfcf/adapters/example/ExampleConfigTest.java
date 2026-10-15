package com.vcfcf.adapters.example;

/**
 * Example plain-main test: ExampleConfig defaults and URL shape. Replace
 * with tests for your own config and parsing classes; keep them free of
 * SDK and framework-jar imports so they run on a plain JDK in CI. Run from
 * the repo root (ci/run_java_tests.sh).
 */
public final class ExampleConfigTest {

    public static void main(String[] args) {
        portDefaults();
        allowInsecureOnlyOnTrue();
        System.out.println("ExampleConfigTest: all assertions passed");
    }

    static void portDefaults() {
        T.eq("443", new ExampleConfig("h", null, "u", "p", null).port,
                "absent port defaults to 443");
        T.eq("443", new ExampleConfig("h", "", "u", "p", null).port,
                "empty port defaults to 443");
        T.eq("https://h:8443",
                new ExampleConfig("h", "8443", "u", "p", null).baseUrl(),
                "baseUrl carries the explicit port");
    }

    static void allowInsecureOnlyOnTrue() {
        T.check(new ExampleConfig("h", null, "u", "p", "true").allowInsecure,
                "true turns validation off");
        for (String v : new String[] {null, "", "false", "yes", "1"}) {
            T.check(!new ExampleConfig("h", null, "u", "p", v).allowInsecure,
                    "<" + v + "> keeps validation on");
        }
    }
}
