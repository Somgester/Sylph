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

    private static void record(Path log, JsonObject message) throws Exception {
        Files.writeString(log, message + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void reply(JsonObject request, String result) throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":" + result + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        System.out.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        System.out.write(bytes);
        System.out.flush();
    }
}
