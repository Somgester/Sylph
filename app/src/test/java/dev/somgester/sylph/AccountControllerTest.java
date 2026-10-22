package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AccountControllerTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @Test
    void loginAndServerLogoutUpdateAccountStateWithoutKeepingTokensInProperties() throws Exception {
        try (var server = new OidcTestServer()) {
            var account = FxTestSupport.onFxThread(() -> {
                var controller = new AccountController(new OidcClient(server.configuration), server::browser);
                controller.signIn();
                controller.signIn();
                return controller;
            });
            try {
                FxTestSupport.await(() -> !account.busyProperty().get());
                FxTestSupport.onFxThread(() -> {
                    assertEquals("stable-user-id", account.profileProperty().get().subject());
                    assertNull(account.browserUriProperty().get());
                    assertFalse(account.statusProperty().get().contains("eyJ"));
                    account.signOut();
                    assertNull(account.profileProperty().get());
                    return null;
                });
                FxTestSupport.await(() -> !account.busyProperty().get());
                assertEquals(1, server.exchanges.get());
                FxTestSupport.onFxThread(() -> {
                    assertTrue(account.statusProperty().get().startsWith("Signed out."));
                    return null;
                });
            } finally {
                FxTestSupport.onFxThread(() -> { account.close(); return null; });
            }
        }
    }

    @Test
    void cancelledAttemptCannotRestoreSessionAndRetryReusesReleasedPort() throws Exception {
        try (var server = new OidcTestServer()) {
            AtomicBoolean respond = new AtomicBoolean();
            var account = FxTestSupport.onFxThread(() -> {
                var controller = new AccountController(new OidcClient(server.configuration), uri -> {
                    if (respond.get()) {
                        server.browser(uri);
                    }
                });
                controller.signIn();
                return controller;
            });
            try {
                FxTestSupport.await(() -> account.browserUriProperty().get() != null);
                FxTestSupport.onFxThread(() -> {
                    account.cancel();
                    assertFalse(account.busyProperty().get());
                    assertNull(account.profileProperty().get());
                    respond.set(true);
                    account.signIn();
                    return null;
                });
                FxTestSupport.await(() -> !account.busyProperty().get());
                FxTestSupport.onFxThread(() -> {
                    assertEquals("caveman", account.profileProperty().get().name());
                    return null;
                });
                assertEquals(1, server.exchanges.get());
            } finally {
                FxTestSupport.onFxThread(() -> { account.close(); return null; });
            }
        }
    }

    @Test
    void expiredSessionReturnsToGuestAndBrowserFailureCanBeCancelled() throws Exception {
        try (var server = new OidcTestServer()) {
            server.claims = builder -> builder.expirationTime(Date.from(Instant.now().plusSeconds(2)));
            var account = FxTestSupport.onFxThread(() -> {
                var controller = new AccountController(new OidcClient(server.configuration), server::browser);
                controller.signIn();
                return controller;
            });
            try {
                FxTestSupport.await(() -> account.profileProperty().get() != null);
                FxTestSupport.await(() -> account.profileProperty().get() == null);
                FxTestSupport.onFxThread(() -> {
                    assertTrue(account.statusProperty().get().contains("expired"));
                    return null;
                });
            } finally {
                FxTestSupport.onFxThread(() -> { account.close(); return null; });
            }
            var unavailable = FxTestSupport.onFxThread(() -> {
                var controller = new AccountController(new OidcClient(server.configuration, Duration.ofSeconds(5)),
                        uri -> { throw new IllegalStateException("No desktop browser"); });
                controller.signIn();
                return controller;
            });
            try {
                FxTestSupport.await(() -> unavailable.statusProperty().get().startsWith("Could not open"));
                FxTestSupport.onFxThread(() -> {
                    unavailable.cancel();
                    assertNull(unavailable.profileProperty().get());
                    assertFalse(unavailable.busyProperty().get());
                    return null;
                });
            } finally {
                FxTestSupport.onFxThread(() -> { unavailable.close(); return null; });
            }
        }
    }

    @Test
    void edDsaLoginCreatesVisibleAccountSession() throws Exception {
        try (var server = new OidcTestServer()) {
            server.edDsa = true;
            var account = FxTestSupport.onFxThread(() -> {
                var controller = new AccountController(new OidcClient(server.configuration), server::browser);
                controller.signIn();
                return controller;
            });
            try {
                FxTestSupport.await(() -> !account.busyProperty().get());
                FxTestSupport.onFxThread(() -> {
                    assertEquals("caveman", account.profileProperty().get().name());
                    assertTrue(account.statusProperty().get().startsWith("Signed in"));
                    return null;
                });
            } finally {
                FxTestSupport.onFxThread(() -> { account.close(); return null; });
            }
        }
    }
}
