package com.vcfcf.adapters.example;

/**
 * ExampleConfig defaults, URL shape and host/port validation (issue #16).
 * Keep tests free of SDK and framework-jar imports so they run on a plain
 * JDK in CI. Run from the repo root (ci/run_java_tests.sh).
 */
public final class ExampleConfigTest {

    public static void main(String[] args) {
        portDefaults();
        validHosts();
        invalidHosts();
        validPorts();
        invalidPorts();
        System.out.println("ExampleConfigTest: all assertions passed");
    }

    static ExampleConfig cfg(String host, String port) {
        return new ExampleConfig(host, port, "u", "p", null);
    }

    static void portDefaults() {
        T.eq(443, cfg("h", null).port, "absent port defaults to 443");
        T.eq(443, cfg("h", "").port, "empty port defaults to 443");
        T.eq(443, cfg("h", "  ").port, "blank port defaults to 443");
        T.eq("https://h:8443", cfg("h", "8443").baseUrl(),
                "baseUrl carries the explicit port");
        T.eq("https://h:443", cfg("h", null).baseUrl(), "baseUrl default port");
    }

    static void validHosts() {
        T.eq("nas.lab.local", cfg("  nas.lab.local ", null).host, "host is trimmed");
        T.eq("https://nas.lab.local:443", cfg(" nas.lab.local", null).baseUrl(),
                "trimmed host in the URL");
        T.eq("https://10.0.0.1:443", cfg("10.0.0.1", null).baseUrl(), "IPv4");
        T.eq("https://[fd00::10]:8443", cfg("[fd00::10]", "8443").baseUrl(),
                "bracketed IPv6");
        T.eq("https://[::ffff:10.0.0.1]:443", cfg("[::ffff:10.0.0.1]", null).baseUrl(),
                "IPv4-mapped IPv6");
        T.eq("https://my-host:443", cfg("my-host", null).baseUrl(), "single label");
    }

    static void invalidHosts() {
        for (String h : new String[] {null, "", "   ",
                "https://nas.lab", "nas.lab/api", "user@nas.lab", "nas.lab?x=1",
                "nas.lab#frag", "nas lab", "nas.lab:8443", "fd00::10",
                "[fd00::10", "[nas.lab]", "[]", "-nas.lab", "nas-.lab", "nas..lab",
                "nas_lab", "256.1.1.1", "10.0.0.999", "999", "10.1", "nas.123"}) {
            expectInvalid(() -> cfg(h, null), "Host / IP Address", "host <" + h + ">");
        }
    }

    static void validPorts() {
        T.eq(1, cfg("h", "1").port, "port 1");
        T.eq(65535, cfg("h", "65535").port, "port 65535");
        T.eq(8443, cfg("h", " 8443 ").port, "port trimmed");
    }

    static void invalidPorts() {
        for (String p : new String[] {"0", "65536", "-1", "https", "443/api",
                "99999999999", "8443 1"}) {
            expectInvalid(() -> cfg("h", p), "Port", "port <" + p + ">");
        }
    }

    static void expectInvalid(Runnable r, String field, String what) {
        try {
            r.run();
            throw new AssertionError(what + " must be rejected");
        } catch (IllegalArgumentException e) {
            T.check(e.getMessage() != null && e.getMessage().startsWith(field),
                    what + ": message names the field: " + e.getMessage());
        }
    }
}
