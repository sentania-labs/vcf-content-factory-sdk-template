package com.vcfcf.adapters.example;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A response body handler with a size cap (issue #12). Plain JDK
 * ({@code java.net.http}), no VCF Ops SDK and no framework-jar dependency,
 * so the plain-main tests drive it directly.
 *
 * <p>{@code BodyHandlers.ofString()} buffers whatever the target sends, so a
 * misbehaving target or a proxy error page could fill the collector heap,
 * which every adapter on that collector shares. This handler:
 * <ul>
 *   <li>reads a 200 body up to {@code maxBytes}; past that it cancels the
 *       transfer (nothing more is read) and answers a {@link Body} marked
 *       {@linkplain Body#overCap over the cap}, also when
 *       {@code Content-Length} already says the body is too large;</li>
 *   <li>does not read the body of any other status at all: it cancels the
 *       transfer at once and answers {@code null} (the caller reports the
 *       status only, never the body). Draining it instead would hold the
 *       collector thread for as long as a misbehaving target keeps
 *       streaming, because the request timeout stops at the headers.</li>
 * </ul>
 *
 * <p><b>Why a marker, not an exception.</b> The framework's
 * {@code RetryPolicy} retries every {@code IOException} from the send, and
 * {@code HttpClient.send} turns any failure of the body (other than
 * {@code IllegalArgumentException} or {@code SecurityException}) into one.
 * Failing the body here would fetch an oversized response three times. The
 * marker lets the send succeed once; the caller then throws, outside the
 * retry loop (see {@code ExampleApiClient.get}).
 *
 * <p>Raise {@link #DEFAULT_MAX_BYTES} if a real endpoint of yours
 * legitimately returns more; keep one constant per pak.
 */
final class BoundedBody implements HttpResponse.BodyHandler<BoundedBody.Body> {

    /** Default cap: 8 MB. One constant; change it here for your pak. */
    static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;

    private final long maxBytes;

    BoundedBody(long maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be > 0");
        this.maxBytes = maxBytes;
    }

    /** A 200 body: its text, or the mark that it was over the cap and not read. */
    static final class Body {
        /** The body as UTF-8 text; {@code null} when {@link #overCap}. */
        final String text;
        /** The body was larger than the cap; nothing past the cap was read. */
        final boolean overCap;

        private Body(String text, boolean overCap) {
            this.text = text;
            this.overCap = overCap;
        }
    }

    @Override
    public HttpResponse.BodySubscriber<Body> apply(HttpResponse.ResponseInfo info) {
        if (info.statusCode() != 200) {
            // Not needed: the caller reports the status, never the body.
            return new Unread();
        }
        long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1L);
        return new Capped(maxBytes, declared > maxBytes);
    }

    /**
     * Whether a {@code Content-Type} header value is JSON:
     * {@code application/json} or any {@code application/*+json}, with or
     * without parameters such as {@code charset}. Case-insensitive.
     */
    static boolean isJson(String contentType) {
        if (contentType == null) return false;
        String media = contentType;
        int semi = media.indexOf(';');
        if (semi >= 0) media = media.substring(0, semi);
        media = media.trim().toLowerCase(Locale.ROOT);
        return media.equals("application/json")
                || (media.startsWith("application/") && media.endsWith("+json"));
    }

    /**
     * Cancels the transfer on subscribe and answers {@code null}: no byte of
     * the body is requested, so a slow or endless body cannot hold the
     * thread. The connection is closed rather than reused, which is fine for
     * an error response.
     */
    static final class Unread implements HttpResponse.BodySubscriber<Body> {
        private final CompletableFuture<Body> result = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription s) {
            s.cancel();
            result.complete(null);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            // never requested
        }

        @Override
        public void onError(Throwable t) {
            result.complete(null); // the body was never wanted
        }

        @Override
        public void onComplete() {
            result.complete(null);
        }

        @Override
        public CompletionStage<Body> getBody() {
            return result;
        }
    }

    /**
     * Collects the body into memory up to the cap. Bodies are JSON, which
     * is UTF-8 (RFC 8259), so the bytes are decoded as UTF-8.
     */
    static final class Capped implements HttpResponse.BodySubscriber<Body> {
        private final long maxBytes;
        private final boolean declaredOverCap;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final CompletableFuture<Body> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        /** @param declaredOverCap Content-Length is already over the cap: read nothing */
        Capped(long maxBytes, boolean declaredOverCap) {
            this.maxBytes = maxBytes;
            this.declaredOverCap = declaredOverCap;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            if (declaredOverCap) {
                s.cancel();
                result.complete(new Body(null, true));
                return;
            }
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer b : items) {
                if (buf.size() + (long) b.remaining() > maxBytes) {
                    subscription.cancel();
                    result.complete(new Body(null, true));
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                buf.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(new Body(
                    new String(buf.toByteArray(), StandardCharsets.UTF_8), false));
        }

        @Override
        public CompletionStage<Body> getBody() {
            return result;
        }
    }
}
