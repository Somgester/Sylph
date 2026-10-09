package dev.somgester.sylph;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

final class FxTestSupport {

    private FxTestSupport() { }

    static void initialize() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            latch.countDown();
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out initializing JavaFX");
        }
        Platform.setImplicitExit(false);
    }

    static <T> T onFxThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (onFxThread(condition)) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for JavaFX state");
    }
}
