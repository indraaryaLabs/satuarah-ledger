package click.satuarah.ledger.support;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A small JSON client over the JDK's own HttpClient, so the API tests touch the real server. */
public final class Http {

    public record Response(int status, String body, java.net.http.HttpHeaders headers) {

        public <T> T json(String path) {
            return JsonPath.read(body, path);
        }

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    public Http(int port) {
        this.base = "http://localhost:" + port;
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), Map.of());
    }

    public Response post(String path, String json) {
        return post(path, json, Map.of());
    }

    public Response post(String path, String json, Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.putIfAbsent("Content-Type", "application/json");
        return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(json)), all);
    }

    public Response postWithKey(String path, String key, String json) {
        return post(path, json, Map.of("Idempotency-Key", key));
    }

    private Response send(HttpRequest.Builder request, Map<String, String> headers) {
        headers.forEach(request::header);
        try {
            HttpResponse<String> response =
                    client.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
