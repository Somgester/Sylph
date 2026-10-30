package dev.somgester.sylph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class KiroSessions {

    @FunctionalInterface
    interface Loader {
        Listing load() throws IOException, InterruptedException;
    }

    record Session(String id, String title, Path directory, Instant updatedAt) {
        Session {
            // IDs become shell input when resuming. Only accept an inert, non-option identifier.
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,255}") || !directory.isAbsolute()) {
                throw new IllegalArgumentException("Invalid Kiro session identifier or directory");
            }
            directory = directory.normalize();
            title = title.replaceAll("\\p{Cntrl}", " ").strip();
        }

        String label() {
            return (title.isBlank() ? "Untitled session" : title) + " — " + directory + " · " + id;
        }

        Session withTitle(String replacement) {
            return new Session(id, replacement, directory, updatedAt);
        }

        String resumeCommand() {
            return "kiro-cli chat --resume-id " + id + "\r";
        }
    }

    record Listing(List<Session> sessions, boolean complete) {
        Listing {
            sessions = List.copyOf(sessions);
        }
    }

    private record Key(Path directory, String id) { }

    private static final int MAX_OUTPUT = 4 * 1024 * 1024;

    private final String executable;

    private final Duration timeout;

    private final KiroSessionNames names;

    KiroSessions() {
        this("kiro-cli", Duration.ofSeconds(10), new KiroSessionNames());
    }

    KiroSessions(String executable, Duration timeout) {
        this(executable, timeout, new KiroSessionNames());
    }

    KiroSessions(String executable, Duration timeout, KiroSessionNames names) {
        this.executable = executable;
        this.timeout = timeout;
        this.names = names;
    }

    Listing list() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable, "chat", "--list-sessions", "--all-cwds", "--format", "json")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        FutureTask<byte[]> output = new FutureTask<>(() -> {
            try (var stream = process.getInputStream()) {
                return stream.readNBytes(MAX_OUTPUT + 1);
            }
        });
        Thread.ofVirtual().name("sylph-kiro-sessions-output").start(output);
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Kiro session listing timed out. Try again.");
            }
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (bytes.length > MAX_OUTPUT) {
                throw new IOException("Kiro session list is too large to load.");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Kiro could not list sessions. Check CLI installation and login.");
            }
            return applyNames(parse(new String(bytes, StandardCharsets.UTF_8)));
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("Unable to read Kiro session list.", failure);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            output.cancel(true);
        }
    }

    void rename(Session session, String name) throws IOException {
        String cleaned = name.replaceAll("\\p{Cntrl}", " ").strip();
        if (cleaned.isBlank() || cleaned.length() > 200) {
            throw new IOException("Session name must contain 1 to 200 characters.");
        }
        try {
            names.put(session.directory(), session.id(), cleaned);
        } catch (BackingStoreException | SecurityException failure) {
            throw new IOException("Unable to save the session name.", failure);
        }
    }

    private Listing applyNames(Listing listing) {
        var sessions = listing.sessions().stream()
                .map(session -> {
                    String name = names.get(session.directory(), session.id());
                    return name.isBlank() ? session : session.withTitle(name);
                }).toList();
        return new Listing(sessions, listing.complete());
    }

    static Listing parse(String json) throws IOException {
        try {
            var root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                throw new IllegalArgumentException("Expected all-workspace session list");
            }
            var sessions = new LinkedHashMap<Key, Session>();
            boolean complete = true;
            for (var element : root.getAsJsonArray()) {
                var workspace = element.getAsJsonObject();
                Path directory = Path.of(string(workspace, "cwd"));
                if (!directory.isAbsolute()) {
                    throw new IllegalArgumentException("Expected an absolute workspace directory");
                }
                if (workspace.has("complete")) {
                    var value = workspace.getAsJsonPrimitive("complete");
                    if (!value.isBoolean()) {
                        throw new IllegalArgumentException("Expected completeness flag");
                    }
                    complete &= value.getAsBoolean();
                }
                for (var item : workspace.getAsJsonArray("sessions")) {
                    var data = item.getAsJsonObject();
                    String title = data.has("title") && !data.get("title").isJsonNull() ? string(data, "title") : "";
                    Instant updated = Instant.EPOCH;
                    if (data.has("updatedAt") && !data.get("updatedAt").isJsonNull()) {
                        try {
                            updated = Instant.parse(string(data, "updatedAt"));
                        } catch (DateTimeParseException ignored) {
                            // Older stores may lack a sortable timestamp; session identity still works.
                        }
                    }
                    var session = new Session(string(data, "sessionId"), title, directory, updated);
                    sessions.putIfAbsent(new Key(session.directory(), session.id()), session);
                }
            }
            var sorted = new ArrayList<>(sessions.values());
            sorted.sort(Comparator.comparing(Session::updatedAt).reversed().thenComparing(Session::id));
            return new Listing(sorted, complete);
        } catch (RuntimeException failure) {
            throw new IOException("Unexpected Kiro session list format. Check your kiro-cli version.", failure);
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected a string field: " + key);
        }
        return value.getAsString();
    }
}
