package dev.somgester.sylph;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Signed local OIDC fixture; never contacts an identity provider or opens a desktop browser. */
final class OidcTestServer implements AutoCloseable {

    static final RSAKey KEY = key("signing-key");

    static final RSAKey OTHER_KEY = key("signing-key");

    static final OctetKeyPair ED_KEY = edKey();

    static final OctetKeyPair OTHER_ED_KEY = edKey();

    boolean edDsa;

    String keys = new JWKSet(List.of(KEY.toPublicJWK(), ED_KEY.toPublicJWK())).toString();

    final HttpServer server;

    final URI issuer;

    final AuthConfiguration configuration;

    final AtomicInteger exchanges = new AtomicInteger();

    volatile Map<String, String> authorization;

    volatile Map<String, String> tokenRequest;

    Consumer<JWTClaimsSet.Builder> claims = value -> { };

    String discoveryIssuer;

    String tokenEndpoint;

    String rawToken;

    int tokenStatus = 200;

    OidcTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test");
        discoveryIssuer = issuer.toString();
        tokenEndpoint = issuer + "/token";
        configuration = new AuthConfiguration(issuer, "sylph-desktop", availablePort());
        server.createContext("/realms/test/.well-known/openid-configuration", exchange -> {
            JsonObject result = new JsonObject();
            result.addProperty("issuer", discoveryIssuer);
            result.addProperty("authorization_endpoint", issuer + "/authorize");
            result.addProperty("token_endpoint", tokenEndpoint);
            result.addProperty("jwks_uri", issuer + "/keys");
            result.addProperty("end_session_endpoint", issuer + "/logout");
            JsonArray methods = new JsonArray();
            methods.add("S256");
            result.add("code_challenge_methods_supported", methods);
            json(exchange, 200, result.toString());
        });
        server.createContext("/realms/test/keys", exchange -> json(exchange, 200,
                keys));
        server.createContext("/realms/test/token", exchange -> {
            exchanges.incrementAndGet();
            tokenRequest = OAuthCallback.query(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            if (tokenStatus != 200) {
                json(exchange, tokenStatus, "{\"error_description\":\"DO_NOT_LEAK_TOKEN_RESPONSE\"}");
            } else if (!OidcClient.challenge(tokenRequest.get("code_verifier"))
                    .equals(authorization.get("code_challenge"))) {
                json(exchange, 400, "{\"error\":\"invalid_grant\"}");
            } else {
                try {
                    var builder = builder(authorization.get("nonce"));
                    claims.accept(builder);
                    JsonObject response = new JsonObject();
                    String signed = edDsa ? token(builder.build(), ED_KEY) : token(builder.build(), KEY);
                    response.addProperty("id_token", rawToken == null ? signed : rawToken);
                    response.addProperty("access_token", "not-persisted");
                    json(exchange, 200, response.toString());
                } catch (JOSEException error) {
                    throw new AssertionError(error);
                }
            }
        });
        server.start();
    }

    JWTClaimsSet.Builder builder(String nonce) {
        return new JWTClaimsSet.Builder().issuer(issuer.toString()).subject("stable-user-id")
                .audience("sylph-desktop").issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .claim("nonce", nonce).claim("preferred_username", "caveman").claim("email", "cave@example.test");
    }

    void browser(URI uri) {
        var parameters = OAuthCallback.query(uri.getRawQuery());
        if (parameters.containsKey("post_logout_redirect_uri")) {
            get(URI.create(parameters.get("post_logout_redirect_uri") + "?"
                    + OidcClient.form(Map.of("state", parameters.get("state")))));
        } else {
            authorization = parameters;
            get(URI.create(parameters.get("redirect_uri") + "?" + OidcClient.form(Map.of(
                    "code", "authorization-code", "state", parameters.get("state"), "iss", issuer.toString()))));
        }
    }

    static String token(JWTClaimsSet claims, RSAKey key) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    static String token(JWTClaimsSet claims, OctetKeyPair key) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new Ed25519Signer(key));
        return jwt.serialize();
    }

    private static OctetKeyPair edKey() {
        try {
            return new OctetKeyPairGenerator(Curve.Ed25519).keyID("ed-signing-key").generate();
        } catch (JOSEException error) {
            throw new AssertionError(error);
        }
    }

    static HttpResponse<String> get(URI uri) {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException error) {
            throw new AssertionError(error);
        }
    }

    static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static RSAKey key(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (JOSEException error) {
            throw new AssertionError(error);
        }
    }

    private static void json(HttpExchange exchange, int status, String text) throws IOException {
        try (exchange) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
