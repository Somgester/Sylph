package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HerdrThemeSettingsPaneTest {

    @BeforeAll
    static void initializeJavaFx() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @Test
    void successfulImportSelectsAndPersistsThemeOnlyAfterWorkerCompletes() throws Exception {
        var store = new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2, HerdrCliTheme.NORD));
        var settings = new AppSettings(store);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> imported = new AtomicReference<>();
        var pane = FxTestSupport.onFxThread(() -> new HerdrThemeSettingsPane(settings, source -> {
            assertFalse(Platform.isFxApplicationThread());
            imported.set(source);
            started.countDown();
            release.await();
        }));
        try {
            beginImport(pane);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            FxTestSupport.onFxThread(() -> {
                assertEquals(HerdrCliTheme.NORD, settings.herdrThemeProperty().get());
                assertTrue(control(pane, "#herdr-theme-import", Button.class).isDisabled());
                return null;
            });
            release.countDown();
            FxTestSupport.await(() -> settings.herdrThemeProperty().get() == HerdrCliTheme.IMPORTED);
            FxTestSupport.onFxThread(() -> {
                assertEquals(HerdrCliTheme.IMPORTED, new AppSettings(store).herdrThemeProperty().get());
                assertTrue(control(pane, "#herdr-theme-status", Label.class).getText().contains("Open a new"));
                ((Button) pane.getContent().lookup("#herdr-theme-reset")).fire();
                assertEquals(HerdrCliTheme.DEFAULT, settings.herdrThemeProperty().get());
                return null;
            });
            assertEquals("/tmp/theme with spaces.toml", imported.get());
        } finally {
            release.countDown();
            close(pane);
        }
    }

    @Test
    void failedImportKeepsSelectionAndAllowsRetry() throws Exception {
        var settings = new AppSettings(new AppSettingsTest.MemoryStore(
                new AppSettings.Values(true, false, 2, HerdrCliTheme.NORD)));
        var pane = FxTestSupport.onFxThread(() -> new HerdrThemeSettingsPane(settings, source -> {
            throw new IOException("secret URL must not be displayed");
        }));
        try {
            beginImport(pane);
            FxTestSupport.await(() -> control(pane, "#herdr-theme-status", Label.class)
                    .getText().startsWith("Import failed"));
            FxTestSupport.onFxThread(() -> {
                assertEquals(HerdrCliTheme.NORD, settings.herdrThemeProperty().get());
                assertFalse(control(pane, "#herdr-theme-import", Button.class).isDisabled());
                assertFalse(control(pane, "#herdr-theme-status", Label.class).getText().contains("secret"));
                return null;
            });
        } finally {
            close(pane);
        }
    }

    @Test
    void closingSettingsCancelsPendingImportWithoutChangingSelection() throws Exception {
        var settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2)));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        var pane = FxTestSupport.onFxThread(() -> new HerdrThemeSettingsPane(settings, source -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException cancelled) {
                interrupted.set(true);
                throw cancelled;
            } finally {
                stopped.countDown();
            }
        }));
        try {
            beginImport(pane);
            assertTrue(started.await(5, TimeUnit.SECONDS));
        } finally {
            close(pane);
        }
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
        FxTestSupport.onFxThread(() -> {
            assertEquals(HerdrCliTheme.DEFAULT, settings.herdrThemeProperty().get());
            return null;
        });
    }

    private static void beginImport(HerdrThemeSettingsPane pane) throws Exception {
        FxTestSupport.onFxThread(() -> {
            control(pane, "#herdr-theme-source", TextField.class).setText("/tmp/theme with spaces.toml");
            control(pane, "#herdr-theme-import", Button.class).fire();
            return null;
        });
    }

    private static <T> T control(HerdrThemeSettingsPane pane, String id, Class<T> type) {
        return type.cast(pane.getContent().lookup(id));
    }

    private static void close(HerdrThemeSettingsPane pane) throws Exception {
        FxTestSupport.onFxThread(() -> {
            pane.close();
            return null;
        });
    }
}
