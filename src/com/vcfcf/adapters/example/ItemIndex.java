package com.vcfcf.adapters.example;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Indexes one payload list by item id. No VCF Ops SDK and no framework-jar
 * dependency, so the plain-main tests exercise it directly.
 */
final class ItemIndex {

    private ItemIndex() {}

    /**
     * The rows by id, in payload order, first row winning for a duplicate id.
     * A row with no id is skipped (an occasional malformed row should not
     * fail the cycle). But a non-empty list in which <b>no</b> row has an id
     * throws: that is schema drift (the id field renamed or moved), and
     * returning an empty index would discover nothing and leave every
     * resource looking healthy with no data. Unreadable is not empty.
     *
     * @param rows  the payload list
     * @param idOf  the row's id, or {@code null} / empty when it has none
     * @param what  what the rows are, for the message (no secrets)
     * @throws IOException when {@code rows} is non-empty and none has an id
     */
    static <T> Map<String, T> byId(List<T> rows, Function<T, String> idOf,
            String what) throws IOException {
        Map<String, T> byId = new LinkedHashMap<>();
        for (T row : rows) {
            String id = idOf.apply(row);
            if (id == null || id.isEmpty()) continue;
            byId.putIfAbsent(id, row);
        }
        if (byId.isEmpty() && !rows.isEmpty()) {
            throw new IOException(what + ": " + rows.size() + " row(s) but none "
                    + "has an id; the payload schema may have changed (treating "
                    + "as unreadable, not empty)");
        }
        return byId;
    }
}
