package dev.somgester.sylph;

import java.net.URI;
import java.util.Map;
import java.util.Set;

record AuthConfiguration(URI issuer, String clientId, int callbackPort) {

    AuthConfiguration {
        if (!safeEndpoint(issuer) || issuer.getQuery() != null || issuer.getPath().endsWith("/")
                || clientId == null || !clientId.matches("[a-zA-Z0-9._-]{1,128}")
                || callbackPort < 1024 || callbackPort > 65535) {
            throw new IllegalArgumentException("Invalid authentication issuer, client ID, or callback port.");
        }
    }

    static AuthConfiguration environment(Map<String, String> environment) {
        return new AuthConfiguration(URI.create(environment.getOrDefault("SYLPH_AUTH_ISSUER",
                "http://localhost:8080/realms/sylph")),
                environment.getOrDefault("SYLPH_AUTH_CLIENT_ID", "sylph-desktop"),
                Integer.parseInt(environment.getOrDefault("SYLPH_AUTH_CALLBACK_PORT", "43117")));
    }

    static boolean safeEndpoint(URI uri) {
        return uri != null && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()));
    }
}
