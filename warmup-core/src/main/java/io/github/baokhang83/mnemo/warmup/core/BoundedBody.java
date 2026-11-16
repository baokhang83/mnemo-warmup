package io.github.baokhang83.mnemo.warmup.core;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A response body read into memory with a hard ceiling. {@code BodyHandlers.ofByteArray()} reads whatever the server sends, so a
 * hostile or broken registry could exhaust the heap (an {@code OutOfMemoryError} is an Error, and would end the init container
 * with a failure). This one refuses a declared length over the limit before reading anything, and cancels the stream the moment
 * the bytes received pass it.
 */
final class BoundedBody {
    private BoundedBody() {}

    static BodyHandler<byte[]> bytes(long limit, String what) {
        return info -> new Collector(limit, what, info.headers().firstValueAsLong("Content-Length").orElse(-1));
    }

    private static final class Collector implements BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final long limit;
        private final String what;
        private final long declared;
        private Flow.Subscription subscription;

        Collector(long limit, String what, long declared) {
            this.limit = limit;
            this.what = what;
            this.declared = declared;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            if (declared > limit) {
                refuse();
                return;
            }
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (buffer.size() + (long) item.remaining() > limit) {
                    refuse();
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.write(chunk, 0, chunk.length);
            }
        }

        private void refuse() {
            subscription.cancel();
            result.completeExceptionally(new StoreException("The registry sent more than the " + limit + " bytes allowed for " + what + "; refusing it"));
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }
    }
}
