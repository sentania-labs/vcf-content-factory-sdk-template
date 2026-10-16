package com.vcfcf.adapters.example;

/**
 * One snapshot per collection cycle (issue #9). No VCF Ops SDK and no
 * framework-jar dependency, so the plain-main tests drive it with a
 * counting builder.
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
 * <p>This is the teaching-size version. The children
 * ({@code content/sdk-adapters/unifi} and {@code synology},
 * {@code SnapshotCache}) add what a production pak needs on top: a failure
 * back-off that spans cycles (so a wrong password does not re-login every
 * cycle), a maximum snapshot age for a cycle that never ends, and one WARN
 * per failed build.
 *
 * <p>Thread safety: every method is {@code synchronized}; a build runs
 * under the lock, so only one thread reads the target at a time.
 */
final class CycleCache<S> {

    /** Builds one snapshot (contacts the target). */
    interface Builder<S> {
        S build() throws Exception;
    }

    private S value;
    private Exception failure;

    /** Top of a cycle: the next {@link #get} reads the target again. */
    synchronized void beginCycle() {
        value = null;
        failure = null;
    }

    /**
     * This cycle's snapshot, built on the first call of the cycle.
     *
     * @throws Exception this cycle's build failure, the same instance on
     *                   every call until the next {@link #beginCycle}
     */
    synchronized S get(Builder<S> builder) throws Exception {
        if (value != null) return value;
        if (failure != null) throw failure;
        S built;
        try {
            built = builder.build();
        } catch (InterruptedException e) {
            throw e; // never remembered: the next call may run again
        } catch (Exception e) {
            failure = e;
            throw e;
        }
        if (built == null) {
            failure = new IllegalStateException("snapshot builder returned null");
            throw failure;
        }
        value = built;
        return value;
    }
}
