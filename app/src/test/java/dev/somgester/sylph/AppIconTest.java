package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppIconTest {

    @BeforeAll
    static void initialize() throws Exception {
        FxTestSupport.initialize();
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 24, 32, 64, 128, 256})
    void logoAndWindowIconStayCentredAndUnclippedAtEverySize(int size) throws Exception {
        FxTestSupport.onFxThread(() -> {
            var logo = AppIcon.logo(size);
            logo.resize(size, size);
            logo.layout();
            var mark = logo.lookup(".sylph-mark");
            var bounds = logo.sceneToLocal(mark.localToScene(mark.getBoundsInLocal()));
            assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= size);
            assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= size);
            assertEquals(size / 2.0, bounds.getCenterX(), 0.6);
            assertEquals(size / 2.0, bounds.getCenterY(), 0.6);
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            Image image = size == 64 ? AppIcon.windowIcon() : logo.snapshot(parameters, null);
            assertEquals(size, image.getWidth());
            assertEquals(size, image.getHeight());
            for (int pixel = 0; pixel < size; pixel++) {
                assertEquals(0, image.getPixelReader().getColor(pixel, 0).getOpacity());
                assertEquals(0, image.getPixelReader().getColor(pixel, size - 1).getOpacity());
                assertEquals(0, image.getPixelReader().getColor(0, pixel).getOpacity());
                assertEquals(0, image.getPixelReader().getColor(size - 1, pixel).getOpacity());
            }
            int visible = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (image.getPixelReader().getColor(x, y).getOpacity() > 0) {
                        visible++;
                    }
                }
            }
            assertTrue(visible > size * size / 10, "The icon must contain visible artwork");
            return null;
        });
    }
}
