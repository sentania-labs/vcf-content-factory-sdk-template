package com.vcfcf.adapters.example;

import java.util.function.LongSupplier;

/**
 * One snapshot per collection cycle (issue #9). No VCF Ops SDK and no
 * framework-jar dependency, so the plain-main tests drive it with a
 * counting builder and a fake clock.
 *
 * <p>{@link #beginCycle} marks the top of a cycle and forgets the last
 * result. The first {@link #get} of the cycle runs the builder (the one
 * remote read); every later {@code get} in the same cycle answers from
 * that result without contacting the target. So a cycle over N resources
 * makes one read, not N + 1.
 *
 * <p>A failed build is remembered for the rest of its cycle and rethrown,
 * so a target that is down costs one failed read per cycle, not one per
 * resource (each with its own retries and timeouts). An
 * {@link InterruptedException} (Stop Collecting, discard) is never
 * remembered.
 *
 * <p><b>Maximum age (review W2).</b> A result, snapshot or failure, is also
 * dropped once it is {@code maxAgeMs} old (counted from when the build
 * finished), whether or not {@link #beginCycle} ran. This is the safety net
 * for a caller that stops starting cycles. Use half the collection
 * interval: the age counts from when the read finished, so a whole interval
 * could still serve the previous cycle's snapshot to the next cycle.
 *
 * <p>This is the teaching-size version. The children
 * ({@code content/sdk-adapters/unifi} and {@code synology},
 * {@code SnapshotCache}) add what a production pak needs on top: a failure
 * back-off that spans cycles (so a wrong password does not re-login every
 * cycle) and one WARN per failed build.
 *
 * <p>Thread safety: every method is {@code synchronized}; a build runs
 * under the lock, so only one thread reads the target at a time.
 */
final class CycleCache<S> {

    /** Builds one snapshot (contacts the target). */
    interface Builder<S> {
        S build() throws Exception;
    }

    private final long maxAgeMs;
    private final LongSupplier clock;

    private S value;
    private Exception failure;
    private long builtAt;

    /**
     * @param maxAgeMs the oldest result {@link #get} will serve; use half
     *                 the collection interval
     * @param clock    milliseconds, {@code System::currentTimeMillis} in
     *                 production
     */
    CycleCache(long maxAgeMs, LongSupplier clock) {
        if (maxAgeMs <= 0) throw new IllegalArgumentException("maxAgeMs must be > 0");
        this.maxAgeMs = maxAgeMs;
        this.clock = clock;
    }

    /** Top of a cycle: the next {@link #get} reads the target again. */
    synchronized void beginCycle() {
        value = null;
        failure = null;
    }

    /**
     * This cycle's snapshot, built on the first call of the cycle, or again
     * once the last result is {@code maxAgeMs} old.
     *
     * @throws Exception this cycle's build failure, the same instance on
     *                   every call until the next {@link #beginCycle} or
     *                   until it is {@code maxAgeMs} old
     */
    synchronized S get(Builder<S> builder) throws Exception {
        if ((value != null || failure != null)
                && clock.getAsLong() - builtAt >= maxAgeMs) {
            beginCycle(); // too old to be this cycle's reading
        }
        if (value != null) return value;
        if (failure != null) throw failure;
        S built;
        try {
            built = builder.build();
        } catch (InterruptedException e) {
            throw e; // never remembered: the next call may run again
        } catch (Exception e) {
            failure = e;
            builtAt = clock.getAsLong();
            throw e;
        }
        builtAt = clock.getAsLong();
        if (built == null) {
            failure = new IllegalStateException("snapshot builder returned null");
            throw failure;
        }
        value = built;
        return value;
    }
}
