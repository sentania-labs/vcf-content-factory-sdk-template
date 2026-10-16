package com.vcfcf.adapters.example;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * How a failed read is classified (issue #13). No VCF Ops SDK and no
 * framework-jar dependency, so the plain-main tests exercise it directly.
 *
 * <p>Every check walks the cause chain (cycle safe): the framework's HTTP
 * and retry layers can wrap the meaningful exception, so it is rarely the
 * outermost one. The children (UniFi, Synology) extend this with
 * authentication-refusal checks; add yours here when your target has them.
 */
final class ExampleFailures {

    private ExampleFailures() {}

    /**
     * Whether {@code t} means the target could not be reached, so the
     * resource is DOWN (the target is down) rather than ERROR (something is
     * wrong with the adapter, its credentials or its data). True when the
     * cause chain holds a {@link java.net.ConnectException}, a
     * {@link java.net.http.HttpTimeoutException} (which includes
     * {@link java.net.http.HttpConnectTimeoutException}), a
     * {@link java.net.SocketTimeoutException}, an
     * {@link java.net.UnknownHostException} or a
     * {@link java.net.NoRouteToHostException}. Certificate failures, HTTP
     * error statuses, unreadable payloads and anything unexpected stay ERROR.
     */
    static boolean isUnreachable(Throwable t) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            if (c instanceof java.net.ConnectException
                    || c instanceof java.net.http.HttpTimeoutException
                    || c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.UnknownHostException
                    || c instanceof java.net.NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }
}
