package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OAuthCallbackTest {

    @Test
    void rejectsForeignStateDuplicateParametersWrongIssuerAndPathsWithoutConsumingAttempt() throws Exception {
        try (var callback = new OAuthCallback(0, "/oauth/callback", "expected", "https://issuer.example", true)) {
            String base = callback.uri().toString();
            assertEquals(400, OidcTestServer.get(URI.create(base + "?code=bad&state=foreign")).statusCode());
            assertEquals(400, OidcTestServer.get(
                    URI.create(base + "?code=x&state=expected&state=expected")).statusCode());
            assertEquals(400, OidcTestServer.get(URI.create(base
                    + "?code=x&state=expected&iss=https%3A%2F%2Fforeign.example")).statusCode());
            assertEquals(404, OidcTestServer.get(URI.create(base + "/extra?code=x&state=expected")).statusCode());
            assertEquals(400, OidcTestServer.get(URI.create(base + "?state=expected")).statusCode());
            assertEquals(200, OidcTestServer.get(URI.create(base + "?code=good&state=expected")).statusCode());
            assertEquals("good", callback.await(Duration.ofSeconds(1)).get("code"));
            assertEquals(410, OidcTestServer.get(URI.create(base + "?code=good&state=expected")).statusCode());
        }
    }

    @Test
    void denialCompletesAttemptWithoutEchoingQueryDataIntoBrowserResponse() throws Exception {
        try (var callback = new OAuthCallback(0, "/oauth/callback", "expected", "https://issuer.example", true)) {
            var response = OidcTestServer.get(URI.create(callback.uri()
                    + "?state=expected&error=access_denied&error_description=SECRET"));
            assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
            assertTrue(response.body().contains("cancelled"));
            assertTrue(!response.body().contains("SECRET"));
            assertThrows(IOException.class, () -> callback.await(Duration.ofSeconds(1)));
        }
    }
}
