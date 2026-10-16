package com.vcfcf.adapters.example;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Issue #12: a body under the cap is read whole; a body over the cap is
 * marked over the cap (not failed, so not retried) and cancels the
 * transfer, also when Content-Length says so up front; a non-200 body is
 * cancelled unread; and only JSON content types pass. Run from the repo
 * root (ci/run_java_tests.sh).
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
        HttpResponse.BodySubscriber<BoundedBody.Body> s =
                new BoundedBody(10).apply(info(200, Map.of()));
        Sub sub = new Sub();
        s.onSubscribe(sub);
        s.onNext(bytes("{\"a\":"));
        s.onNext(bytes("1}"));
        s.onComplete();
        BoundedBody.Body body = s.getBody().toCompletableFuture().get();
        T.check(!body.overCap, "under the cap");
        T.eq("{\"a\":1}", body.text, "body read whole");
        T.check(!sub.cancelled, "not cancelled");
        // exactly at the cap is allowed
        s = new BoundedBody(4).apply(info(200, Map.of()));
        s.onSubscribe(new Sub());
        s.onNext(bytes("abcd"));
        s.onComplete();
        T.eq("abcd", s.getBody().toCompletableFuture().get().text, "exactly the cap");
    }

    static void overCapFailsAndCancels() throws Exception {
        HttpResponse.BodySubscriber<BoundedBody.Body> s =
                new BoundedBody(4).apply(info(200, Map.of()));
        Sub sub = new Sub();
        s.onSubscribe(sub);
        s.onNext(bytes("abc"));
        s.onNext(bytes("de"));
        s.onNext(bytes("more"));   // ignored after the failure
        s.onComplete();            // ignored after the failure
        expectOverCap(s, "over the cap");
        T.check(sub.cancelled, "the transfer is cancelled");
    }

    static void declaredLengthOverCapFailsUnread() throws Exception {
        HttpResponse.BodySubscriber<BoundedBody.Body> s = new BoundedBody(4).apply(
                info(200, Map.of("Content-Length", List.of("5000000000"))));
        Sub sub = new Sub();
        s.onSubscribe(sub);
        expectOverCap(s, "declared length over the cap");
        T.check(sub.cancelled, "cancelled before reading");
        T.eq(0L, sub.requested, "no byte requested");
    }

    /** Review W1: a non-200 body is cancelled unread, not drained. */
    static void nonOkBodyDiscarded() throws Exception {
        for (int status : new int[] {500, 503, 401, 302}) {
            HttpResponse.BodySubscriber<BoundedBody.Body> s =
                    new BoundedBody(4).apply(info(status, Map.of()));
            Sub sub = new Sub();
            s.onSubscribe(sub);
            T.check(sub.cancelled, status + ": the transfer is cancelled on subscribe");
            T.eq(0L, sub.requested, status + ": no byte requested");
            T.check(s.getBody().toCompletableFuture().isDone(),
                    status + ": answered at once, without waiting for the body");
            T.eq(null, s.getBody().toCompletableFuture().get(),
                    status + ": the answer is null");
        }
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

    /**
     * Over the cap completes normally with the marker (never exceptionally):
     * a failed body would become an IOException from send(), which the
     * framework RetryPolicy retries, fetching the oversized body again.
     */
    static void expectOverCap(HttpResponse.BodySubscriber<BoundedBody.Body> s,
            String what) throws Exception {
        java.util.concurrent.CompletableFuture<BoundedBody.Body> f =
                s.getBody().toCompletableFuture();
        T.check(f.isDone() && !f.isCompletedExceptionally(),
                what + ": completes normally, so the send is not retried");
        BoundedBody.Body b = f.get();
        T.check(b.overCap, what + ": marked over the cap");
        T.eq(null, b.text, what + ": no text kept");
    }
}
