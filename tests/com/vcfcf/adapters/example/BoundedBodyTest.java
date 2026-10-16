package com.vcfcf.adapters.example;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

/**
 * Issue #12: a body under the cap is read whole, a body over the cap fails
 * with IOException and cancels the transfer (also when Content-Length says
 * so up front), a non-200 body is never read, and only JSON content types
 * pass. Run from the repo root (ci/run_java_tests.sh).
 */
public final class BoundedBodyTest {

    public static void main(String[] args) throws Exception {
        defaultCapIs8MB();
        underCapReadWhole();
        overCapFailsAndCancels();
        declaredLengthOverCapFailsUnread();
        nonOkBodyDiscarded();
        jsonContentTypes();
        System.out.println("BoundedBodyTest: all assertions passed");
    }

    static void defaultCapIs8MB() {
        T.eq(8L * 1024 * 1024, BoundedBody.DEFAULT_MAX_BYTES, "default cap");
    }

    /** Records demand and cancellation. */
    static final class Sub implements Flow.Subscription {
        long requested;
        boolean cancelled;

        @Override public void request(long n) { requested += n; }
        @Override public void cancel() { cancelled = true; }
    }

    static HttpResponse.ResponseInfo info(int status, Map<String, List<String>> headers) {
        HttpHeaders h = HttpHeaders.of(headers, (k, v) -> true);
        return new HttpResponse.ResponseInfo() {
            @Override public int statusCode() { return status; }
            @Override public HttpHeaders headers() { return h; }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    static List<ByteBuffer> bytes(String s) {
        return List.of(ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)));
    }

    static void underCapReadWhole() throws Exception {
        HttpResponse.BodySubscriber<String> s =
                new BoundedBody(10).apply(info(200, Map.of()));
        Sub sub = new Sub();
        s.onSubscribe(sub);
        s.onNext(bytes("{\"a\":"));
        s.onNext(bytes("1}"));
        s.onComplete();
        T.eq("{\"a\":1}", s.getBody().toCompletableFuture().get(), "body read whole");
        T.check(!sub.cancelled, "not cancelled");
        // exactly at the cap is allowed
        s = new BoundedBody(4).apply(info(200, Map.of()));
        s.onSubscribe(new Sub());
        s.onNext(bytes("abcd"));
        s.onComplete();
        T.eq("abcd", s.getBody().toCompletableFuture().get(), "exactly the cap");
    }

    static void overCapFailsAndCancels() throws Exception {
        HttpResponse.BodySubscriber<String> s =
                new BoundedBody(4).apply(info(200, Map.of()));
        Sub sub = new Sub();
        s.onSubscribe(sub);
        s.onNext(bytes("abc"));
        s.onNext(bytes("de"));
        s.onNext(bytes("more"));   // ignored after the failure
        s.onComplete();            // ignored after the failure
        expectIoFailure(s, "over the cap");
        T.check(sub.cancelled, "the transfer is cancelled");
    }

    static void declaredLengthOverCapFailsUnread() throws Exception {
        HttpResponse.BodySubscriber<String> s = new BoundedBody(4).apply(
                info(200, Map.of("Content-Length", List.of("5000000000"))));
        Sub sub = new Sub();
        s.onSubscribe(sub);
        expectIoFailure(s, "declared length over the cap");
        T.check(sub.cancelled, "cancelled before reading");
        T.eq(0L, sub.requested, "no byte requested");
    }

    static void nonOkBodyDiscarded() throws Exception {
        HttpResponse.BodySubscriber<String> s =
                new BoundedBody(4).apply(info(500, Map.of()));
        Sub sub = new Sub();
        s.onSubscribe(sub);
        s.onNext(bytes("a large HTML error page, far over the cap"));
        s.onComplete();
        T.eq(null, s.getBody().toCompletableFuture().get(),
                "a non-200 body is discarded, not buffered");
    }

    static void jsonContentTypes() {
        for (String ok : new String[] {"application/json",
                "application/json; charset=utf-8", "Application/JSON",
                " application/json ", "application/problem+json",
                "application/vnd.example.v1+json;charset=UTF-8"}) {
            T.check(BoundedBody.isJson(ok), "<" + ok + "> is JSON");
        }
        for (String bad : new String[] {null, "", "text/html",
                "text/html; charset=utf-8", "text/plain", "application/xml",
                "text/json+html", "application/jsonp"}) {
            T.check(!BoundedBody.isJson(bad), "<" + bad + "> is not JSON");
        }
    }

    static void expectIoFailure(HttpResponse.BodySubscriber<String> s, String what)
            throws InterruptedException {
        try {
            s.getBody().toCompletableFuture().get();
            throw new AssertionError(what + ": must fail");
        } catch (ExecutionException e) {
            T.check(e.getCause() instanceof IOException,
                    what + ": fails with IOException, got " + e.getCause());
        }
    }
}
