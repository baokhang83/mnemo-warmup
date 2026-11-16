package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedBodyTest {

    private static ResponseInfo info(String contentLength) {
        HttpHeaders headers = HttpHeaders.of(contentLength == null ? Map.of() : Map.of("Content-Length", List.of(contentLength)), (a, b) -> true);
        return new ResponseInfo() {
            public int statusCode() { return 200; }
            public HttpHeaders headers() { return headers; }
            public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
        };
    }

    private static final class Sub implements Flow.Subscription {
        final AtomicBoolean cancelled = new AtomicBoolean();
        public void request(long n) {}
        public void cancel() { cancelled.set(true); }
    }

    @Test
    void aBodyWithinTheLimitIsReturnedWhole() throws Exception {
        var subscriber = BoundedBody.bytes(10, "a thing").apply(info("6"));
        var sub = new Sub();
        subscriber.onSubscribe(sub);
        subscriber.onNext(List.of(ByteBuffer.wrap("abc".getBytes()), ByteBuffer.wrap("def".getBytes())));
        subscriber.onComplete();
        assertArrayEquals("abcdef".getBytes(), subscriber.getBody().toCompletableFuture().get());
        assertFalse(sub.cancelled.get());
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedBeforeReadingAnything() {
        var subscriber = BoundedBody.bytes(10, "a thing").apply(info("11"));
        var sub = new Sub();
        subscriber.onSubscribe(sub);
        assertTrue(sub.cancelled.get(), "the stream must be cancelled at once");
        var ex = assertThrows(ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get());
        assertTrue(ex.getCause() instanceof StoreException && ex.getCause().getMessage().contains("more than the 10 bytes allowed for a thing"), ex.getCause().getMessage());
    }

    // a server that does not declare a length (chunked) is cut off when the bytes received pass the limit
    @Test
    void anUndeclaredBodyIsCutOffOnceItPassesTheLimit() {
        var subscriber = BoundedBody.bytes(5, "a thing").apply(info(null));
        var sub = new Sub();
        subscriber.onSubscribe(sub);
        subscriber.onNext(List.of(ByteBuffer.wrap("abc".getBytes())));
        assertFalse(sub.cancelled.get());
        subscriber.onNext(List.of(ByteBuffer.wrap("def".getBytes())));
        assertTrue(sub.cancelled.get());
        var ex = assertThrows(ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get());
        assertEquals(StoreException.class, ex.getCause().getClass());
    }

    @Test
    void exactlyTheLimitIsAllowed() throws Exception {
        var subscriber = BoundedBody.bytes(4, "a thing").apply(info(null));
        subscriber.onSubscribe(new Sub());
        subscriber.onNext(List.of(ByteBuffer.wrap("abcd".getBytes())));
        subscriber.onComplete();
        assertEquals(4, subscriber.getBody().toCompletableFuture().get().length);
    }
}
