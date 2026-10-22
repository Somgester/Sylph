package dev.somgester.sylph;

import com.google.gson.JsonObject;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Authorization code + S256 PKCE against a user-configured Keycloak issuer. No client secret. */
final class OidcClient {

    record Profile(URI issuer, String subject, String name, String email, Instant expiresAt) { }

    // Not a record: a default record toString would expose the ID token.
    static final class Session {

        final Profile profile;

        private final String idToken;

        private final Metadata metadata;

        Session(Profile profile, String idToken, Metadata metadata) {
            this.profile = profile;
            this.idToken = idToken;
            this.metadata = metadata;
        }
    }

    private record Metadata(URI authorization, URI token, URI keys, URI logout) { }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthConfiguration configuration;

    private final Duration callbackTimeout;

    OidcClient(AuthConfiguration configuration) {
        this(configuration, Duration.ofMinutes(2));
    }

    OidcClient(AuthConfiguration configuration, Duration callbackTimeout) {
        this.configuration = configuration;
        this.callbackTimeout = callbackTimeout;
    }

    Session signIn(Consumer<URI> browser) throws IOException, InterruptedException {
        try (AuthHttp http = new AuthHttp()) {
            Metadata metadata = discover(http);
            String state = random();
            String nonce = random();
            String verifier = random();
            try (var callback = new OAuthCallback(configuration.callbackPort(), "/oauth/callback", state,
                    configuration.issuer().toString(), true)) {
                Map<String, String> authorization = new LinkedHashMap<>();
                authorization.put("response_type", "code");
                authorization.put("client_id", configuration.clientId());
                authorization.put("redirect_uri", callback.uri().toString());
                authorization.put("scope", "openid profile email");
                authorization.put("state", state);
                authorization.put("nonce", nonce);
                authorization.put("code_challenge", challenge(verifier));
                authorization.put("code_challenge_method", "S256");
                browser.accept(URI.create(metadata.authorization() + "?" + form(authorization)));
                String code = callback.await(callbackTimeout).get("code");
                var tokenRequest = HttpRequest.newBuilder(metadata.token()).timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "authorization_code",
                                "client_id", configuration.clientId(), "redirect_uri", callback.uri().toString(),
                                "code", code, "code_verifier", verifier)))).build();
                String idToken;
                try {
                    idToken = string(http.json(tokenRequest), "id_token");
                } catch (IOException error) {
                    throw new AuthFailure(AuthFailure.Reason.TOKEN_EXCHANGE);
                }
                JsonObject keys = http.json(HttpRequest.newBuilder(metadata.keys()).GET().build());
                Profile profile = verify(idToken, nonce, configuration, keys.toString());
                return new Session(profile, idToken, metadata);
            }
        }
    }

    void signOut(Session session, Consumer<URI> browser) throws IOException, InterruptedException {
        String state = random();
        try (var callback = new OAuthCallback(configuration.callbackPort(), "/oauth/logout", state,
                configuration.issuer().toString(), false)) {
            browser.accept(URI.create(session.metadata.logout() + "?" + form(Map.of(
                    "id_token_hint", session.idToken, "client_id", configuration.clientId(),
                    "post_logout_redirect_uri", callback.uri().toString(), "state", state))));
            callback.await(callbackTimeout);
        }
    }

    private Metadata discover(AuthHttp http) throws IOException, InterruptedException {
        JsonObject discovery = http.json(HttpRequest.newBuilder(
                URI.create(configuration.issuer() + "/.well-known/openid-configuration")).GET().build());
        if (!configuration.issuer().toString().equals(string(discovery, "issuer"))) {
            throw new IOException("Authentication discovery issuer does not match configuration.");
        }
        if (!discovery.has("code_challenge_methods_supported")
                || !discovery.getAsJsonArray("code_challenge_methods_supported")
                        .contains(new com.google.gson.JsonPrimitive("S256"))) {
            throw new IOException("Authentication server must support S256 PKCE.");
        }
        return new Metadata(endpoint(discovery, "authorization_endpoint"), endpoint(discovery, "token_endpoint"),
                endpoint(discovery, "jwks_uri"), endpoint(discovery, "end_session_endpoint"));
    }

    private URI endpoint(JsonObject discovery, String key) throws IOException {
        try {
            URI uri = URI.create(string(discovery, key));
            if (!AuthConfiguration.safeEndpoint(uri) || uri.getQuery() != null
                    || !configuration.issuer().getScheme().equals(uri.getScheme())
                    || !configuration.issuer().getRawAuthority().equals(uri.getRawAuthority())) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IOException("Authentication endpoints must use the configured issuer's origin.");
        }
    }

    static Profile verify(String token, String nonce, AuthConfiguration configuration, String keys) throws IOException {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSAlgorithm algorithm = jwt.getHeader().getAlgorithm();
            if (!JWSAlgorithm.RS256.equals(algorithm) && !JWSAlgorithm.EdDSA.equals(algorithm)) {
                throw new AuthFailure(AuthFailure.Reason.SIGNING_ALGORITHM);
            }
            JWKSet keySet = JWKSet.parse(keys);
            var required = new JWTClaimsSet.Builder().issuer(configuration.issuer().toString())
                    .audience(configuration.clientId()).claim("nonce", nonce).build();
            var verifier = new DefaultJWTClaimsVerifier<SecurityContext>(required, Set.of("sub", "iat", "exp"));
            verifier.setMaxClockSkew(30);
            JWTClaimsSet claims;
            if (JWSAlgorithm.EdDSA.equals(algorithm)) {
                verifyEdDsa(jwt, keySet);
                claims = jwt.getJWTClaimsSet();
                verifier.verify(claims, null);
            } else {
                var processor = new DefaultJWTProcessor<SecurityContext>();
                processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                        new ImmutableJWKSet<>(keySet)));
                processor.setJWTClaimsSetVerifier(verifier);
                claims = processor.process(jwt, null);
            }
            String authorized = claims.getStringClaim("azp");
            String subject = claims.getStringClaim("sub");
            var issuedAt = claims.getIssueTime();
            var expiresAt = claims.getExpirationTime();
            if (subject == null || subject.isBlank() || issuedAt == null || expiresAt == null
                    || issuedAt.toInstant().isAfter(Instant.now().plusSeconds(30))
                    || expiresAt.before(issuedAt)
                    || authorized != null && !authorized.equals(configuration.clientId())
                    || claims.getAudience().size() > 1 && authorized == null) {
                throw new AuthFailure(AuthFailure.Reason.TOKEN_VERIFICATION);
            }
            String given = claims.getStringClaim("given_name");
            String family = claims.getStringClaim("family_name");
            String name = ((given == null ? "" : given.strip()) + " "
                    + (family == null ? "" : family.strip())).strip();
            if (name.isBlank()) {
                name = claims.getStringClaim("name");
            }
            if (name == null || name.isBlank()) {
                name = claims.getStringClaim("preferred_username");
            }
            if (name == null || name.isBlank()) {
                name = subject;
            }
            return new Profile(configuration.issuer(), subject, name, claims.getStringClaim("email"),
                    expiresAt.toInstant());
        } catch (ParseException | BadJOSEException | JOSEException | IllegalArgumentException error) {
            throw new AuthFailure(AuthFailure.Reason.TOKEN_VERIFICATION);
        }
    }

    private static void verifyEdDsa(SignedJWT jwt, JWKSet keys) throws JOSEException, AuthFailure {
        // Only issuer-provided keys matching kid, algorithm, and signing use are candidates.
        var selector = new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader()));
        for (var key : selector.select(keys)) {
            if (key instanceof OctetKeyPair pair && Curve.Ed25519.equals(pair.getCurve())
                    && (key.getKeyOperations() == null || key.getKeyOperations().isEmpty()
                        || key.getKeyOperations().contains(KeyOperation.VERIFY))
                    && jwt.verify(new Ed25519Verifier(pair.toPublicJWK()))) {
                return;
            }
        }
        throw new AuthFailure(AuthFailure.Reason.TOKEN_VERIFICATION);
    }

    private static String string(JsonObject value, String key) throws IOException {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString()
                || value.get(key).getAsString().isBlank()) {
            throw new IOException("Authentication server response is missing a required field.");
        }
        return value.get(key).getAsString();
    }

    static String form(Map<String, String> parameters) {
        return parameters.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
