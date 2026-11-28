package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class HerdrKiroResumeTest {

    @TempDir
    Path directory;

    @Test
    void runningOrRestoringAgentsPreventDuplicateLaunch() {
        for (String fields : new String[] {"\"agent\":\"kiro\"", "\"launch_pending\":true",
                "\"agent_session\":{\"value\":\"exact-id\"}"}) {
            var snapshot = JsonParser.parseString("{\"panes\":[{\"pane_id\":\"w1:p1\"," + fields + "}]}")
                    .getAsJsonObject();
            assertEquals(null, HerdrKiroResume.idlePane(snapshot, directory));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void legacySavedIdUsesExactResumeArgumentOnceThroughReadyShellApi() throws Exception {
        Path helper = directory.resolve("herdr");
        Path log = directory.resolve("argv.log");
        Files.writeString(helper, """
                #!/bin/sh
                if [ "$3" = api ]; then
                    printf '%s\n' "$RESTORE_SNAPSHOT"
                else
                    printf '%s\n' "$@" >> "$RESTORE_LOG"
                fi
                """);
        assertTrue(helper.toFile().setExecutable(true));
        var saved = new HerdrSessionStore.Tab("sylph-original", "exact-id", directory, true, HerdrCliTheme.NORD);
        var environment = new java.util.HashMap<>(System.getenv());
        environment.put("RESTORE_LOG", log.toString());
        var snapshot = new com.google.gson.JsonObject();
        var pane = new com.google.gson.JsonObject();
        pane.addProperty("pane_id", "w1:p1");
        pane.addProperty("cwd", directory.toString());
        var agents = new com.google.gson.JsonArray();
        agents.add(pane);
        snapshot.add("panes", agents);
        environment.put("RESTORE_SNAPSHOT", snapshot.toString());
        var failures = new ArrayList<String>();
        HerdrKiroResume.restore(helper, saved, environment, new CompletableFuture<>(), failures::add)
                .get(5, TimeUnit.SECONDS);
        String arguments = Files.readString(log);
        assertTrue(arguments.contains("--session\nsylph-original\nagent\nstart\n"));
        assertTrue(arguments.endsWith("--\n--resume-id\nexact-id\n"));
        assertEquals(1, arguments.split("--resume-id", -1).length - 1);
        assertTrue(failures.isEmpty());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void missingResumeCommandIsRecoveredInItsOwnPaneBesideARunningChat() throws Exception {
        Path helper = directory.resolve("herdr");
        Path log = directory.resolve("argv.log");
        Files.writeString(helper, """
                #!/bin/sh
                if [ "$3" = api ]; then
                    printf '%s' "$SNAPSHOT"
                else
                    printf '%s\n' "$@" >> "$LOG"
                fi
                """);
        assertTrue(helper.toFile().setExecutable(true));
        var chats = Map.of("w1:p1", new HerdrSessionStore.Chat("running", directory),
                "w1:p2", new HerdrSessionStore.Chat("restore-me", directory));
        var saved = new HerdrSessionStore.Tab("sylph-test", "running", directory, true, HerdrCliTheme.NORD, chats);
        String snapshot = """
                {"agents":[{"pane_id":"w1:p1","agent":"kiro"}],"panes":[
                  {"pane_id":"w1:p1","agent":"kiro"},{"pane_id":"w1:p2","cwd":%s},
                  {"pane_id":"w1:p3","cwd":%s}]}
                """.formatted(new com.google.gson.Gson().toJson(directory.toString()),
                        new com.google.gson.Gson().toJson(directory.toString()));
        var failures = new ArrayList<String>();
        HerdrKiroResume.restore(helper, saved, Map.of("LOG", log.toString(), "SNAPSHOT", snapshot),
                new CompletableFuture<>(), failures::add).get(5, TimeUnit.SECONDS);
        assertEquals(1, Files.readAllLines(log).stream().filter("--resume-id"::equals).count());
        assertTrue(Files.readAllLines(log).contains("w1:p2"));
        assertTrue(Files.readString(log).endsWith("restore-me\n"));
        assertTrue(failures.isEmpty());
    }

    @Test
    void shellInAnotherDirectoryIsNotUsedForResume() {
        var snapshot = JsonParser.parseString("{\"panes\":[{\"pane_id\":\"w1:p1\",\"cwd\":\"/elsewhere\"}]}")
                .getAsJsonObject();
        assertEquals("", HerdrKiroResume.idlePane(snapshot, directory));
    }

    @Test
    void absentKiroIdDoesNotInventResumeCommand() throws Exception {
        var saved = new HerdrSessionStore.Tab("sylph-original", null, directory, true, HerdrCliTheme.DEFAULT);
        HerdrKiroResume.restore(directory.resolve("missing"), saved, Map.of(), new CompletableFuture<>(),
                message -> { throw new AssertionError(message); }).get(1, TimeUnit.SECONDS);
        assertFalse(Files.exists(directory.resolve("argv.log")));
    }
}
