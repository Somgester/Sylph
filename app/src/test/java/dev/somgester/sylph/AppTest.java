package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class AppTest {

    @Test
    void appClassIsFinal() {
        assertTrue(Modifier.isFinal(App.class.getModifiers()));
    }

    @Test
    void privateConstructorCanBeInvokedReflectively() throws Exception {
        Constructor<App> constructor = App.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        constructor.setAccessible(true);
        App instance = constructor.newInstance();

        assertNotNull(instance);
    }
}
