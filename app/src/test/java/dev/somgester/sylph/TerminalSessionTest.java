package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class TerminalSessionTest {

    private static final class Capture implements TerminalSession.Listener {
        private final StringBuffer output = new StringBuffer();

        private final CountDownLatch started = new CountDownLatch(1);

        private final CountDownLatch ended = new CountDownLatch(1);

        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Integer exitCode;

        @Override
        public void started() {
            started.countDown();
        }

        @Override
        public void output(String text) {
            output.append(text);
        }

        @Override
        public void ended(Integer code, Throwable error) {
            exitCode = code;
            failure.set(error);
            ended.countDown();
        }
    }

    @TempDir
    Path directory;

    private final List<TerminalSession> sessions = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        sessions.forEach(TerminalSession::close);
        for (TerminalSession session : sessions) {
            session.completion().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shellSelectionUsesAnExecutablePathWithoutParsingItAsACommand() throws Exception {
        assertEquals(List.of("/bin/sh", "-i"), TerminalSession.shell(Map.of("SHELL", "missing --argument"), "Linux"));
        assertEquals(List.of("/bin/sh", "-i"), TerminalSession.shell(Map.of("SHELL", "bad\0path"), "Linux"));
        assertEquals(List.of("C:\\shell folder\\cmd.exe"),
                TerminalSession.shell(Map.of("ComSpec", "C:\\shell folder\\cmd.exe"), "Windows 11"));
        Path executable = Files.writeString(directory.resolve("shell with spaces"), "");
        if (executable.toFile().setExecutable(true)) {
            assertEquals(List.of(executable.toString(), "-i"),
                    TerminalSession.shell(Map.of("SHELL", executable.toString()), "Linux"));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void realShellHasTtyWorkingDirectoryUnicodeAndAnsiOutput() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("workspace with spaces"));
        Capture capture = new Capture();
        TerminalSession session = start(folder, capture);
        session.send("test -t 0 && test -t 1 && printf 'real-tty\\n'; pwd; "
                + "printf 'λ ☃\\n'; printf '\\033[31mred\\033[0m\\n'\r");
        await(() -> capture.output.toString().contains("\u001b[31mred\u001b[0m"));
        assertTrue(capture.output.toString().contains("real-tty"));
        assertTrue(capture.output.toString().contains(folder.toString()));
        assertTrue(capture.output.toString().contains("λ ☃"));
        session.send("exit 7\r");
        assertTrue(capture.ended.await(5, TimeUnit.SECONDS));
        assertEquals(7, capture.exitCode);
        assertEquals(null, capture.failure.get());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resizeReachesThePtyAndControlCInterruptsForegroundCommands() throws Exception {
        Capture capture = new Capture();
        TerminalSession session = start(directory, capture);
        session.resize(72, 19);
        session.send("stty size; printf 'sleep-started\\n'; sleep 30\r");
        await(() -> capture.output.toString().contains("sleep-started"));
        assertTrue(capture.output.toString().contains("19 72"));
        session.send("\u0003");
        session.send("printf 'interrupt-worked\\n'\r");
        await(() -> capture.output.toString().contains("interrupt-worked"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void closingTerminalStopsShellAndItsRunningChild() throws Exception {
        Capture capture = new Capture();
        TerminalSession session = start(directory, capture);
        session.send("echo $$ > shell.pid; sleep 30 & echo $! > child.pid; wait\r");
        await(() -> pidReady(directory.resolve("child.pid")));
        long shell = Long.parseLong(Files.readString(directory.resolve("shell.pid")).trim());
        long child = Long.parseLong(Files.readString(directory.resolve("child.pid")).trim());
        session.close();
        session.completion().get(5, TimeUnit.SECONDS);
        await(() -> !alive(shell) && !alive(child));
        assertFalse(alive(shell));
        assertFalse(alive(child));
        session.send("ignored\r");
        session.resize(80, 24);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void inputQueuedBeforeProcessRegistrationIsDeliveredAfterStartup() throws Exception {
        var environment = new HashMap<>(System.getenv());
        environment.put("ENV", "/dev/null");
        TerminalSession session = new TerminalSession(directory, List.of("/bin/sh", "-i"), environment);
        sessions.add(session);
        session.send("printf 'queued-input' > startup-input\r");
        session.start(new Capture());
        await(() -> {
            try {
                return Files.readString(directory.resolve("startup-input")).equals("queued-input");
            } catch (java.io.IOException notWrittenYet) {
                return false;
            }
        });
    }

    @Test
    void closingBeforeStartupDoesNotLaunchAShell() throws Exception {
        TerminalSession session = new TerminalSession(directory, List.of("not-a-shell"), Map.of());
        sessions.add(session);
        Capture capture = new Capture();
        session.close();
        session.start(capture);
        session.completion().get(5, TimeUnit.SECONDS);
        assertEquals(1, capture.started.getCount());
        assertEquals(1, capture.ended.getCount());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void closingWhileNativeStartupIsPendingLeavesNoShellBehind() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            Path pid = directory.resolve("startup-" + attempt + ".pid");
            TerminalSession session = new TerminalSession(directory,
                    List.of("/bin/sh", "-c", "echo $$ > " + pid.getFileName() + "; exec sleep 30"), System.getenv());
            sessions.add(session);
            session.start(new Capture());
            session.close();
            session.completion().get(5, TimeUnit.SECONDS);
            if (Files.exists(pid)) {
                assertFalse(alive(Long.parseLong(Files.readString(pid).trim())));
            }
        }
    }

    @Test
    void startupFailureIsReportedAndCompletesCleanup() throws Exception {
        TerminalSession session = new TerminalSession(directory.resolve("missing"), List.of("not-a-shell"), Map.of());
        sessions.add(session);
        Capture capture = new Capture();
        session.start(capture);
        assertTrue(capture.ended.await(5, TimeUnit.SECONDS));
        session.completion().get(5, TimeUnit.SECONDS);
        assertNotNull(capture.failure.get());
        assertTrue(capture.failure.get().getMessage().contains("no longer exists"));
    }

    private TerminalSession start(Path folder, Capture capture) throws Exception {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("ENV", "/dev/null");
        environment.put("PS1", "");
        TerminalSession session = new TerminalSession(folder, List.of("/bin/sh", "-i"), environment);
        sessions.add(session);
        session.start(capture);
        assertTrue(capture.started.await(5, TimeUnit.SECONDS));
        session.send("stty -echo; printf '\\122\\105\\101\\104\\131\\n'\r");
        await(() -> capture.output.toString().contains("READY"));
        return session;
    }

    private static boolean pidReady(Path file) {
        try {
            return Files.readString(file).trim().matches("[0-9]+");
        } catch (java.io.IOException notWrittenYet) {
            return false;
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for terminal process");
    }
}
