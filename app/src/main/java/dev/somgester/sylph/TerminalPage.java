package dev.somgester.sylph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

final class TerminalPage {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String TEMPLATE = template();

    private TerminalPage() { }

    static String html() {
        byte[] nonce = new byte[18];
        RANDOM.nextBytes(nonce);
        return TEMPLATE.replace("@@NONCE@@", Base64.getEncoder().encodeToString(nonce));
    }

    private static String template() {
        String page = resource("index.html");
        for (String file : new String[] {"xterm.css", "terminal.css", "xterm.js", "addon-fit.js", "terminal.js"}) {
            String content = resource(file);
            if (file.endsWith(".js")) {
                // Keep upstream string literals from accidentally terminating an inline script.
                content = content.replace("</script", "<\\/script");
            }
            page = page.replace("@@" + file + "@@", content);
        }
        return page;
    }

    private static String resource(String name) {
        try (var stream = TerminalPage.class.getResourceAsStream("/terminal/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing terminal resource: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load terminal resource: " + name, failure);
        }
    }
}
