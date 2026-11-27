package dev.somgester.sylph;

import javafx.beans.binding.Bindings;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Stage;

final class LocalUpdateNotice {

    private LocalUpdateNotice() { }

    static Alert create(Stage owner, Runnable onClose) {
        Alert notice = new Alert(Alert.AlertType.INFORMATION,
                "Local Sylph code has changed. Save your work, close Sylph, then launch it again "
                        + "from the desktop icon or run ./run.sh to build and use the update.\n\n"
                        + "Your current window keeps running its existing code. "
                        + "Reload Window does not install updates.", ButtonType.OK);
        notice.initOwner(owner);
        notice.initModality(Modality.NONE);
        notice.setTitle("Sylph update available");
        notice.setHeaderText("Restart Sylph to use local changes");
        var styles = owner.getScene().getStylesheets();
        var pane = notice.getDialogPane();
        pane.getStyleClass().add("local-update-notice");
        Bindings.bindContent(pane.getStylesheets(), styles);
        notice.setOnHidden(event -> {
            Bindings.unbindContent(pane.getStylesheets(), styles);
            onClose.run();
        });
        return notice;
    }
}
