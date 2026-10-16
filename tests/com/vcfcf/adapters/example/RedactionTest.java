package com.vcfcf.adapters.example;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Issue #15: the password is not a public field and toString() never
 * prints it. Run from the repo root (ci/run_java_tests.sh).
 */
public final class RedactionTest {

    public static void main(String[] args) throws Exception {
        toStringRedacts();
        passwordFieldIsPrivate();
        rejectedValuesNeverEchoed();
        System.out.println("RedactionTest: all assertions passed");
    }

    static void toStringRedacts() {
        ExampleConfig c = new ExampleConfig("nas.lab", "8443", "svc-ops",
                "S3cret-Pw!", "true");
        String s = c.toString();
        T.check(!s.contains("S3cret-Pw!"), "toString never carries the password: " + s);
        T.check(s.contains("password=***"), "toString shows password=***: " + s);
        T.check(s.contains("host=nas.lab"), "host: " + s);
        T.check(s.contains("port=8443"), "port: " + s);
        T.check(s.contains("username=svc-ops"), "username: " + s);
        T.check(s.contains("allowInsecure=true"), "allowInsecure: " + s);
        T.eq("S3cret-Pw!", c.password(), "the accessor still hands it to the auth strategy");
    }

    /**
     * Review B1: a credential pasted into Host or Port is rejected without
     * being copied into the message, which is logged every cycle and shown
     * in Test Connection.
     */
    static void rejectedValuesNeverEchoed() {
        String[][] cases = {
            {"user:S3cret@nas.lab", null},
            {"https://admin:S3cret@nas.lab", null},
            {"https://admin:S3cret@nas.lab/api", null},
            {"admin@S3cret", null},
            {"[admin:S3cret@fd00::10]", null},
            {"S3cret word", null},
            {"Hunter2!pw", null},
            {"p4ss$word", null},
            {"S3cret-", null},
            {"S3cret.999", null},
            {"[S3cret]", null},
            {"nas.lab", "S3cret"},
            {"nas.lab", "S3cret:443"},
        };
        for (String[] c : cases) {
            try {
                new ExampleConfig(c[0], c[1], "u", "p", null);
                throw new AssertionError("must be rejected: host/port case " + c[0]);
            } catch (IllegalArgumentException e) {
                T.check(!e.getMessage().contains("S3cret")
                        && !e.getMessage().contains("Hunter2")
                        && !e.getMessage().contains("p4ss"),
                        "message must not echo the credential: " + e.getMessage());
            }
        }
    }

    static void passwordFieldIsPrivate() throws Exception {
        Field f = ExampleConfig.class.getDeclaredField("password");
        T.check(Modifier.isPrivate(f.getModifiers()), "password field is private");
    }
}
