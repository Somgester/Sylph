# Accounts and browser sign-in

Sylph uses a self-hosted [Keycloak](https://www.keycloak.org/) server for username/password accounts, Google login, and GitHub login. The native JavaFX **Account** page opens the system browser for authentication, then receives a short-lived localhost callback. The editor stays usable as a guest, including when the server is offline. No password or provider client secret is stored in Sylph.

This implements account identity only. It does not upload local files, synchronize settings, grant repository permissions, or protect a future remote API. Such APIs must validate their own access tokens and permissions.

## Start the local server

Requirements: Docker with Docker Compose. This is an opt-in development server; `run.sh`, `dev.sh`, and `init.sh` do not install or start Docker.

```bash
cp auth/.env.example auth/.env
```

Set a strong `KC_BOOTSTRAP_ADMIN_PASSWORD` in `auth/.env`, and keep that file private. It is ignored by Git. A local file has already been generated on the development machine used for implementation; do not overwrite an existing file when following this setup.

```bash
chmod 600 auth/.env
docker compose --env-file auth/.env -f auth/compose.yaml up -d
./run.sh
```

Keycloak 26.8.1 starts at `http://localhost:8080`, published only on the host's loopback interface. Persistent local account data lives in Docker's `sylph-auth_keycloak-data` volume. It imports the `sylph` realm and public `sylph-desktop` client on the first start. Realm edits in the import JSON are not automatically applied to an existing realm.

Open `http://localhost:8080/admin/` and use the bootstrap username/password from `auth/.env`. The default username is `sylph-admin`. Switch from the **master** realm to **sylph** when configuring application users or identity providers. Follow Keycloak's bootstrap-admin guidance to create a permanent administrator for a long-lived installation.

Changing `auth/.env` does not reset an existing administrator's password. If you change the password in the console, update the file with the current credentials before using the integration runner.

In Sylph, click the round account avatar at the top right for sign-in/sign-out actions. After sign-in it shows initials; the menu shows the verified first/last name and a smaller email line. Providers without name claims fall back to the username or subject. Choose **Account settings…**, **View → Account**, or **Ctrl/Cmd+Alt+A** for the full account page. **Sign in / create account** opens Keycloak's browser page. Username/password login and registration work immediately; Google/GitHub appear after their identity providers are configured below. This is a browser password flow, not HTTP Basic authentication or a desktop password-grant flow.

The imported realm enables a 12-character password policy and brute-force protection. Email-based password recovery is disabled until an administrator configures mail delivery and enables it. No SMTP service or paid API is required for the initial local setup.

## Google setup

Use a **Web application** OAuth client in Google Auth Platform because Google redirects to Keycloak, which holds the client secret. This is different from connecting a desktop OAuth client directly to Google.

For this local setup, authorize this exact redirect URI:

```text
http://localhost:8080/realms/sylph/broker/google/endpoint
```

In Keycloak's `sylph` realm:

1. Open **Identity providers → Add provider → Google**.
2. Keep the alias `google`.
3. Enter Google's client ID and client secret in Keycloak, then save and enable the provider.
4. Confirm that the Redirect URI displayed by Keycloak exactly matches Google's configured URI.

If Google's consent screen is in testing mode, add your test Google account there. Provider secrets belong in Keycloak, never in application source, Gradle properties, or desktop configuration.

## GitHub setup

Create a GitHub OAuth App with this authorization callback URL:

```text
http://localhost:8080/realms/sylph/broker/github/endpoint
```

In Keycloak's `sylph` realm, add the **GitHub** identity provider with alias `github`, enter the GitHub client ID and secret, save, and enable it. Use the Redirect URI displayed by Keycloak as the source of truth. Keep the default account-linking protections; matching an email address alone must not silently merge accounts.

After configuring either provider, use **Sign in / create account** in Sylph and choose that provider on the browser page. Installing or configuring an OAuth client does not itself verify a live sign-in; test with an actual provider account. Implementation verification covers real Keycloak password login/logout, not live Google/GitHub consent with owner credentials.

## Browser return to Sylph

All three login methods return from Keycloak to:

```text
http://127.0.0.1:43117/oauth/callback
```

Browser logout returns to:

```text
http://127.0.0.1:43117/oauth/logout
```

These are configured on the Keycloak desktop client. They are **not** the Google/GitHub provider callback URIs. Listeners bind only `127.0.0.1`, run only during an active browser operation, and close after success, cancellation, timeout, or app shutdown. The fixed port means two simultaneous Sylph authentication attempts cannot share it.

Authentication uses authorization code flow, S256 PKCE, random single-use state and nonce, exact callback paths, and verified EdDSA (Ed25519) or RS256 ID tokens. Token checks include signature, issuer, audience, nonce, expiry, subject, issue time, and authorized party. The public desktop client has no client secret. Discovery endpoints must remain on the configured issuer's origin, and external servers require HTTPS.

If browser login reaches Sylph's callback but the app remains signed out, check **sylph realm → Clients → sylph-desktop → Advanced → ID Token Signature Algorithm**. Sylph supports **EdDSA with an Ed25519 signing key** and **RS256**. The imported realm and client default to EdDSA; existing realms are not changed by editing the import file. EdDSA verification uses Nimbus JOSE + JWT with its optional open-source Tink dependency. Only matching keys from the configured issuer's JWKS are trusted, and all identity-claim checks apply to both algorithms. Sylph distinguishes an unsupported signing algorithm from code-exchange and other token-verification failures without displaying tokens or provider response bodies.

Sign-in state lives only in memory and clears when its ID token expires or Sylph exits. Reload Window retains the current in-memory account. No refresh/remember-me storage is implemented in this first chunk. **Sign out** clears native account state immediately and opens Keycloak's browser logout. Cancelling/failing browser logout leaves the app as guest, but the browser's server session may remain. Logout does not sign out the underlying Google/GitHub website account.

The page provides **Cancel browser action** and **Open browser again**. Leaving Account for the editor does not stop a pending sign-in. Raw provider/token errors are never rendered, and token material is not written to preferences or files.

## Configuration

Set these before launching Sylph when using another server/client/port:

| Environment variable | Default |
| --- | --- |
| `SYLPH_AUTH_ISSUER` | `http://localhost:8080/realms/sylph` |
| `SYLPH_AUTH_CLIENT_ID` | `sylph-desktop` |
| `SYLPH_AUTH_CALLBACK_PORT` | `43117` |

Update Keycloak's exact valid login and post-logout redirect URIs if changing the callback port. Invalid configuration disables sign-in without blocking guest editing.

The supplied Compose configuration uses Keycloak development mode and its local development database. It is for localhost testing. A shared deployment needs a stable HTTPS hostname, a supported production database, backups, and production Keycloak configuration. Provider callback URIs must then use that server's HTTPS hostname instead of localhost.

## Checks and shutdown

`./test.sh` runs headless fixture tests for the native UI/lifecycle and signed OIDC flows, including cancellation/retry, guest safety, state/nonce/issuer/audience checks, expired/forged tokens, replay, and timeouts. Normal tests do not require Docker or contact an identity provider.

To test against the running local Keycloak server:

```bash
python3 scripts/test-auth.py
```

The runner uses local admin credentials, creates a disposable non-admin user, exercises a browser-style password form without opening a desktop browser, validates identity through the actual token/JWKS endpoints, verifies logout and a fresh login, and removes the test user in a `finally` block. Its integration task is separate from regular checks. The admin password grant is used only for this local fixture provisioner; it is disabled on the Sylph desktop client.

```bash
docker compose --env-file auth/.env -f auth/compose.yaml stop
```

Stopping the server preserves users and data. Do not remove the data volume unless intentionally resetting the development accounts.

[Back to documentation](README.md)
