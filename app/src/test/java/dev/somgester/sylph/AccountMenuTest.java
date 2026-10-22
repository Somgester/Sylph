package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountMenuTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dark", "light"})
    void avatarMenuFollowsVerifiedLoginAndLogoutAndUsesThemeColors(String theme) throws Exception {
        try (var server = new OidcTestServer()) {
            server.edDsa = true;
            server.claims = builder -> builder.claim("given_name", "Cave").claim("family_name", "Man");
            var controller = FxTestSupport.onFxThread(
                    () -> new AccountController(new OidcClient(server.configuration), server::browser));
            AtomicInteger openedPage = new AtomicInteger();
            var avatar = FxTestSupport.onFxThread(() -> {
                var button = new AccountMenu(openedPage::incrementAndGet);
                button.connect(controller);
                return button;
            });
            var stage = FxTestSupport.onFxThread(() -> {
                Stage window = new Stage();
                var root = new BorderPane(avatar);
                root.setId("root");
                Scene scene = new Scene(root, 400, 300);
                scene.getStylesheets().setAll(getClass().getResource("/styles/" + theme + ".css").toExternalForm(),
                        getClass().getResource("/styles/shell.css").toExternalForm());
                window.setScene(scene);
                window.show();
                return window;
            });
            try {
                FxTestSupport.onFxThread(() -> {
                    avatar.fire();
                    assertTrue(avatar.getContextMenu().isShowing());
                    assertEquals("Guest", summary(avatar, "account-menu-name").getText());
                    assertFalse(summary(avatar, "account-menu-email").isManaged());
                    assertTrue(item(avatar, "account-menu-sign-in").isVisible());
                    assertFalse(item(avatar, "account-menu-sign-out").isVisible());
                    avatar.getContextMenu().hide();
                    item(avatar, "account-menu-sign-in").fire();
                    assertTrue(item(avatar, "account-menu-sign-in").isDisable());
                    return null;
                });
                FxTestSupport.await(() -> !controller.busyProperty().get());
                FxTestSupport.onFxThread(() -> {
                    assertEquals(1, openedPage.get());
                    assertEquals("CM", avatar.getText());
                    assertNull(avatar.getGraphic());
                    assertEquals("Account: Cave Man", avatar.getAccessibleText());
                    avatar.fire();
                    var root = avatar.getContextMenu().getScene().getRoot();
                    root.applyCss();
                    root.layout();
                    Label name = summary(avatar, "account-menu-name");
                    Label email = summary(avatar, "account-menu-email");
                    assertEquals("Logged in as Cave Man", name.getText());
                    assertEquals("cave@example.test", email.getText());
                    assertTrue(email.isManaged());
                    assertTrue(email.getFont().getSize() < name.getFont().getSize());
                    assertEquals(Color.web(theme.equals("dark") ? "#cbd5e1" : "#413c36"), name.getTextFill());
                    assertFalse(item(avatar, "account-menu-sign-in").isVisible());
                    assertTrue(item(avatar, "account-menu-sign-out").isVisible());
                    avatar.getContextMenu().hide();
                    item(avatar, "account-menu-sign-out").fire();
                    assertNull(controller.profileProperty().get());
                    assertEquals("", avatar.getText());
                    assertTrue(avatar.getGraphic() != null);
                    assertEquals("Guest", summary(avatar, "account-menu-name").getText());
                    assertFalse(summary(avatar, "account-menu-email").isVisible());
                    return null;
                });
                FxTestSupport.await(() -> !controller.busyProperty().get());
            } finally {
                FxTestSupport.onFxThread(() -> {
                    avatar.close();
                    controller.close();
                    stage.hide();
                    return null;
                });
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"Cave Man, CM", "caveman, C", "Cave Middle Man, CM", "Éva 山田, É山"})
    void initialsUseFirstAndLastNamesIncludingUnicode(String name, String expected) {
        assertEquals(expected, AccountMenu.initials(name));
    }

    private static Label summary(AccountMenu avatar, String id) {
        var content = ((CustomMenuItem) avatar.getContextMenu().getItems().getFirst()).getContent();
        return (Label) content.lookup("#" + id);
    }

    private static MenuItem item(AccountMenu avatar, String id) {
        return avatar.getContextMenu().getItems().stream().filter(value -> id.equals(value.getId()))
                .findFirst().orElseThrow();
    }
}
