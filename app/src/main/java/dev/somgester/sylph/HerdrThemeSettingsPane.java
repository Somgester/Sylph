package dev.somgester.sylph;

import java.io.IOException;
import java.nio.file.Path;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class HerdrThemeSettingsPane extends TitledPane implements AutoCloseable {

    @FunctionalInterface
    interface Importer {
        void importTheme(String source) throws IOException, InterruptedException;
    }

    private final AppSettings settings;

    private final ComboBox<HerdrCliTheme> theme = new ComboBox<>();

    private Task<Void> pending;

    private boolean closed;

    HerdrThemeSettingsPane(AppSettings settings, Path profile) {
        this(settings, new HerdrThemeImporter(profile)::importTheme);
    }

    HerdrThemeSettingsPane(AppSettings settings, Importer importer) {
        this.settings = settings;
        setText("Herdr Themes");
        setId("settings-herdr-themes");
        setExpanded(false);
        setAnimated(false);
        theme.setId("herdr-cli-theme");
        theme.setAccessibleText("Herdr CLI theme");
        theme.getItems().setAll(HerdrCliTheme.values());
        SettingsPage.followPopupTheme(theme);
        theme.valueProperty().bindBidirectional(settings.herdrThemeProperty());
        Button reset = new Button("Reset");
        reset.setId("herdr-theme-reset");
        reset.setOnAction(event -> settings.herdrThemeProperty().set(HerdrCliTheme.DEFAULT));
        reset.disableProperty().bind(settings.herdrThemeProperty().isEqualTo(HerdrCliTheme.DEFAULT));
        HBox options = new HBox(12, new Label("Theme"), theme, reset);
        options.setAlignment(Pos.CENTER_LEFT);
        TextField source = new TextField();
        source.setId("herdr-theme-source");
        source.setPromptText("Local TOML file path or raw HTTPS URL");
        source.setAccessibleText("Herdr theme file or URL");
        Button importButton = new Button("Import and select");
        importButton.setId("herdr-theme-import");
        Label status = description("Open a new Herdr CLI tab after changing its theme.");
        status.setId("herdr-theme-status");
        VBox controls = new VBox(10, options, source, importButton);
        importButton.disableProperty().bind(source.textProperty().isEmpty());
        importButton.setOnAction(event -> {
            String input = source.getText().strip();
            if (input.isEmpty() || pending != null) {
                return;
            }
            controls.setDisable(true);
            status.setText("Importing theme…");
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws IOException, InterruptedException {
                    importer.importTheme(input);
                    return null;
                }
            };
            pending = task;
            task.setOnSucceeded(done -> {
                if (!closed) {
                    pending = null;
                    controls.setDisable(false);
                    settings.herdrThemeProperty().set(HerdrCliTheme.IMPORTED);
                    status.setText("Theme imported. Open a new Herdr CLI tab to use it.");
                }
            });
            task.setOnFailed(done -> {
                if (!closed) {
                    pending = null;
                    controls.setDisable(false);
                    status.setText("Import failed. Check the file or raw HTTPS URL, theme format, "
                            + "and Python 3.11+/curl installation. Previous theme was kept.");
                }
            });
            Thread.ofVirtual().name("herdr-theme-import").start(task);
        });
        setContent(new VBox(10, description("Built-in and imported themes use a separate Sylph profile."),
                controls, status));
    }

    private static Label description(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-description");
        return label;
    }

    @Override
    public void close() {
        closed = true;
        if (pending != null) {
            pending.cancel(true);
        }
        theme.valueProperty().unbindBidirectional(settings.herdrThemeProperty());
        setContent(null);
    }
}
