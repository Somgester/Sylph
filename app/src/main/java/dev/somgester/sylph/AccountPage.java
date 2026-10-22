package dev.somgester.sylph;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class AccountPage extends ScrollPane implements AutoCloseable {

    private final Label identity = label("", "settings-description");

    private final Label status = label("", "settings-description");

    private final Button signIn = new Button("Sign in / create account");

    private final Button signOut = new Button("Sign out");

    private final Button cancel = new Button("Cancel browser action");

    private final Hyperlink openBrowser = new Hyperlink("Open browser again");

    AccountPage(AccountController controller, Runnable onBack) {
        setId("account-page");
        setFitToWidth(true);
        setFitToHeight(true);
        Label title = label("Account", "settings-title");
        Button back = new Button("← Back to editor");
        back.setId("account-back");
        back.setOnAction(event -> onBack.run());
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        HBox heading = new HBox(16, title, back);
        heading.setAlignment(Pos.CENTER_LEFT);
        Label description = label("Use your browser to sign in with username/password, Google, or GitHub. "
                + "Google and GitHub become available when configured on your Keycloak server.",
                "settings-description");
        identity.setId("account-identity");
        identity.textProperty().bind(Bindings.createStringBinding(() -> {
            var profile = controller.profileProperty().get();
            return profile == null ? "Guest — local files stay available without an account."
                    : profile.name() + (profile.email() == null ? "" : "\n" + profile.email());
        }, controller.profileProperty()));
        status.setId("account-status");
        status.textProperty().bind(controller.statusProperty());
        signIn.setId("account-sign-in");
        signIn.disableProperty().bind(
                controller.busyProperty().or(controller.profileProperty().isNotNull()));
        if (!controller.configured()) {
            signIn.disableProperty().unbind();
            signIn.setDisable(true);
        }
        signIn.setOnAction(event -> controller.signIn());
        signOut.setId("account-sign-out");
        signOut.disableProperty().bind(controller.busyProperty().or(controller.profileProperty().isNull()));
        signOut.setOnAction(event -> controller.signOut());
        cancel.setId("account-cancel");
        cancel.disableProperty().bind(controller.busyProperty().not());
        cancel.setOnAction(event -> controller.cancel());
        openBrowser.setId("account-open-browser");
        openBrowser.visibleProperty().bind(controller.browserUriProperty().isNotNull());
        openBrowser.managedProperty().bind(openBrowser.visibleProperty());
        openBrowser.setOnAction(event -> controller.reopenBrowser());
        VBox card = new VBox(14, identity, new HBox(10, signIn, signOut), cancel, openBrowser, status);
        card.getStyleClass().add("settings-section");
        Label privacy = label("Passwords stay on the sign-in server. Sylph keeps this session in memory only; "
                + "closing the app clears it. Signing out does not close or change your files.",
                "settings-description");
        VBox content = new VBox(20, heading, description, card, privacy);
        content.getStyleClass().add("settings-canvas");
        content.setPadding(new Insets(24, 28, 24, 28));
        setContent(content);
    }

    @Override
    public void close() {
        identity.textProperty().unbind();
        status.textProperty().unbind();
        signIn.disableProperty().unbind();
        signOut.disableProperty().unbind();
        cancel.disableProperty().unbind();
        openBrowser.visibleProperty().unbind();
        openBrowser.managedProperty().unbind();
    }

    private static Label label(String text, String style) {
        Label result = new Label(text);
        result.getStyleClass().add(style);
        result.setWrapText(true);
        return result;
    }
}
