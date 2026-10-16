package com.vcfcf.adapters.example;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 *   <li>reads a 200 body up to {@code maxBytes} and fails with an
 *       {@link IOException} past that (it cancels the transfer, so nothing
 *       more is read), including when {@code Content-Length} already says
 *       the body is too large;</li>
 *   <li>discards the body of any other status unread (the caller reports
 *       the status only, never the body).</li>
 * </ul>
 * Raise {@link #DEFAULT_MAX_BYTES} if a real endpoint of yours legitimately
 * returns more; keep one constant per pak.
 */
final class BoundedBody implements HttpResponse.BodyHandler<String> {

    /** Default cap: 8 MB. One constant; change it here for your pak. */
    static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;

    private final long maxBytes;

    BoundedBody(long maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be > 0");
        this.maxBytes = maxBytes;
    }

    @Override
    public HttpResponse.BodySubscriber<String> apply(HttpResponse.ResponseInfo info) {
        if (info.statusCode() != 200) {
            // Not needed: the caller reports the status, never the body.
            return HttpResponse.BodySubscribers.replacing(null);
        }
        long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declared > maxBytes) {
            return new Capped(maxBytes, tooLarge(maxBytes));
        }
        return new Capped(maxBytes, null);
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

    private static IOException tooLarge(long maxBytes) {
        return new IOException("response body exceeds the " + maxBytes
                + "-byte cap; not read");
    }

    /**
     * Collects the body into memory up to the cap. Bodies are JSON, which
     * is UTF-8 (RFC 8259), so the bytes are decoded as UTF-8.
     */
    static final class Capped implements HttpResponse.BodySubscriber<String> {
        private final long maxBytes;
        private final IOException failFast;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        /** @param failFast when non-null, fail with it without reading */
        Capped(long maxBytes, IOException failFast) {
            this.maxBytes = maxBytes;
            this.failFast = failFast;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            if (failFast != null) {
                result.completeExceptionally(failFast);
                s.cancel();
                return;
            }
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer b : items) {
                if (buf.size() + (long) b.remaining() > maxBytes) {
                    result.completeExceptionally(tooLarge(maxBytes));
                    subscription.cancel();
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
            result.complete(new String(buf.toByteArray(), StandardCharsets.UTF_8));
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }
    }
}
