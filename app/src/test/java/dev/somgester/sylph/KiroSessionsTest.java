package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KiroSessionsTest {

    @TempDir
    Path directory;

    @Test
    void parsesAllWorkspacesSortsNewestFirstAndDeduplicatesStoreEntries() throws Exception {
        String json = """
                [{"cwd":"/workspace/first","sessions":[
                  {"sessionId":"old-id","title":"Old","updatedAt":"2026-01-01T00:00:00Z"},
                  {"sessionId":"old-id","title":"Old","updatedAt":"2026-01-01T00:00:00Z"}
                ],"complete":true},
                {"cwd":"/workspace/second folder","sessions":[
                  {"sessionId":"new-id","title":"Fix colors","updatedAt":"2026-10-17T00:00:00Z"}
                ],"complete":true}]
                """;
        var listing = KiroSessions.parse(json);
        assertTrue(listing.complete());
        assertEquals(List.of("new-id", "old-id"), listing.sessions().stream().map(KiroSessions.Session::id).toList());
        var session = listing.sessions().getFirst();
        assertEquals(Path.of("/workspace/second folder"), session.directory());
        assertTrue(session.label().contains("Fix colors"));
        assertEquals("kiro-cli chat --resume-id new-id\r", session.resumeCommand());
    }

    @Test
    void partialAndEmptyListingsAreDistinctAndMissingTitlesHaveAFallback() throws Exception {
        var empty = KiroSessions.parse("[]");
        assertTrue(empty.complete());
        assertTrue(empty.sessions().isEmpty());
        var partial = KiroSessions.parse("""
                [{"cwd":"/workspace","sessions":[{"sessionId":"id","title":null}],"complete":false}]
                """);
        assertFalse(partial.complete());
        assertTrue(partial.sessions().getFirst().label().startsWith("Untitled session"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad;command", "bad\ncommand", "$(command)", "--option", "bad%PATH%", "bad\"quote"})
    void rejectsSessionIdentifiersThatCouldBecomeShellCommands(String id) {
        assertThrows(IllegalArgumentException.class,
                () -> new KiroSessions.Session(id, "Title", directory, java.time.Instant.EPOCH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "{}", "[{}]", "[{\"cwd\":\"relative\",\"sessions\":[]}]",
            "[{\"cwd\":\"/workspace\",\"sessions\":[{\"sessionId\":true}]}]"})
    void rejectsMalformedOutputInsteadOfClaimingThereAreNoSessions(String json) {
        assertThrows(IOException.class, () -> KiroSessions.parse(json));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void commandUsesDocumentedListArgumentsAndDoesNotStartAnInteractiveChat() throws Exception {
        Path executable = script("""
                test "$#" -eq 5 || exit 2
                test "$1 $2 $3 $4 $5" = 'chat --list-sessions --all-cwds --format json' || exit 3
                printf '[]'
                """);
        assertTrue(new KiroSessions(executable.toString(), Duration.ofSeconds(2)).list().sessions().isEmpty());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void processErrorsAndMissingExecutableAreReported() throws Exception {
        Path executable = script("echo 'not logged in' >&2\nexit 1\n");
        assertThrows(IOException.class, () -> new KiroSessions(executable.toString(), Duration.ofSeconds(2)).list());
        assertThrows(IOException.class,
                () -> new KiroSessions(directory.resolve("missing").toString(), Duration.ofSeconds(2)).list());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void hungCommandTimesOut() throws Exception {
        Path executable = script("sleep 30\n");
        IOException error = assertThrows(IOException.class,
                () -> new KiroSessions(executable.toString(), Duration.ofMillis(100)).list());
        assertTrue(error.getMessage().contains("timed out"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void cancellingAListingInterruptsAndStopsItsProcess() throws Exception {
        Path executable = script("printf '%s' \"$$\" > \"$0.pid\"\nsleep 30\n");
        CountDownLatch interrupted = new CountDownLatch(1);
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                new KiroSessions(executable.toString(), Duration.ofSeconds(20)).list();
            } catch (InterruptedException expected) {
                interrupted.countDown();
            }
            return null;
        });
        Thread thread = Thread.ofPlatform().daemon().start(task);
        Path pidFile = Path.of(executable + ".pid");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(pidFile));
            thread.interrupt();
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            task.get(3, TimeUnit.SECONDS);
            long pid = Long.parseLong(Files.readString(pidFile));
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent()) {
                handle.orElseThrow().onExit().get(3, TimeUnit.SECONDS);
                assertFalse(handle.orElseThrow().isAlive());
            }
        } finally {
            thread.interrupt();
        }
    }

    private Path script(String body) throws IOException {
        Path executable = Files.writeString(directory.resolve("kiro-cli"), "#!/bin/sh\n" + body);
        assertTrue(executable.toFile().setExecutable(true));
        return executable;
    }
}
