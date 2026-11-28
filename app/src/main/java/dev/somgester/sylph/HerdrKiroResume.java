package dev.somgester.sylph;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Fallback for saved Kiro IDs captured before Herdr learned their native resume command. */
final class HerdrKiroResume {

    private HerdrKiroResume() { }

    static CompletableFuture<Void> restore(Path executable, HerdrSessionStore.Tab saved,
            Map<String, String> environment, CompletableFuture<Void> terminalClosed, Consumer<String> onFailure) {
        if (saved.kiroSessionId() == null && saved.paneSessions().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                var pending = new HashMap<>(saved.paneSessions());
                if (pending.isEmpty()) {
                    pending.put("", new HerdrSessionStore.Chat(saved.kiroSessionId(), saved.directory()));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline && !terminalClosed.isDone()) {
                    try {
                        String output = run(executable, saved, environment, List.of("api", "snapshot"));
                        JsonObject root = JsonParser.parseString(output).getAsJsonObject();
                        if (root.has("result")) {
                            root = root.getAsJsonObject("result");
                        }
                        if (root.has("snapshot")) {
                            root = root.getAsJsonObject("snapshot");
                        }
                        var iterator = pending.entrySet().iterator();
                        while (iterator.hasNext() && !terminalClosed.isDone()) {
                            var entry = iterator.next();
                            String pane = idlePane(root, entry.getValue().directory(), entry.getKey());
                            if (pane == null) {
                                iterator.remove();
                            } else if (!pane.isEmpty()) {
                                iterator.remove();
                                try {
                                    run(executable, saved, environment, List.of("agent", "start",
                                            "sylph-restore-" + pane.replace(':', '-'), "--kind", "kiro",
                                            "--pane", pane, "--timeout", "5000",
                                            "--", "--resume-id", entry.getValue().sessionId()));
                                } catch (IOException failed) {
                                    onFailure.accept("Could not resume Kiro in " + pane + ". Saved ID was kept.");
                                }
                            }
                        }
                        if (pending.isEmpty()) {
                            return;
                        }
                    } catch (IOException starting) {
                        // The PTY client and server start asynchronously. Wait for its API to be ready.
                    }
                    Thread.sleep(200);
                }
                if (!terminalClosed.isDone()) {
                    onFailure.accept("Kiro could not be resumed automatically. Saved session ID was kept.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException unexpected) {
                onFailure.accept("Could not inspect the Herdr restore state. Saved Kiro session ID was kept.");
            }
        }, task -> Thread.ofVirtual().name("sylph-kiro-restore").start(task));
    }

    // null: an agent owns the session; empty: no pane ready; otherwise an idle shell pane ID.
    static String idlePane(JsonObject snapshot, Path directory) {
        return idlePane(snapshot, directory, "");
    }

    private static String idlePane(JsonObject snapshot, Path directory, String target) {
        String first = "";
        // The agents list omits idle shells. The panes list includes occupied and idle panes.
        for (var element : snapshot.getAsJsonArray("panes")) {
            JsonObject agent = element.getAsJsonObject();
            if (!target.isEmpty() && !target.equals(agent.get("pane_id").getAsString())) {
                continue;
            }
            if ((agent.has("launch_pending") && agent.get("launch_pending").getAsBoolean())
                    || (agent.has("agent") && !agent.get("agent").isJsonNull())
                    || (agent.has("agent_session") && !agent.get("agent_session").isJsonNull())) {
                return null;
            }
            var cwd = agent.has("foreground_cwd") && !agent.get("foreground_cwd").isJsonNull()
                    ? agent.get("foreground_cwd") : agent.get("cwd");
            if (agent.has("pane_id") && cwd != null && !cwd.isJsonNull()
                    && Path.of(cwd.getAsString()).normalize().equals(directory.normalize())) {
                first = agent.get("pane_id").getAsString();
            }
        }
        return first;
    }

    private static String run(Path executable, HerdrSessionStore.Tab saved, Map<String, String> environment,
            List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(executable.toString(), "--session", saved.herdrSessionId()));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(saved.directory().toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process = builder.start();
        FutureTask<byte[]> output = new FutureTask<>(() -> process.getInputStream().readNBytes(524289));
        Thread.ofVirtual().start(output);
        try {
            process.getOutputStream().close();
            if (!process.waitFor(7, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Herdr restore command unavailable");
            }
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (bytes.length > 524288) {
                throw new IOException("Herdr restore output exceeds limit");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IOException("Could not read Herdr restore response", failure);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            output.cancel(true);
        }
    }
}
