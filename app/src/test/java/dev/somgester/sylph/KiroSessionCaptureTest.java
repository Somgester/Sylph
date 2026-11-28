package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KiroSessionCaptureTest {

    @TempDir
    Path directory;

    @Test
    void exactIdsStayWithTheirOpenTabsEvenWhenBothUseSameFolder() throws Exception {
        var store = new HerdrSessionStore(directory.resolve("state.json"));
        try (var tracker = new HerdrTabTracker(store, message -> { });
                var capture = new KiroSessionCapture(tracker::captured)) {
            tracker.opened(tab("sylph-first"));
            tracker.opened(tab("sylph-second"));
            assertEquals(204, post(capture, payload("sylph-first", "kiro-one"), true));
            assertEquals(204, post(capture, payload("sylph-second", "kiro-two"), true));
            assertEquals("kiro-one", store.load().tabs().get(0).kiroSessionId());
            assertEquals("kiro-two", store.load().tabs().get(1).kiroSessionId());
            tracker.removed("sylph-first");
            assertEquals(410, post(capture, payload("sylph-first", "late-id"), true));
            tracker.flushed().get(5, TimeUnit.SECONDS);
            assertEquals(1, store.load().tabs().size());
            assertEquals("kiro-two", store.load().tabs().getFirst().kiroSessionId());
        }
    }

    @Test
    void reopeningOneInternalPaneReplacesOnlyItsOwnConversation() throws Exception {
        var store = new HerdrSessionStore(directory.resolve("last-session.json"));
        try (var tracker = new HerdrTabTracker(store, message -> { });
                var capture = new KiroSessionCapture(tracker::captured)) {
            var tab = tab("sylph-first");
            tracker.opened(tab);
            for (String[] report : new String[][] {{"w1:p1", "first"}, {"w1:p2", "second"}, {"w1:p1", "third"}}) {
                var event = com.google.gson.JsonParser.parseString(payload(tab.herdrSessionId(), report[1]))
                        .getAsJsonObject();
                event.addProperty("paneId", report[0]);
                assertEquals(204, post(capture, event.toString(), true));
            }
            tracker.opened(tab);
            tracker.flushed().get(5, TimeUnit.SECONDS);
            var chats = store.load().tabs().getFirst().paneSessions();
            assertEquals(2, chats.size());
            assertEquals("third", chats.get("w1:p1").sessionId());
            assertEquals("second", chats.get("w1:p2").sessionId());
        }
    }

    @Test
    void unauthorizedMalformedAndOversizedEventsCannotChangeSavedState() throws Exception {
        var store = new HerdrSessionStore(directory.resolve("state.json"));
        try (var tracker = new HerdrTabTracker(store, message -> { });
                var capture = new KiroSessionCapture(tracker::captured)) {
            tracker.opened(tab("sylph-first"));
            tracker.flushed().get(5, TimeUnit.SECONDS);
            assertEquals(403, post(capture, payload("sylph-first", "kiro-one"), false));
            assertEquals(400, post(capture, "not JSON", true));
            assertEquals(400, post(capture, payload("sylph-first", "bad;command"), true));
            assertEquals(413, post(capture, "x".repeat(16385), true));
            assertEquals(410, post(capture, payload("sylph-unknown", "kiro-one"), true));
            assertEquals(null, store.load().tabs().getFirst().kiroSessionId());
        }
    }

    @Test
    void bundledHookReportsToReplacementListenerUsingSameConnectionFile() throws Exception {
        Process check;
        try {
            check = new ProcessBuilder("python3", "--version").start();
        } catch (java.io.IOException missing) {
            assumeTrue(false, "Python 3 is required for the hook integration fixture");
            return;
        }
        assumeTrue(check.waitFor(5, TimeUnit.SECONDS) && check.exitValue() == 0);
        Path helper = directory.resolve("hook with spaces.py");
        try (var input = getClass().getResourceAsStream("/herdr/kiro-session-hook.py")) {
            Files.copy(input, helper);
        }
        Path endpoint = directory.resolve("kiro-capture.json");
        var store = new HerdrSessionStore(directory.resolve("state.json"));
        try (var tracker = new HerdrTabTracker(store, message -> { })) {
            tracker.opened(tab("sylph-first"));
            Map<String, String> inherited;
            try (var initial = new KiroSessionCapture(endpoint, tracker::captured)) {
                inherited = initial.environment();
            }
            try (var replacement = new KiroSessionCapture(endpoint, tracker::captured)) {
                ProcessBuilder builder = new ProcessBuilder("python3", helper.toString());
                builder.environment().keySet().removeIf(name -> name.startsWith("HERDR_"));
                builder.environment().putAll(inherited);
                builder.environment().put("SYLPH_HERDR_SESSION_ID", "sylph-first");
                Process process = builder.start();
                try (var output = process.getOutputStream()) {
                    output.write(("{\"hook_event_name\":\"SessionStart\",\"session_id\":\"exact-kiro-id\","
                            + "\"cwd\":" + new com.google.gson.Gson().toJson(directory.toString()) + "}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                try {
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                    assertEquals(0, process.exitValue(), new String(process.getErrorStream().readAllBytes()));
                } finally {
                    process.destroyForcibly();
                }
                assertEquals("exact-kiro-id", store.load().tabs().getFirst().kiroSessionId());
                assertEquals(inherited, replacement.environment());
            }
        }
    }

    private HerdrSessionStore.Tab tab(String id) {
        return new HerdrSessionStore.Tab(id, null, directory, true, HerdrCliTheme.DEFAULT);
    }

    private String payload(String herdr, String kiro) {
        var object = new com.google.gson.JsonObject();
        object.addProperty("herdrSessionId", herdr);
        object.addProperty("kiroSessionId", kiro);
        object.addProperty("directory", directory.toString());
        return object.toString();
    }

    private static int post(KiroSessionCapture capture, String json, boolean authorized) throws Exception {
        var environment = capture.environment();
        var request = HttpRequest.newBuilder(URI.create(environment.get("SYLPH_KIRO_CAPTURE_URL")))
                .timeout(java.time.Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(json));
        if (authorized) {
            request.header("Authorization", "Bearer " + environment.get("SYLPH_KIRO_CAPTURE_TOKEN"));
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
