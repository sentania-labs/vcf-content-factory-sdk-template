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

    static void passwordFieldIsPrivate() throws Exception {
        Field f = ExampleConfig.class.getDeclaredField("password");
        T.check(Modifier.isPrivate(f.getModifiers()), "password field is private");
    }
}
