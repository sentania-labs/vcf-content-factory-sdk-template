package com.vcfcf.adapters.example;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;

/**
 * Issue #13: unreachable failures map to DOWN, directly or wrapped, and
 * everything else stays ERROR. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class FailureMappingTest {

    public static void main(String[] args) {
        unreachableDirect();
        unreachableWrapped();
        reachableStaysError();
        cycleSafe();
        System.out.println("FailureMappingTest: all assertions passed");
    }

    static Throwable[] unreachable() {
        return new Throwable[] {
            new ConnectException("refused"),
            new UnknownHostException("nas.lab"),
            new NoRouteToHostException("no route"),
            new HttpConnectTimeoutException("connect timed out"),
            new HttpTimeoutException("request timed out"),
            new SocketTimeoutException("read timed out"),
        };
    }

    static void unreachableDirect() {
        for (Throwable t : unreachable()) {
            T.check(ExampleFailures.isUnreachable(t),
                    t.getClass().getSimpleName() + " is DOWN");
        }
    }

    static void unreachableWrapped() {
        for (Throwable t : unreachable()) {
            Throwable wrapped = new RuntimeException("retry exhausted",
                    new IOException("GET /api/items failed", t));
            T.check(ExampleFailures.isUnreachable(wrapped),
                    "wrapped " + t.getClass().getSimpleName() + " is DOWN");
        }
    }

    static void reachableStaysError() {
        T.check(!ExampleFailures.isUnreachable(
                new IOException("GET /api/items returned HTTP 500")),
                "an HTTP error status is ERROR");
        T.check(!ExampleFailures.isUnreachable(
                new SSLHandshakeException("PKIX path building failed")),
                "a certificate failure is ERROR");
        T.check(!ExampleFailures.isUnreachable(
                new IllegalStateException("unreadable payload")),
                "an unreadable payload is ERROR");
        T.check(!ExampleFailures.isUnreachable(null), "null is ERROR");
    }

    static void cycleSafe() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        T.check(!ExampleFailures.isUnreachable(b), "a cause cycle terminates");
    }
}
