package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LocalUpdateNoticeTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @Test
    void noticeIsNonmodalTracksBothThemesAndDoesNotAlterEditorText() throws Exception {
        FxTestSupport.onFxThread(() -> {
            TextArea editor = new TextArea("unsaved work");
            Stage owner = new Stage();
            Scene scene = new Scene(editor, 400, 200);
            owner.setScene(scene);
            String dark = LocalUpdateNoticeTest.class.getResource("/styles/dark.css").toExternalForm();
            String light = LocalUpdateNoticeTest.class.getResource("/styles/light.css").toExternalForm();
            String shell = LocalUpdateNoticeTest.class.getResource("/styles/shell.css").toExternalForm();
            scene.getStylesheets().setAll(dark, shell);
            owner.show();
            AtomicBoolean dismissed = new AtomicBoolean();
            Alert notice = LocalUpdateNotice.create(owner, () -> dismissed.set(true));
            try {
                assertEquals(Modality.NONE, notice.getModality());
                assertEquals(java.util.List.of(ButtonType.OK), notice.getButtonTypes());
                notice.show();
                var pane = notice.getDialogPane();
                pane.applyCss();
                assertEquals(Color.web("#0d0f12"), pane.getBackground().getFills().getFirst().getFill());
                scene.getStylesheets().setAll(light, shell);
                pane.applyCss();
                assertEquals(Color.web("#faf9f5"), pane.getBackground().getFills().getFirst().getFill());
                assertEquals("unsaved work", editor.getText());
                assertFalse(editor.isDisabled());
                notice.close();
                assertTrue(dismissed.get());
                scene.getStylesheets().setAll(dark, shell);
                assertEquals(java.util.List.of(light, shell), pane.getStylesheets());
            } finally {
                notice.close();
                owner.hide();
            }
            return null;
        });
    }
}
