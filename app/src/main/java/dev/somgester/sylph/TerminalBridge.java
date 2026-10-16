package dev.somgester.sylph;

import java.nio.charset.StandardCharsets;

// WebKit invokes these public methods reflectively; retain the bridge in TerminalView.
public final class TerminalBridge {

    private final TerminalSession session;

    private final Runnable drained;

    TerminalBridge(TerminalSession session, Runnable drained) {
        this.session = session;
        this.drained = drained;
    }

    public void input(String text) {
        session.send(text);
    }

    public void binary(String text) {
        session.send(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    public void resize(int columns, int rows) {
        session.resize(columns, rows);
    }

    public void drained() {
        drained.run();
    }
}
