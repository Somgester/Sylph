package dev.somgester.sylph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Stores restore references only; conversation contents remain in the agent's own storage. */
final class HerdrSessionStore {

    record Tab(String herdrSessionId, String kiroSessionId, Path directory,
            boolean inEditor, HerdrCliTheme theme) {
        Tab {
            if (herdrSessionId == null || !herdrSessionId.matches("sylph-[a-z0-9][a-z0-9_-]{0,57}")) {
                throw new IllegalArgumentException("Invalid Sylph Herdr session identifier");
            }
            if (kiroSessionId != null && !kiroSessionId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,255}")) {
                throw new IllegalArgumentException("Invalid Kiro session identifier");
            }
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(theme, "theme");
            if (!directory.isAbsolute()) {
                throw new IllegalArgumentException("Session directory must be absolute");
            }
            directory = directory.normalize();
        }
    }

    record State(List<Tab> tabs, String activeSessionId) {
        State {
            tabs = List.copyOf(tabs);
            if (tabs.size() > 100) {
                throw new IllegalArgumentException("Too many saved Herdr tabs");
            }
            Set<String> ids = new HashSet<>();
            for (Tab tab : tabs) {
                if (!ids.add(tab.herdrSessionId())) {
                    throw new IllegalArgumentException("Duplicate Herdr session identifier");
                }
            }
            if (activeSessionId != null && !ids.contains(activeSessionId)) {
                throw new IllegalArgumentException("Active session must refer to a saved tab");
            }
        }

        static State empty() {
            return new State(List.of(), null);
        }
    }

    private static final int MAX_BYTES = 1024 * 1024;

    private final Path file;

    HerdrSessionStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    synchronized State load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return State.empty();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Herdr session state must be a regular file.");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Herdr session state exceeds the size limit.");
        }
        try {
            JsonObject root = JsonParser.parseString(StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString()).getAsJsonObject();
            requireFields(root, Set.of("version", "tabs", "activeSessionId"));
            JsonElement version = root.get("version");
            if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                    || !version.getAsString().equals("1")) {
                throw new IllegalArgumentException("Unsupported session-state version");
            }
            JsonArray tabs = root.getAsJsonArray("tabs");
            var loaded = new java.util.ArrayList<Tab>();
            if (tabs.size() > 100) {
                throw new IllegalArgumentException("Too many saved Herdr tabs");
            }
            for (JsonElement element : tabs) {
                JsonObject tab = element.getAsJsonObject();
                requireFields(tab, Set.of("herdrSessionId", "kiroSessionId", "directory", "inEditor", "theme"));
                JsonElement inEditor = tab.get("inEditor");
                if (!inEditor.isJsonPrimitive() || !inEditor.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("Expected placement flag");
                }
                loaded.add(new Tab(string(tab.get("herdrSessionId")), nullableString(tab.get("kiroSessionId")),
                        Path.of(string(tab.get("directory"))), inEditor.getAsBoolean(),
                        HerdrCliTheme.valueOf(string(tab.get("theme")))));
            }
            return new State(loaded, nullableString(root.get("activeSessionId")));
        } catch (RuntimeException malformed) {
            throw new IOException("Cannot read Herdr session state; original file was kept.", malformed);
        }
    }

    synchronized void save(State state) throws IOException {
        Objects.requireNonNull(state, "state");
        // Never replace an invalid or newer-format file with a blank/default state.
        load();
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray tabs = new JsonArray();
        for (Tab saved : state.tabs()) {
            JsonObject tab = new JsonObject();
            tab.addProperty("herdrSessionId", saved.herdrSessionId());
            tab.add("kiroSessionId", jsonString(saved.kiroSessionId()));
            tab.addProperty("directory", saved.directory().toString());
            tab.addProperty("inEditor", saved.inEditor());
            tab.addProperty("theme", saved.theme().name());
            tabs.add(tab);
        }
        root.add("tabs", tabs);
        root.add("activeSessionId", jsonString(state.activeSessionId()));
        byte[] bytes = (root + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Herdr session state exceeds the size limit.");
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".herdr-sessions-", ".tmp");
        try {
            if (Files.getFileAttributeView(temporary, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            }
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireFields(JsonObject object, Set<String> fields) {
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException("Unexpected session-state fields");
        }
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected a session-state string");
        }
        return value.getAsString();
    }

    private static String nullableString(JsonElement value) {
        return value != null && value.isJsonNull() ? null : string(value);
    }

    private static JsonElement jsonString(String value) {
        return value == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(value);
    }
}
