package dev.somgester.sylph;

import java.io.IOException;

/** Only these fixed, application-owned diagnostics may be displayed after an authentication failure. */
final class AuthFailure extends IOException {

    private static final long serialVersionUID = 1L;

    enum Reason {
        TOKEN_EXCHANGE("Browser returned, but the authorization-code exchange failed. "
                + "Check Keycloak client settings and the callback URI."),
        TOKEN_VERIFICATION("Browser returned, but the identity token could not be verified. "
                + "Check Keycloak issuer, client, and signing-key settings."),
        SIGNING_ALGORITHM("Keycloak's ID-token signing algorithm is unsupported. "
                + "Sylph supports EdDSA (Ed25519) and RS256 ID tokens.");

        final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    AuthFailure(Reason reason) {
        super(reason.message);
    }
}
