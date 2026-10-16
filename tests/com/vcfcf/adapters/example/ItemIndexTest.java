package com.vcfcf.adapters.example;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Peer review: an occasional id-less row is skipped, an empty list is an
 * empty index, but a non-empty list with no ids at all (schema drift)
 * throws instead of discovering nothing. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class ItemIndexTest {

    public static void main(String[] args) throws Exception {
        indexesAndSkipsIdless();
        emptyListIsEmpty();
        noIdsAtAllThrows();
        System.out.println("ItemIndexTest: all assertions passed");
    }

    /** Rows are "id=name" strings; "" or "=name" means no id. */
    static String idOf(String row) {
        int eq = row.indexOf('=');
        return eq < 0 ? null : row.substring(0, eq);
    }

    static void indexesAndSkipsIdless() throws Exception {
        Map<String, String> m = ItemIndex.byId(
                Arrays.asList("a=one", "=noid", "nothing", "b=two", "a=dup"),
                ItemIndexTest::idOf, "test");
        T.eq(Arrays.asList("a", "b"), List.copyOf(m.keySet()), "ids in payload order");
        T.eq("a=one", m.get("a"), "first row wins for a duplicate id");
    }

    static void emptyListIsEmpty() throws Exception {
        T.eq(0, ItemIndex.byId(Collections.<String>emptyList(),
                ItemIndexTest::idOf, "test").size(), "no rows, no resources");
    }

    static void noIdsAtAllThrows() {
        try {
            ItemIndex.byId(Arrays.asList("=x", "nothing", "=y"),
                    ItemIndexTest::idOf, "Remote /api/items");
            throw new AssertionError("a list with no ids must throw");
        } catch (IOException e) {
            T.check(e.getMessage().startsWith("Remote /api/items: 3 row(s)"),
                    "message names the source and count: " + e.getMessage());
        }
    }
}
