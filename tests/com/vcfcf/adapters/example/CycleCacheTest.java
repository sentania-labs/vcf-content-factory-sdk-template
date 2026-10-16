package com.vcfcf.adapters.example;

import java.io.IOException;

/**
 * Issue #9: one snapshot build per cycle however many resources read it,
 * a fresh build after beginCycle, a failure remembered for its cycle only,
 * and an interrupt never remembered. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class CycleCacheTest {

    public static void main(String[] args) throws Exception {
        servedOncePerCycle();
        failureRememberedForItsCycle();
        interruptNotRemembered();
        nullBuildIsAFailure();
        maxAgeWithoutBeginCycle();
        System.out.println("CycleCacheTest: all assertions passed");
    }

    static long now = 1_000_000L;

    static CycleCache<String> newCache() {
        return new CycleCache<>(300_000L, () -> now);
    }

    /** Counts builds; fails while {@code fail} is set. */
    static final class Counting implements CycleCache.Builder<String> {
        int builds;
        Exception fail;

        @Override
        public String build() throws Exception {
            builds++;
            if (fail != null) throw fail;
            return "snapshot-" + builds;
        }
    }

    static void servedOncePerCycle() throws Exception {
        CycleCache<String> cache = newCache();
        Counting b = new Counting();
        cache.beginCycle();
        // rediscover plus 50 per-resource collects in one cycle
        for (int i = 0; i < 51; i++) {
            T.eq("snapshot-1", cache.get(b), "same snapshot all cycle");
        }
        T.eq(1, b.builds, "one remote read per cycle");
        cache.beginCycle();
        T.eq("snapshot-2", cache.get(b), "next cycle reads again");
        T.eq("snapshot-2", cache.get(b), "and serves that read");
        T.eq(2, b.builds, "one read per cycle, two cycles");
    }

    static void failureRememberedForItsCycle() throws Exception {
        CycleCache<String> cache = newCache();
        Counting b = new Counting();
        IOException down = new IOException("target down");
        b.fail = down;
        cache.beginCycle();
        for (int i = 0; i < 5; i++) {
            try {
                cache.get(b);
                throw new AssertionError("a failed build must throw");
            } catch (IOException e) {
                T.check(e == down, "the same failure is rethrown");
            }
        }
        T.eq(1, b.builds, "a down target costs one read per cycle");
        b.fail = null;
        cache.beginCycle();
        T.eq("snapshot-2", cache.get(b), "the next cycle tries again");
    }

    static void interruptNotRemembered() throws Exception {
        CycleCache<String> cache = newCache();
        Counting b = new Counting();
        b.fail = new InterruptedException("discarded");
        cache.beginCycle();
        try {
            cache.get(b);
            throw new AssertionError("an interrupt must propagate");
        } catch (InterruptedException expected) {
            // propagated
        }
        b.fail = null;
        T.eq("snapshot-2", cache.get(b),
                "same cycle, the interrupt was not remembered");
    }

    static void nullBuildIsAFailure() throws Exception {
        CycleCache<String> cache = newCache();
        cache.beginCycle();
        try {
            cache.get(() -> null);
            throw new AssertionError("a null snapshot must not be served");
        } catch (IllegalStateException expected) {
            // a null build is a failure, not an empty snapshot
        }
    }

    /**
     * Review W2: a caller that never calls beginCycle again (for example a
     * child that made needsRediscovery conditional) is not served a snapshot
     * older than the maximum age, and a failure expires the same way.
     */
    static void maxAgeWithoutBeginCycle() throws Exception {
        CycleCache<String> cache = newCache();
        Counting b = new Counting();
        cache.beginCycle();
        T.eq("snapshot-1", cache.get(b), "first read");
        now += 299_999L;
        T.eq("snapshot-1", cache.get(b), "younger than the maximum age: served");
        now += 1L;
        T.eq("snapshot-2", cache.get(b), "at the maximum age: read again, no beginCycle");
        T.eq(2, b.builds, "one rebuild");
        b.fail = new java.io.IOException("down");
        now += 300_000L;
        try {
            cache.get(b);
            throw new AssertionError("must fail");
        } catch (java.io.IOException expected) {
            // remembered
        }
        b.fail = null;
        T.eq(3, b.builds, "the failed read counted");
        now += 300_000L;
        T.eq("snapshot-4", cache.get(b), "an old failure expires too");
    }
}
