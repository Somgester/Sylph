package dev.somgester.sylph;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

final class TerminalChooser {

    enum Choice {
        SHELL, HERDR_CLI, HERDR_DESKTOP, CANCEL
    }

    private TerminalChooser() { }

    static Choice choose(Stage owner, boolean inEditor, boolean cliAvailable, boolean desktopAvailable) {
        ButtonType shell = new ButtonType("Shell", ButtonBar.ButtonData.OK_DONE);
        ButtonType cli = new ButtonType("Herdr CLI", ButtonBar.ButtonData.OTHER);
        ButtonType herdr = new ButtonType("Herdr Desktop", ButtonBar.ButtonData.OTHER);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("New Terminal");
        var pane = dialog.getDialogPane();
        pane.getStyleClass().add("terminal-chooser");
        pane.getStylesheets().setAll(owner.getScene().getStylesheets());
        pane.getButtonTypes().add(shell);
        if (cliAvailable) {
            pane.getButtonTypes().add(cli);
        }
        if (desktopAvailable) {
            pane.getButtonTypes().add(herdr);
        }
        pane.getButtonTypes().add(ButtonType.CANCEL);
        Label title = new Label("Choose a terminal");
        title.getStyleClass().add("terminal-chooser-title");
        pane.setContent(title);
        String location = inEditor ? "Open in an editor tab" : "Open in the bottom panel";
        styleCard((Button) pane.lookupButton(shell), "terminal-choice-shell", shell.getText(), location);
        if (cliAvailable) {
            styleCard((Button) pane.lookupButton(cli), "terminal-choice-herdr-cli", cli.getText(), location);
        }
        if (desktopAvailable) {
            styleCard((Button) pane.lookupButton(herdr), "terminal-choice-herdr", herdr.getText(),
                    "Connect in an editor tab");
        }
        Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        cancel.setId("terminal-choice-cancel");
        cancel.getStyleClass().add("terminal-chooser-cancel");
        ButtonBar.setButtonUniformSize(cancel, false);
        dialog.setOnShown(event -> pane.lookupButton(shell).requestFocus());
        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result == shell) {
            return Choice.SHELL;
        }
        if (result == cli) {
            return Choice.HERDR_CLI;
        }
        return result == herdr ? Choice.HERDR_DESKTOP : Choice.CANCEL;
    }

    private static void styleCard(Button button, String id, String name, String detail) {
        Label title = new Label(name);
        title.getStyleClass().add("terminal-choice-name");
        Label subtitle = new Label(detail);
        subtitle.getStyleClass().add("terminal-choice-detail");
        VBox graphic = new VBox(4, title, subtitle);
        graphic.setAlignment(Pos.CENTER_LEFT);
        button.setId(id);
        button.setText("");
        button.setAccessibleText(name + ". " + detail);
        button.setGraphic(graphic);
        button.getStyleClass().add("terminal-choice");
        button.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                button.fire();
                event.consume();
            }
        });
    }
}
