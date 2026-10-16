package dev.somgester.sylph;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class TerminalSession implements AutoCloseable {

    interface Listener {
        void started();

        // May block until the renderer consumes this chunk, keeping output memory bounded.
        void output(String text) throws InterruptedException;

        void ended(Integer exitCode, Throwable failure);
    }

    private final Path directory;

    private final List<String> command;

    private final Map<String, String> environment;

    private final ExecutorService input = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("sylph-terminal-input").factory());

    private final AtomicBoolean started = new AtomicBoolean();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private final CompletableFuture<Void> launched = new CompletableFuture<>();

    private volatile PtyProcess process;

    private volatile Throwable inputFailure;

    private volatile int columns = 80;

    private volatile int rows = 24;

    TerminalSession(Path directory) {
        this(directory, shell(System.getenv(), System.getProperty("os.name")), System.getenv());
    }

    TerminalSession(Path directory, List<String> command, Map<String, String> environment) {
        this.directory = directory.toAbsolutePath().normalize();
        this.command = List.copyOf(command);
        this.environment = new HashMap<>(environment);
        this.environment.put("TERM", "xterm-256color");
        this.environment.put("COLORTERM", "truecolor");
        this.environment.put("TERM_PROGRAM", "Sylph");
    }

    static List<String> shell(Map<String, String> environment, String operatingSystem) {
        if (operatingSystem.startsWith("Windows")) {
            return List.of(environment.getOrDefault("ComSpec", "cmd.exe"));
        }
        String configured = environment.get("SHELL");
        if (configured != null && !configured.isBlank()) {
            try {
                Path executable = Path.of(configured);
                if (executable.isAbsolute() && Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                    return List.of(configured, "-i");
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // An invalid inherited SHELL must not prevent the fallback shell from starting.
            }
        }
        return List.of("/bin/sh", "-i");
    }

    Path directory() {
        return directory;
    }

    CompletableFuture<Void> completion() {
        return completion;
    }

    void start(Listener listener) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Terminal session already started");
        }
        Thread.ofPlatform().daemon().name("sylph-terminal-reader").start(() -> run(listener));
    }

    private void run(Listener listener) {
        Integer exitCode = null;
        Throwable failure = null;
        try {
            if (closed.get()) {
                return;
            }
            if (!Files.isDirectory(directory)) {
                throw new IOException("Terminal folder no longer exists: " + directory);
            }
            process = new PtyProcessBuilder(command.toArray(String[]::new))
                    .setEnvironment(environment).setDirectory(directory.toString())
                    .setInitialColumns(columns).setInitialRows(rows).setRedirectErrorStream(true).start();
            launched.complete(null);
            if (closed.get()) {
                return;
            }
            process.setWinSize(new WinSize(columns, rows));
            listener.started();
            try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[32 * 1024];
                int count;
                while (!closed.get() && (count = reader.read(buffer)) != -1) {
                    while (count < buffer.length && reader.ready()) {
                        int additional = reader.read(buffer, count, buffer.length - count);
                        if (additional == -1) {
                            break;
                        }
                        count += additional;
                    }
                    listener.output(new String(buffer, 0, count));
                }
            }
            if (!closed.get()) {
                exitCode = process.waitFor();
            }
        } catch (Exception | LinkageError error) {
            if (!closed.get()) {
                failure = error;
            }
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            launched.complete(null);
            input.shutdownNow();
            try {
                terminate();
                if (!closed.get()) {
                    listener.ended(exitCode, inputFailure == null ? failure : inputFailure);
                }
            } finally {
                completion.complete(null);
            }
        }
    }

    void send(String text) {
        send(text.getBytes(StandardCharsets.UTF_8));
    }

    void send(byte[] bytes) {
        if (closed.get()) {
            return;
        }
        try {
            input.execute(() -> {
                try {
                    launched.join();
                    if (!closed.get() && process != null && process.isAlive()) {
                        process.getOutputStream().write(bytes);
                        process.getOutputStream().flush();
                    }
                } catch (IOException failure) {
                    if (!closed.get() && process.isAlive()) {
                        inputFailure = failure;
                        terminate();
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The shell ended between checking its state and queuing input.
        }
    }

    void resize(int newColumns, int newRows) {
        if (newColumns < 2 || newRows < 1 || newColumns > 1000 || newRows > 1000 || closed.get()) {
            return;
        }
        columns = newColumns;
        rows = newRows;
        PtyProcess current = process;
        if (current != null && current.isAlive()) {
            try {
                input.execute(() -> {
                    if (!closed.get() && current.isAlive()) {
                        try {
                            current.setWinSize(new WinSize(columns, rows));
                        } catch (RuntimeException failure) {
                            if (current.isAlive()) {
                                inputFailure = failure;
                                terminate();
                            }
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // A resize arriving after shell exit has no work to do.
            }
        }
    }

    private synchronized void terminate() {
        PtyProcess current = process;
        if (current == null) {
            return;
        }
        List<ProcessHandle> descendants = ProcessHandle.of(current.pid())
                .map(handle -> handle.descendants().toList()).orElse(List.of());
        descendants.reversed().forEach(ProcessHandle::destroy);
        current.destroy();
        try {
            if (!current.waitFor(300, TimeUnit.MILLISECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            current.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally {
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            for (Closeable stream : List.of(current.getOutputStream(), current.getInputStream(),
                    current.getErrorStream())) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // One already-closed stream must not prevent closing the remaining handles.
                }
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            input.shutdownNow();
            // Native process termination and its grace period must never block the JavaFX thread.
            if (!started.get()) {
                launched.complete(null);
                completion.complete(null);
            }
            Thread.ofPlatform().name("sylph-terminal-close").start(() -> {
                // A close during native startup must also dispose the process once it is registered.
                launched.join();
                terminate();
            });
        }
    }
}
