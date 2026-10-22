package dev.somgester.sylph;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.util.Duration;

/** JavaFX-owned account state. Login failures/cancellation cannot block editing or restore a stale session. */
final class AccountController implements AutoCloseable {

    private final OidcClient client;

    private final Consumer<URI> browser;

    private final ReadOnlyObjectWrapper<OidcClient.Profile> profile = new ReadOnlyObjectWrapper<>();

    private final ReadOnlyObjectWrapper<URI> browserUri = new ReadOnlyObjectWrapper<>();

    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("Guest mode. Local editing is available.");

    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "sylph-auth");
        thread.setDaemon(true);
        return thread;
    });

    private final PauseTransition expiry = new PauseTransition();

    private OidcClient.Session session;

    private Future<?> pending;

    private long revision;

    private boolean closed;

    AccountController(OidcClient client, Consumer<URI> browser) {
        this.client = client;
        this.browser = browser;
    }

    static AccountController environment(Consumer<URI> browser) {
        try {
            return new AccountController(new OidcClient(AuthConfiguration.environment(System.getenv())), browser);
        } catch (IllegalArgumentException error) {
            var controller = new AccountController(null, browser);
            controller.status.set("Authentication configuration is invalid. Check SYLPH_AUTH_* variables.");
            return controller;
        }
    }

    ReadOnlyObjectProperty<OidcClient.Profile> profileProperty() {
        return profile.getReadOnlyProperty();
    }

    ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    ReadOnlyObjectProperty<URI> browserUriProperty() {
        return browserUri.getReadOnlyProperty();
    }

    boolean configured() {
        return client != null;
    }

    void signIn() {
        if (closed || busy.get() || session != null || client == null) {
            return;
        }
        begin(null);
    }

    void signOut() {
        if (closed || busy.get() || session == null) {
            return;
        }
        var previous = session;
        clearSession();
        begin(previous);
    }

    private void begin(OidcClient.Session previous) {
        long requested = ++revision;
        busy.set(true);
        status.set(previous == null ? "Preparing browser sign-in…" : "Signing out in your browser…");
        pending = worker.submit(() -> {
            Consumer<URI> open = uri -> Platform.runLater(() -> {
                if (!closed && requested == revision) {
                    browserUri.set(uri);
                    status.set(previous == null ? "Finish sign-in in your browser." : "Finish browser sign-out.");
                    reopenBrowser();
                }
            });
            try {
                OidcClient.Session signedIn;
                if (previous == null) {
                    signedIn = client.signIn(open);
                } else {
                    client.signOut(previous, open);
                    signedIn = null;
                }
                Platform.runLater(() -> {
                    if (!closed && requested == revision) {
                        finish();
                        session = signedIn;
                        profile.set(signedIn == null ? null : signedIn.profile);
                        status.set(signedIn == null ? "Signed out. Continue editing as guest."
                                : "Signed in as " + signedIn.profile.name() + ".");
                        if (signedIn != null) {
                            double remaining = java.time.Duration.between(Instant.now(), signedIn.profile.expiresAt())
                                    .toMillis();
                            expiry.setDuration(Duration.millis(Math.max(1, remaining)));
                            expiry.setOnFinished(event -> {
                                clearSession();
                                status.set("Sign-in session expired. Local editing remains available.");
                            });
                            expiry.playFromStart();
                        }
                    }
                });
            } catch (Exception error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String diagnostic = error instanceof AuthFailure ? error.getMessage()
                        : "Sign-in failed or was cancelled. Check Keycloak and try again.";
                Platform.runLater(() -> {
                    if (!closed && requested == revision) {
                        finish();
                        // Never include arbitrary provider errors or token responses in UI text.
                        status.set(previous == null ? diagnostic
                                : "Signed out locally. Browser sign-out did not finish; its session may remain.");
                    }
                });
            }
        });
    }

    void reopenBrowser() {
        if (!closed && browserUri.get() != null) {
            try {
                browser.accept(browserUri.get());
            } catch (RuntimeException error) {
                status.set("Could not open your browser. Check the system's default browser and try Open browser.");
            }
        }
    }

    void cancel() {
        if (busy.get()) {
            revision++;
            pending.cancel(true);
            finish();
            status.set("Browser action cancelled. Local editing remains available; browser session may remain.");
        }
    }

    private void finish() {
        browserUri.set(null);
        busy.set(false);
        pending = null;
    }

    private void clearSession() {
        expiry.stop();
        session = null;
        profile.set(null);
    }

    @Override
    public void close() {
        cancel();
        closed = true;
        clearSession();
        worker.shutdownNow();
    }
}
