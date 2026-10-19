package dev.somgester.sylph;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Separate-process fixture: validates actual stdio framing without depending on LSP4J's server implementation. */
public final class FakeLanguageServer {

    private FakeLanguageServer() { }

    public static void main(String[] args) throws Exception {
        Path log = Path.of(args[0]);
        String mode = args[2];
        var started = new JsonObject();
        started.addProperty("method", "test/started");
        started.addProperty("pid", ProcessHandle.current().pid());
        started.addProperty("directory", Path.of("").toAbsolutePath().toString());
        record(log, started);
        var pending = new java.util.LinkedHashMap<JsonObject, String>();
        while (true) {
            var header = new ByteArrayOutputStream();
            int value;
            while ((value = System.in.read()) != -1) {
                header.write(value);
                if (header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                    break;
                }
            }
            if (value == -1) {
                return;
            }
            int length = -1;
            for (String line : header.toString(StandardCharsets.US_ASCII).split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            var message = JsonParser.parseString(new String(System.in.readNBytes(length), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            record(log, message);
            String method = message.get("method").getAsString();
            if (method.equals("initialize")) {
                if (mode.equals("hang-initialize")) {
                    Thread.sleep(30_000);
                }
                if (mode.equals("crash")) {
                    System.exit(7);
                }
                reply(message, "{\"capabilities\":" + args[1] + "}");
            } else if (method.equals("initialized") && mode.equals("block-input")) {
                Thread.sleep(30_000);
            } else if (mode.startsWith("features") && method.startsWith("textDocument/")) {
                if (method.equals("textDocument/didOpen")) {
                    var document = message.getAsJsonObject("params").getAsJsonObject("textDocument");
                    diagnostics(document, document.get("version").getAsInt(), mode);
                } else if (method.equals("textDocument/didChange")) {
                    var document = message.getAsJsonObject("params").getAsJsonObject("textDocument");
                    if (!mode.equals("features-delayed")) {
                        diagnostics(document, mode.equals("features-stale-diagnostics") ? 1
                                : document.get("version").getAsInt(), mode);
                    }
                    for (var entry : pending.entrySet()) {
                        reply(entry.getKey(), entry.getValue());
                    }
                    pending.clear();
                } else if (message.has("id")) {
                    String result = featureResult(message, mode, log);
                    if (mode.equals("features-delayed")) {
                        pending.put(message, result);
                    } else {
                        reply(message, result);
                    }
                }
            } else if (method.equals("textDocument/hover") && mode.equals("reply-hover")) {
                reply(message, "{\"contents\":{\"kind\":\"plaintext\",\"value\":\"type information\"}}");
            } else if (method.equals("shutdown") && !mode.equals("hang-shutdown")) {
                reply(message, "null");
            } else if (method.equals("exit")) {
                return;
            }
            // Hover requests deliberately stay pending until the client cancels them.
        }
    }

    private static String featureResult(JsonObject request, String mode, Path log) {
        String method = request.get("method").getAsString();
        var document = request.getAsJsonObject("params").getAsJsonObject("textDocument");
        String uri = document.get("uri").getAsString();
        if (method.equals("textDocument/hover")) {
            return "{\"contents\":{\"kind\":\"markdown\",\"value\":\"<b>type information</b>\"}}";
        }
        if (method.equals("textDocument/completion")) {
            var position = request.getAsJsonObject("params").getAsJsonObject("position");
            int line = position.get("line").getAsInt();
            int end = position.get("character").getAsInt();
            String range = "{\"start\":{\"line\":" + line + ",\"character\":" + Math.max(0, end - 2)
                    + "},\"end\":{\"line\":" + line + ",\"character\":" + end + "}}";
            return "{\"isIncomplete\":false,\"items\":[{\"label\":\"alphaServer\",\"textEdit\":{\"range\":" + range
                    + ",\"newText\":\"alphaServer\"},\"additionalTextEdits\":[{\"range\":{\"start\":{\"line\":0,"
                    + "\"character\":0},\"end\":{\"line\":0,\"character\":0}},\"newText\":\"import sample;\\n\"}]}]}";
        }
        if (method.equals("textDocument/definition")) {
            if (mode.equals("features-cross")) {
                uri = log.getParent().resolve("target.java").toUri().toString();
            }
            var location = new JsonObject();
            location.addProperty(mode.equals("features-links") ? "targetUri" : "uri", uri);
            var range = JsonParser.parseString("{\"start\":{\"line\":0,\"character\":0},"
                    + "\"end\":{\"line\":0,\"character\":5}}");
            location.add(mode.equals("features-links") ? "targetRange" : "range", range);
            if (mode.equals("features-links")) {
                location.add("targetSelectionRange", range);
            }
            var second = location.deepCopy();
            if (mode.equals("features-multiple")) {
                second.getAsJsonObject("range").getAsJsonObject("start").addProperty("character", 5);
                second.getAsJsonObject("range").getAsJsonObject("end").addProperty("character", 10);
            }
            return "[" + location + (mode.equals("features-multiple") ? "," + second : "") + "]";
        }
        return "null";
    }

    private static void diagnostics(JsonObject document, int version, String mode) throws Exception {
        var params = new JsonObject();
        params.add("uri", document.get("uri"));
        if (!mode.equals("features-unversioned")) {
            params.addProperty("version", version);
        }
        params.add("diagnostics", JsonParser.parseString("[{\"range\":{\"start\":{\"line\":0,\"character\":0},"
                + "\"end\":{\"line\":0,\"character\":2}},\"severity\":1,\"message\":\"Example problem\"}]"));
        var notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "textDocument/publishDiagnostics");
        notification.add("params", params);
        send(notification.toString());
    }

    private static void record(Path log, JsonObject message) throws Exception {
        Files.writeString(log, message + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void reply(JsonObject request, String result) throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":" + result + "}";
        send(json);
    }

    private static void send(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        System.out.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        System.out.write(bytes);
        System.out.flush();
    }
}
