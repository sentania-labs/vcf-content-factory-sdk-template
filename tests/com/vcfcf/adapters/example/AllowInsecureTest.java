package com.vcfcf.adapters.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Issue #8: certificate validation is on by default, only an explicit
 * "true" turns it off, and the configure WARN is present exactly while it
 * is off. Run from the repo root (ci/run_java_tests.sh).
 */
public final class AllowInsecureTest {

    public static void main(String[] args) throws Exception {
        strictParse();
        warningOnlyWhileInsecure();
        describeDefaultIsFalse();
        System.out.println("AllowInsecureTest: all assertions passed");
    }

    static void strictParse() {
        for (String v : new String[] {"true", "TRUE", "True"}) {
            T.check(ExampleConfig.parseAllowInsecure(v), v + " is insecure");
        }
        for (String v : new String[] {null, "", " ", "false", "yes", "1",
                "on", " true", "true ", "truee"}) {
            T.check(!ExampleConfig.parseAllowInsecure(v),
                    "<" + v + "> keeps validation on");
        }
        T.check(!new ExampleConfig("h", null, "u", "p", null).allowInsecure,
                "an absent identifier validates");
        T.check(new ExampleConfig("h", null, "u", "p", "true").allowInsecure,
                "an explicit true still works after upgrade");
    }

    static void warningOnlyWhileInsecure() {
        T.eq(null, new ExampleConfig("h", null, "u", "p", null).insecureWarning(),
                "no WARN while validating");
        String w = new ExampleConfig("h", null, "u", "secret-pw", "true")
                .insecureWarning();
        T.check(w != null && w.contains("h:443"), "WARN names the address");
        T.check(!w.contains("secret-pw"), "WARN carries no password");
    }

    /** The shipped default, read from describe.xml at the repo root. */
    static void describeDefaultIsFalse() throws Exception {
        String xml = Files.readString(Path.of("describe.xml"));
        Matcher m = Pattern.compile("<ResourceIdentifier\\s+key=\"allowInsecure\"[^>]*>")
                .matcher(xml);
        T.check(m.find(), "describe.xml declares allowInsecure");
        T.check(m.group().contains("default=\"false\""),
                "describe.xml defaults allowInsecure to false: " + m.group());
    }
}
