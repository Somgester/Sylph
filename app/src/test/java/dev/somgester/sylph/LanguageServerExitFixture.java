package dev.somgester.sylph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Exits immediately after requesting cleanup, like closing the application's last window. */
public final class LanguageServerExitFixture {

    private LanguageServerExitFixture() { }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, "hang-shutdown");
        fixture.connection.ready().get(5, TimeUnit.SECONDS);
        Files.writeString(directory.resolve("server.pid"), Long.toString(fixture.pid()));
        fixture.connection.close();
        // Deliberately do not await stopped(): the connection must keep its own cleanup alive.
    }
}
