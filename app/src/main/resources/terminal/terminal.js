'use strict';
(() => {
    let terminal;
    let fit;
    let bridge;
    const darkTheme = {
        background: '#0d0f12', foreground: '#e5e7eb', cursor: '#06b6d4',
        selectionBackground: '#164e63', black: '#151820', red: '#f87171',
        green: '#a8d295', yellow: '#e6bd85', blue: '#90bde8', magenta: '#d0b0ef',
        cyan: '#74c7cb', white: '#e5e7eb', brightBlack: '#7f8a9d', brightRed: '#ff9ba1',
        brightGreen: '#badea8', brightYellow: '#f2d899', brightBlue: '#a9cefa',
        brightMagenta: '#e4c8ff', brightCyan: '#95e2e6', brightWhite: '#ffffff'
    };
    const lightTheme = {
        background: '#faf9f5', foreground: '#141413', cursor: '#a9583e',
        selectionBackground: '#d9d2c5', black: '#141413', red: '#9f2631',
        green: '#386528', yellow: '#794b09', blue: '#24558b', magenta: '#79419d',
        cyan: '#145d62', white: '#75695b', brightBlack: '#6c6a64', brightRed: '#ad2936',
        brightGreen: '#416d2b', brightYellow: '#805309', brightBlue: '#2c5e96',
        brightMagenta: '#864ba6', brightCyan: '#1d6a70', brightWhite: '#413c36'
    };
    function setTheme(dark) {
        const theme = dark ? darkTheme : lightTheme;
        terminal.options.theme = theme;
        document.body.style.background = theme.background;
    }
    function resize() {
        if (terminal && document.body.clientWidth > 30 && document.body.clientHeight > 30) {
            fit.fit();
        }
    }
    window.sylphTerminal = {
        initialize(javaBridge, dark) {
            bridge = javaBridge;
            terminal = new Terminal({
                fontFamily: 'monospace', fontSize: 13, scrollback: 5000, cursorBlink: true,
                // Terminal output cannot launch browser windows or perform window operations.
                linkHandler: { activate() {} }, windowOptions: {}
            });
            fit = new FitAddon.FitAddon();
            terminal.loadAddon(fit);
            terminal.open(document.getElementById('terminal'));
            terminal.onData(data => bridge.input(data));
            terminal.onBinary(data => bridge.binary(data));
            terminal.onResize(size => bridge.resize(size.cols, size.rows));
            setTheme(dark);
            new ResizeObserver(resize).observe(document.body);
            resize();
            terminal.focus();
        },
        write(text) { terminal.write(text, () => bridge.drained()); },
        ended(text) { terminal.write('\r\n[' + text + ']\r\n'); terminal.options.disableStdin = true; },
        setTheme,
        focus() { resize(); terminal.focus(); },
        paste(text) { terminal.paste(text); },
        selection() { return terminal.getSelection(); },
        selectAll() { terminal.selectAll(); },
        // Read-only screen inspection also verifies real ANSI/Unicode rendering in headless tests.
        screen(lastRows) {
            let text = '';
            const buffer = terminal.buffer.active;
            const start = lastRows ? Math.max(0, buffer.length - lastRows) : 0;
            for (let row = start; row < buffer.length; row++) {
                text += buffer.getLine(row).translateToString(true) + '\n';
            }
            return text;
        }
    };
})();
