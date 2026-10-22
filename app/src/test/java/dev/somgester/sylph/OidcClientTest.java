package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OidcClientTest {

    @Test
    void exchangesCodeWithPkceVerifiesIdentityAndCompletesBrowserLogout() throws Exception {
        try (var server = new OidcTestServer()) {
            var client = new OidcClient(server.configuration);
            var session = client.signIn(server::browser);
            assertEquals("stable-user-id", session.profile.subject());
            assertEquals("caveman", session.profile.name());
            assertEquals("cave@example.test", session.profile.email());
            assertEquals("openid profile email", server.authorization.get("scope"));
            assertEquals("S256", server.authorization.get("code_challenge_method"));
            assertEquals("authorization_code", server.tokenRequest.get("grant_type"));
            assertFalse(server.tokenRequest.containsKey("client_secret"));
            assertNotEquals(server.authorization.get("code_challenge"), server.tokenRequest.get("code_verifier"));
            assertEquals(1, server.exchanges.get());
            client.signOut(session, server::browser);
            assertFalse(session.toString().contains("eyJ"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "nonce", "expiration", "future", "subject", "azp", "multi-audience"})
    void rejectsSignedTokensWithInvalidIdentityClaims(String fault) throws Exception {
        for (boolean edDsa : List.of(false, true)) {
            try (var server = new OidcTestServer()) {
                server.edDsa = edDsa;
                server.claims = builder -> {
                    switch (fault) {
                        case "issuer" -> builder.issuer("https://attacker.example");
                        case "audience" -> builder.audience("another-client");
                        case "nonce" -> builder.claim("nonce", "replayed-nonce");
                        case "expiration" -> builder.expirationTime(Date.from(Instant.now().minusSeconds(60)));
                        case "future" -> builder.issueTime(Date.from(Instant.now().plusSeconds(120)));
                        case "subject" -> builder.subject("");
                        case "azp" -> builder.claim("azp", "another-client");
                        case "multi-audience" -> builder.audience(List.of("sylph-desktop", "another-client"));
                        default -> throw new AssertionError(fault);
                    }
                };
                assertThrows(IOException.class, () -> new OidcClient(server.configuration).signIn(server::browser));
            }
        }
    }

    @Test
    void rejectsForgedUnsignedAndWrongAlgorithmTokens() throws Exception {
        try (var server = new OidcTestServer()) {
            JWTClaimsSet claims = server.builder("nonce").build();
            String forged = OidcTestServer.token(claims, OidcTestServer.OTHER_KEY);
            String unsigned = new PlainJWT(claims).serialize();
            SignedJWT hmac = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            hmac.sign(new MACSigner(new byte[32]));
            String keys = "{\"keys\":[" + OidcTestServer.KEY.toPublicJWK().toJSONString() + "]}";
            for (String token : List.of(forged, unsigned, hmac.serialize(), "not-a-jwt")) {
                assertThrows(IOException.class, () -> OidcClient.verify(token, "nonce", server.configuration, keys));
            }
        }
    }

    @Test
    void untrustedDiscoveryCannotRedirectTokenExchangeToAnotherOrigin() throws Exception {
        try (var server = new OidcTestServer()) {
            AtomicBoolean opened = new AtomicBoolean();
            server.discoveryIssuer = "https://attacker.example";
            assertThrows(IOException.class, () -> new OidcClient(server.configuration)
                    .signIn(uri -> opened.set(true)));
            server.discoveryIssuer = server.issuer.toString();
            server.tokenEndpoint = "https://attacker.example/token";
            assertThrows(IOException.class, () -> new OidcClient(server.configuration)
                    .signIn(uri -> opened.set(true)));
            assertFalse(opened.get());
            assertEquals(0, server.exchanges.get());
        }
    }

    @Test
    void tokenErrorsNeverExposeProviderResponseBodies() throws Exception {
        try (var server = new OidcTestServer()) {
            server.tokenStatus = 400;
            IOException error = assertThrows(IOException.class,
                    () -> new OidcClient(server.configuration).signIn(server::browser));
            assertFalse(error.toString().contains("DO_NOT_LEAK"));
            assertEquals(AuthFailure.Reason.TOKEN_EXCHANGE.message, error.getMessage());
        }
    }

    @Test
    void edDsaCodeExchangeUsesTrustedPublicKeyAndCompletesLogout() throws Exception {
        try (var server = new OidcTestServer()) {
            server.edDsa = true;
            var client = new OidcClient(server.configuration);
            var session = client.signIn(server::browser);
            assertEquals("stable-user-id", session.profile.subject());
            assertEquals("caveman", session.profile.name());
            client.signOut(session, server::browser);
        }
    }

    @Test
    void edDsaRejectsForgedSignaturesAndSupportsMultipleRotatingKeys() throws Exception {
        try (var server = new OidcTestServer()) {
            var claims = server.builder("nonce").build();
            String trusted = OidcTestServer.token(claims, OidcTestServer.ED_KEY);
            String forged = OidcTestServer.token(claims, OidcTestServer.OTHER_ED_KEY);
            assertThrows(AuthFailure.class,
                    () -> OidcClient.verify(forged, "nonce", server.configuration, server.keys));
            SignedJWT tampered = new SignedJWT(SignedJWT.parse(trusted).getHeader(),
                    new JWTClaimsSet.Builder(claims).subject("attacker").build());
            String altered = tampered.getHeader().toBase64URL() + "." + tampered.getPayload().toBase64URL()
                    + "." + SignedJWT.parse(trusted).getSignature();
            assertThrows(AuthFailure.class,
                    () -> OidcClient.verify(altered, "nonce", server.configuration, server.keys));
            String rotated = new JWKSet(List.of(OidcTestServer.OTHER_ED_KEY.toPublicJWK(),
                    OidcTestServer.ED_KEY.toPublicJWK(), OidcTestServer.KEY.toPublicJWK())).toString();
            assertEquals("stable-user-id",
                    OidcClient.verify(trusted, "nonce", server.configuration, rotated).subject());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kid", "algorithm", "encryption", "operations", "curve", "type", "missing"})
    void edDsaRejectsKeysWithIncompatibleMetadata(String fault) throws Exception {
        try (var server = new OidcTestServer()) {
            String token = OidcTestServer.token(server.builder("nonce").build(), OidcTestServer.ED_KEY);
            var key = JsonParser.parseString(OidcTestServer.ED_KEY.toPublicJWK().toJSONString()).getAsJsonObject();
            switch (fault) {
                case "kid" -> key.addProperty("kid", "unknown-key");
                case "algorithm" -> key.addProperty("alg", "RS256");
                case "encryption" -> key.addProperty("use", "enc");
                case "operations" -> {
                    JsonArray operations = new JsonArray();
                    operations.add("sign");
                    key.add("key_ops", operations);
                }
                case "curve" -> key.addProperty("crv", "X25519");
                case "type" -> key = JsonParser.parseString(OidcTestServer.KEY.toPublicJWK().toJSONString())
                        .getAsJsonObject();
                case "missing" -> key = null;
                default -> throw new AssertionError(fault);
            }
            String keys = key == null ? "{\"keys\":[]}" : "{\"keys\":[" + key + "]}";
            assertThrows(AuthFailure.class, () -> OidcClient.verify(token, "nonce", server.configuration, keys));
        }
    }

    @Test
    void edDsaAcceptsExplicitVerificationKeyMetadataAndRejectsUnknownCriticalHeaders() throws Exception {
        try (var server = new OidcTestServer()) {
            var key = JsonParser.parseString(OidcTestServer.ED_KEY.toPublicJWK().toJSONString()).getAsJsonObject();
            key.addProperty("alg", "EdDSA");
            key.addProperty("use", "sig");
            JsonArray operations = new JsonArray();
            operations.add("verify");
            key.add("key_ops", operations);
            String keys = "{\"keys\":[" + key + "]}";
            var claims = server.builder("nonce").build();
            String valid = OidcTestServer.token(claims, OidcTestServer.ED_KEY);
            assertEquals("stable-user-id", OidcClient.verify(valid, "nonce", server.configuration, keys).subject());
            var critical = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                    .keyID(OidcTestServer.ED_KEY.getKeyID()).customParam("unknown", true)
                    .criticalParams(java.util.Set.of("unknown")).build(), claims);
            critical.sign(new Ed25519Signer(OidcTestServer.ED_KEY));
            assertThrows(AuthFailure.class,
                    () -> OidcClient.verify(critical.serialize(), "nonce", server.configuration, keys));
        }
    }

    @Test
    void unsupportedAlgorithmHasActionableDiagnosticWithoutTokenDetails() throws Exception {
        try (var server = new OidcTestServer()) {
            SignedJWT hmac = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), server.builder("nonce").build());
            hmac.sign(new MACSigner(new byte[32]));
            AuthFailure failure = assertThrows(AuthFailure.class,
                    () -> OidcClient.verify(hmac.serialize(), "nonce", server.configuration, server.keys));
            assertEquals(AuthFailure.Reason.SIGNING_ALGORITHM.message, failure.getMessage());
            assertTrue(failure.getMessage().contains("EdDSA"));
            assertFalse(failure.getMessage().contains(hmac.serialize()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"parts", "full", "username", "subject"})
    void displayNameUsesVerifiedProfileClaimsWithSafeFallbacks(String source) throws Exception {
        try (var server = new OidcTestServer()) {
            var claims = server.builder("nonce");
            String expected;
            switch (source) {
                case "parts" -> {
                    claims.claim("given_name", " Cave ").claim("family_name", " Man ").claim("name", "Other Name");
                    expected = "Cave Man";
                }
                case "full" -> {
                    claims.claim("name", "Cave Man");
                    expected = "Cave Man";
                }
                case "username" -> expected = "caveman";
                case "subject" -> {
                    claims.claim("preferred_username", null).claim("name", " ");
                    expected = "stable-user-id";
                }
                default -> throw new AssertionError(source);
            }
            String token = OidcTestServer.token(claims.build(), OidcTestServer.ED_KEY);
            assertEquals(expected, OidcClient.verify(token, "nonce", server.configuration, server.keys).name());
        }
    }

    @Test
    void timeoutReleasesCallbackPortAndNewAttemptsUseFreshStateAndNonce() throws Exception {
        try (var server = new OidcTestServer()) {
            var client = new OidcClient(server.configuration, Duration.ofMillis(100));
            assertThrows(IOException.class, () -> client.signIn(uri -> { }));
            client.signIn(server::browser);
            String state = server.authorization.get("state");
            String nonce = server.authorization.get("nonce");
            client.signIn(server::browser);
            assertNotEquals(state, server.authorization.get("state"));
            assertNotEquals(nonce, server.authorization.get("nonce"));
        }
    }

    @Test
    void invalidConfigurationCannotExposePasswordsOrUseRemotePlainHttp() {
        for (String issuer : List.of("http://example.com/realms/sylph", "file:///etc/passwd",
                "https://user:password@example.com/realm", "https://example.com/realm/")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AuthConfiguration(URI.create(issuer), "sylph-desktop", 43117));
        }
        var defaults = AuthConfiguration.environment(Map.of());
        assertEquals(43117, defaults.callbackPort());
        assertEquals("sylph-desktop", defaults.clientId());
    }
}
