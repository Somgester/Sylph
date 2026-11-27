package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalUpdateMonitorTest {

    @TempDir
    Path project;

    @Test
    void changedContentNotifiesOnceEvenWhenTimestampsAndLengthAreUnchanged() throws Exception {
        Path source = write("app/src/main/java/Example.java", "original");
        var timestamp = Files.getLastModifiedTime(source);
        AtomicInteger notifications = new AtomicInteger();
        try (var monitor = new LocalUpdateMonitor(project, notifications::incrementAndGet)) {
            monitor.checkForChanges();
            monitor.checkForChanges();
            assertEquals(0, notifications.get());
            Files.writeString(source, "modified");
            Files.setLastModifiedTime(source, timestamp);
            monitor.checkForChanges();
            assertEquals(1, notifications.get());
            Files.writeString(source, "changed again");
            monitor.checkForChanges();
            assertEquals(1, notifications.get());
        }
    }

    @Test
    void touchBuildOutputsWorkspaceFilesAndDocumentationDoNotTriggerUpdate() throws Exception {
        Path source = write("app/src/main/java/Example.java", "original");
        AtomicInteger notifications = new AtomicInteger();
        try (var monitor = new LocalUpdateMonitor(project, notifications::incrementAndGet)) {
            monitor.checkForChanges();
            Files.writeString(source, "original");
            write("app/build/classes/Example.class", "new build");
            write("docs/guide.md", "new docs");
            write("README.md", "new readme");
            write("my-workspace/file.txt", "unsaved work");
            monitor.checkForChanges();
            assertEquals(0, notifications.get());
        }
    }

    @Test
    void addingAndDeletingSourceFilesAreDetected() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        try (var monitor = new LocalUpdateMonitor(project, notifications::incrementAndGet)) {
            monitor.checkForChanges();
            write("app/src/main/resources/new.css", "new resource");
            monitor.checkForChanges();
            assertEquals(1, notifications.get());
        }
        try (var monitor = new LocalUpdateMonitor(project, notifications::incrementAndGet)) {
            monitor.checkForChanges();
            Files.delete(project.resolve("app/src/main/resources/new.css"));
            monitor.checkForChanges();
            assertEquals(2, notifications.get());
        }
    }

    @Test
    void buildSettingsAndLauncherChangesAreDetected() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        for (String file : new String[] {"app/build.gradle", "gradle/libs.versions.toml", "run.sh", "init.sh"}) {
            try (var monitor = new LocalUpdateMonitor(project, notifications::incrementAndGet)) {
                monitor.checkForChanges();
                write(file, "changed");
                monitor.checkForChanges();
            }
        }
        assertEquals(4, notifications.get());
    }

    @Test
    void closedMonitorDoesNotNotifyAndNewLaunchEstablishesFreshBaseline() throws Exception {
        Path source = write("app/src/main/java/Example.java", "original");
        AtomicInteger notifications = new AtomicInteger();
        var monitor = new LocalUpdateMonitor(project, notifications::incrementAndGet);
        monitor.checkForChanges();
        monitor.close();
        Files.writeString(source, "new version");
        monitor.checkForChanges();
        assertEquals(0, notifications.get());
        try (var nextLaunch = new LocalUpdateMonitor(project, notifications::incrementAndGet)) {
            nextLaunch.checkForChanges();
            nextLaunch.checkForChanges();
            assertEquals(0, notifications.get());
        }
    }

    private Path write(String relative, String contents) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, contents);
    }
}
