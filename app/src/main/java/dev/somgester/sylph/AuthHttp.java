package dev.somgester.sylph;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** No redirects or response bodies in errors: a token response must never become UI/log text. */
final class AuthHttp implements AutoCloseable {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    JsonObject json(HttpRequest request) throws IOException, InterruptedException {
        var pending = client.sendAsync(request, info -> new Body());
        try {
            var response = pending.get(15, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                throw new IOException("Authentication server returned HTTP " + response.statusCode() + ".");
            }
            try {
                return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException error) {
                throw new IOException("Authentication server returned invalid JSON.");
            }
        } catch (ExecutionException error) {
            throw new IOException("Could not reach the authentication server.");
        } catch (TimeoutException error) {
            pending.cancel(true);
            throw new IOException("Authentication server request timed out.");
        } catch (InterruptedException error) {
            pending.cancel(true);
            throw error;
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static final class Body implements HttpResponse.BodySubscriber<byte[]> {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private final CompletableFuture<byte[]> result = new CompletableFuture<>();

        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 65_536 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Authentication response exceeds size limit."));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
