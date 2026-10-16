package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;

final class TerminalWorkspace extends SplitPane implements AutoCloseable {

    private final TabPane editors = new TabPane();

    private final TabPane panel = new TabPane();

    private final Tab document = new Tab();

    private final Map<Tab, TerminalView> terminals = new IdentityHashMap<>();

    private final Function<Path, TerminalView> createTerminal;

    private final Runnable focusDocument;

    private int nextId = 1;

    private boolean panelVisible;

    private boolean closed;

    @SuppressWarnings("this-escape")
    TerminalWorkspace(EditorSession session, Function<Path, TerminalView> createTerminal, Runnable focusDocument) {
        this.createTerminal = createTerminal;
        this.focusDocument = focusDocument;
        setId("terminal-workspace");
        setOrientation(Orientation.VERTICAL);
        editors.setId("editor-tabs");
        panel.setId("terminal-panel");
        editors.setMinSize(100, 100);
        panel.setMinSize(100, 100);
        document.setId("document-tab");
        document.setClosable(false);
        document.textProperty().bind(Bindings.createStringBinding(() -> {
            Path path = session.pathProperty().get();
            return (path == null ? "Untitled" : path.getFileName().toString())
                    + (session.dirtyProperty().get() ? " •" : "");
        }, session.pathProperty(), session.dirtyProperty()));
        editors.getTabs().add(document);
        editors.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        panel.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        installTabLifecycle(editors);
        installTabLifecycle(panel);
        getItems().add(editors);
    }

    private void installTabLifecycle(TabPane pane) {
        pane.getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) {
                for (Tab removed : change.getRemoved()) {
                    TerminalView terminal = terminals.remove(removed);
                    if (terminal != null) {
                        terminal.close();
                    }
                    removed.setContent(null);
                }
            }
            if (pane == panel && pane.getTabs().isEmpty()) {
                panelVisible = false;
                getItems().remove(panel);
                focusSelection(editors);
            }
        });
        pane.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) ->
                focusSelection(pane));
    }

    private void focusSelection(TabPane pane) {
        if (closed) {
            return;
        }
        TerminalView terminal = terminals.get(pane.getSelectionModel().getSelectedItem());
        if (terminal != null) {
            terminal.focusTerminal();
        } else if (pane == editors) {
            Platform.runLater(() -> {
                if (!closed && getScene() != null && editors.getSelectionModel().getSelectedItem() == document) {
                    focusDocument.run();
                }
            });
        }
    }

    void setDocument(Node node) {
        document.setContent(node);
    }

    void selectDocument() {
        editors.getSelectionModel().select(document);
    }

    void togglePanel(Path directory) {
        if (panelVisible) {
            panelVisible = false;
            getItems().remove(panel);
            focusSelection(editors);
        } else if (panel.getTabs().isEmpty()) {
            open(directory, false);
        } else {
            showPanel();
            TerminalView selected = terminals.get(panel.getSelectionModel().getSelectedItem());
            if (selected != null) {
                selected.focusTerminal();
            }
        }
    }

    void open(Path directory, boolean inEditor) {
        if (closed) {
            return;
        }
        TabPane pane = inEditor ? editors : panel;
        TerminalView view = createTerminal.apply(directory);
        Tab tab = new Tab("Terminal " + nextId++, view);
        tab.setTooltip(new Tooltip(directory.toString()));
        Button duplicate = new Button("⧉");
        duplicate.getStyleClass().add("terminal-duplicate");
        duplicate.setAccessibleText("Duplicate " + tab.getText());
        duplicate.setTooltip(new Tooltip("Duplicate terminal: fresh shell in the same starting folder"));
        duplicate.setOnAction(event -> open(view.session().directory(), inEditor));
        tab.setGraphic(duplicate);
        terminals.put(tab, view);
        pane.getTabs().add(tab);
        pane.getSelectionModel().select(tab);
        if (!inEditor) {
            showPanel();
        }
        view.focusTerminal();
    }

    private void showPanel() {
        panelVisible = true;
        if (!getItems().contains(panel)) {
            getItems().add(panel);
            Platform.runLater(() -> {
                if (!closed && panelVisible) {
                    setDividerPositions(0.65);
                }
            });
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            terminals.values().forEach(TerminalView::close);
            terminals.clear();
            document.textProperty().unbind();
            editors.getTabs().clear();
            panel.getTabs().clear();
            getItems().clear();
        }
    }
}
