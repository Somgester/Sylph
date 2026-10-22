package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("authServer")
class KeycloakIntegrationTest {

    @Test
    void browserPasswordLoginValidatedIdentityAndServerLogoutWorkWithRealKeycloak() throws Exception {
        String username = System.getenv("SYLPH_AUTH_TEST_USERNAME");
        String password = System.getenv("SYLPH_AUTH_TEST_PASSWORD");
        assertNotNull(username, "Run python3 scripts/test-auth.py to provision a disposable local user.");
        assertNotNull(password, "A disposable user's password is required.");
        String expectedName = System.getenv().getOrDefault("SYLPH_AUTH_TEST_NAME", username);
        var configuration = AuthConfiguration.environment(System.getenv());
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL) {
            @Override
            public void put(URI uri, java.util.Map<String, java.util.List<String>> headers) throws java.io.IOException {
                super.put(uri, headers);
                if (uri.getHost().equals("localhost") && uri.getScheme().equals("http")) {
                    // Real browsers treat localhost as a secure context; JDK's HTTP cookie jar does not.
                    // This adaptation belongs only to the local-server test, never to the application.
                    for (var cookie : getCookieStore().getCookies()) {
                        cookie.setSecure(false);
                        cookie.setVersion(0);
                    }
                }
            }
        };
        try (HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(5)).build()) {
            var client = new OidcClient(configuration, Duration.ofSeconds(10));
            var first = client.signIn(uri -> login(browser, uri, username, password));
            assertEquals(expectedName, first.profile.name());
            client.signOut(first, uri -> visit(browser, uri));
            // This browser must see a login form again, rather than silently using an old SSO session.
            var second = client.signIn(uri -> login(browser, uri, username, password));
            assertEquals(first.profile.subject(), second.profile.subject());
            client.signOut(second, uri -> visit(browser, uri));
        }
    }

    private static void login(HttpClient browser, URI uri, String username, String password) {
        try {
            var page = browser.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            Element form = Jsoup.parse(page.body(), page.uri().toString()).selectFirst("form#kc-form-login");
            assertNotNull(form, "Expected Keycloak password form; no desktop browser is opened by this test.");
            var parameters = new LinkedHashMap<String, String>();
            for (Element input : form.select("input[type=hidden][name]")) {
                parameters.put(input.attr("name"), input.attr("value"));
            }
            parameters.put("username", username);
            parameters.put("password", password);
            var response = browser.send(HttpRequest.newBuilder(URI.create(form.absUrl("action")))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(OidcClient.form(parameters))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("/oauth/callback", response.uri().getPath(),
                    "Expected browser to return to desktop callback.");
            assertEquals(200, response.statusCode());
        } catch (java.io.IOException | InterruptedException error) {
            throw new AssertionError("Local Keycloak browser flow failed.", error);
        }
    }

    private static void visit(HttpClient browser, URI uri) {
        try {
            var response = browser.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("/oauth/logout", response.uri().getPath());
            assertEquals(200, response.statusCode());
        } catch (java.io.IOException | InterruptedException error) {
            throw new AssertionError("Local Keycloak logout failed.", error);
        }
    }
}
