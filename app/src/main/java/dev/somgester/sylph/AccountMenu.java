package dev.somgester.sylph;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/** Native account avatar and menu, backed only by the verified in-memory identity. */
final class AccountMenu extends Button implements AutoCloseable {

    private final ObjectProperty<OidcClient.Profile> profile = new SimpleObjectProperty<>();

    private final Runnable openAccount;

    private final ContextMenu menu = new ContextMenu();

    private final MenuItem signIn = new MenuItem("Sign in / create account");

    private final MenuItem signOut = new MenuItem("Sign out");

    AccountMenu(Runnable openAccount) {
        this.openAccount = openAccount;
        setId("open-account");
        getStyleClass().add("chrome-action");
        Node guestIcon = AppIcon.symbol(AppIcon.Symbol.ACCOUNT);
        textProperty().bind(Bindings.createStringBinding(
                () -> profile.get() == null ? "" : initials(profile.get().name()), profile));
        graphicProperty().bind(Bindings.createObjectBinding(
                () -> profile.get() == null ? guestIcon : null, profile));
        accessibleTextProperty().bind(Bindings.createStringBinding(
                () -> profile.get() == null ? "Account: guest" : "Account: " + profile.get().name(), profile));
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(accessibleTextProperty());
        setTooltip(tooltip);

        Label name = new Label();
        name.setId("account-menu-name");
        name.getStyleClass().add("account-menu-name");
        name.setWrapText(true);
        name.textProperty().bind(Bindings.createStringBinding(
                () -> profile.get() == null ? "Guest" : "Logged in as " + profile.get().name(), profile));
        Label email = new Label();
        email.setId("account-menu-email");
        email.getStyleClass().add("account-menu-email");
        email.setWrapText(true);
        email.textProperty().bind(Bindings.createStringBinding(
                () -> profile.get() == null || profile.get().email() == null ? "" : profile.get().email(), profile));
        email.visibleProperty().bind(email.textProperty().isNotEmpty());
        email.managedProperty().bind(email.visibleProperty());
        VBox summary = new VBox(4, name, email);
        summary.getStyleClass().add("account-summary");
        CustomMenuItem identity = new CustomMenuItem(summary, false);
        signIn.setId("account-menu-sign-in");
        signIn.visibleProperty().bind(profile.isNull());
        signIn.setOnAction(event -> openAccount.run());
        signOut.setId("account-menu-sign-out");
        signOut.visibleProperty().bind(profile.isNotNull());
        MenuItem manage = new MenuItem("Account settings…");
        manage.setId("account-menu-settings");
        manage.setOnAction(event -> openAccount.run());
        menu.setId("account-menu");
        menu.getItems().setAll(identity, new SeparatorMenuItem(), signIn, signOut, new SeparatorMenuItem(), manage);
        setContextMenu(menu);
        setOnAction(event -> {
            if (menu.isShowing()) {
                menu.hide();
            } else {
                menu.show(this, Side.BOTTOM, 0, 4);
            }
        });
    }

    void connect(AccountController controller) {
        profile.bind(controller.profileProperty());
        signIn.disableProperty().bind(controller.busyProperty());
        if (!controller.configured()) {
            signIn.disableProperty().unbind();
            signIn.setDisable(true);
        }
        signOut.disableProperty().bind(controller.busyProperty());
        signIn.setOnAction(event -> {
            openAccount.run();
            controller.signIn();
        });
        signOut.setOnAction(event -> controller.signOut());
    }

    static String initials(String name) {
        String[] parts = name.strip().split("\\s+");
        String first = initial(parts[0]);
        return parts.length == 1 ? first : first + initial(parts[parts.length - 1]);
    }

    private static String initial(String part) {
        return new String(Character.toChars(Character.toUpperCase(part.codePointAt(0))));
    }

    @Override
    public void close() {
        menu.hide();
        profile.unbind();
        profile.set(null);
        signIn.disableProperty().unbind();
        signOut.disableProperty().unbind();
        signIn.setOnAction(null);
        signOut.setOnAction(null);
        setOnAction(null);
        menu.getItems().clear();
    }
}
