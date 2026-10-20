package dev.somgester.sylph;

import javafx.beans.InvalidationListener;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;

final class LanguageServerSettingsPane extends TitledPane implements AutoCloseable {

    private final LanguageServerSettings settings;

    private final Label status = description("");

    private final Label persistence = description("");

    private final CheckBox enabled = new CheckBox("Enable Python language server");

    private final TextField executable = new TextField();

    private final InvalidationListener changed = observable -> load();

    LanguageServerSettingsPane(LanguageServerSettings settings, LanguageServerManager manager) {
        this.settings = settings;
        setId("settings-language-servers");
        setText("Language servers — Python");
        setExpanded(false);
        setAnimated(false);
        enabled.setId("python-server-enabled");
        executable.setId("python-server-executable");
        executable.setPromptText("pyright-langserver or absolute executable path");
        executable.setAccessibleText("Pyright executable");
        var validation = description("");
        validation.setId("python-server-validation");
        validation.visibleProperty().bind(validation.textProperty().isNotEmpty());
        validation.managedProperty().bind(validation.visibleProperty());
        persistence.visibleProperty().bind(persistence.textProperty().isNotEmpty());
        persistence.managedProperty().bind(persistence.visibleProperty());
        var apply = new Button("Apply and restart");
        apply.setId("python-server-apply");
        apply.setOnAction(event -> {
            try {
                settings.apply(LanguageServerSettings.Server.PYTHON,
                        new LanguageServerSettings.Configuration(enabled.isSelected(), executable.getText()));
                validation.setText("");
            } catch (IllegalArgumentException error) {
                validation.setText(error.getMessage());
            }
        });
        status.setId("python-server-status");
        status.textProperty().bind(manager.status(LanguageServerSettings.Server.PYTHON));
        persistence.textProperty().bind(settings.persistenceStatusProperty());
        setContent(new VBox(10, description(LanguageServerSettings.Server.PYTHON.label
                + ": completion, diagnostics, hover, and definitions."),
                enabled, new Label("Executable"), executable,
                description("Install Pyright separately with npm install --global pyright. Node.js is required. "
                        + "Choose its executable here; arguments are supplied automatically."),
                apply, validation, status, persistence));
        settings.configuration(LanguageServerSettings.Server.PYTHON).addListener(changed);
        load();
    }

    private void load() {
        var config = settings.configuration(LanguageServerSettings.Server.PYTHON).get();
        enabled.setSelected(config.enabled());
        executable.setText(config.executable());
    }

    private static Label description(String text) {
        var label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-description");
        return label;
    }

    @Override
    public void close() {
        settings.configuration(LanguageServerSettings.Server.PYTHON).removeListener(changed);
        status.textProperty().unbind();
        persistence.textProperty().unbind();
        setContent(null);
    }
}
