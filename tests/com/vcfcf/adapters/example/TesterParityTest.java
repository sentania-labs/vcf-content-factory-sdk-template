package com.vcfcf.adapters.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codex P2 on PR #21: Test Connection must validate the payload exactly as
 * the collection cycle does, so it cannot say "Test OK" for a payload (rows
 * with no id after a schema change) that every cycle then rejects.
 *
 * ExampleAdapter needs the SDK and framework jars, which these plain-JDK
 * tests do not have, so this checks the wiring in the source: one parse
 * method, called by both getTester and fetchSnapshot, and the only place
 * ItemIndex.byId is called. The behaviour of that parse (throw on a
 * non-empty list with no ids) is covered by ItemIndexTest. Run from the
 * repo root (ci/run_java_tests.sh).
 */
public final class TesterParityTest {

    private static final Path SRC =
            Path.of("src/com/vcfcf/adapters/example/ExampleAdapter.java");

    public static void main(String[] args) throws Exception {
        String src = Files.readString(SRC);
        T.eq(1, count(src, "ItemIndex.byId("),
                "ItemIndex.byId is called in exactly one place (parseSnapshot)");
        T.check(body(src, "getTester()").contains("parseSnapshot("),
                "getTester validates through parseSnapshot");
        T.check(body(src, "fetchSnapshot()").contains("parseSnapshot("),
                "fetchSnapshot validates through parseSnapshot");
        T.check(body(src, "parseSnapshot(SimpleJson items)").contains("ItemIndex.byId("),
                "parseSnapshot indexes through ItemIndex.byId");
        System.out.println("TesterParityTest: all assertions passed");
    }

    static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    /** The text of the method whose signature contains {@code sig}, brace-matched. */
    static String body(String src, String sig) {
        Matcher m = Pattern.compile(Pattern.quote(sig) + "[^{;]*\\{").matcher(src);
        T.check(m.find(), "method " + sig + " found");
        int depth = 1;
        int i = m.end();
        for (; i < src.length() && depth > 0; i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return src.substring(m.end(), i);
    }
}
