# Herdr Desktop embedding

The first Herdr milestone is to prove that an interactive Herdr Desktop layout can run inside
Sylph. The terminal chooser, expand and restore controls, and layout importer follow this proof.
This document records the design investigation. An embedded Desktop view is not implemented yet.

## What Sylph needs to host

Sylph's terminal uses a PTY and a JavaFX WebView running xterm.js. Herdr TUI can use that path,
but Traveler and T3 run against the Herdr Desktop layout and mod APIs. Desktop mods describe
native GPUI elements in JavaScript. Herdr Desktop renders those elements and talks to herdr-core,
which owns the terminals and agent sessions.
[Herdr mod API guide](https://github.com/herdrdev/herdr-app/blob/master/defaults/AGENTS.md)

Traveler needs its theme, art and custom blocks. T3 needs its layout, mods and context-specific
keybindings. Both need a real Desktop mod runtime for full compatibility.
[Traveler recipe](https://raw.githubusercontent.com/franky5440-afk/herdr-traveler/main/RECIPE.md) ·
[T3 recipe](https://raw.githubusercontent.com/JinJieBeWater/herdr-t3/main/RECIPE.md)

## First prototype

Start on Linux Wayland with Herdr App 0.1.18 source at
`3e6d56448272064fd06ed95f5b7dc2cf70ea67b4`. Its startup connects to herdr-core, initializes mods
inside the GPUI application loop, and creates the layout in a native `AppWindow`. This gives the
prototype a concrete window creation point to investigate.
[Pinned entry point](https://github.com/herdrdev/herdr-app/blob/3e6d56448272064fd06ed95f5b7dc2cf70ea67b4/src/main.rs#L185-L259)

Use GPUI's existing `render_current_to_image` as the first pixel transfer experiment. Herdr
already uses it for screenshots. Test whether a helper can deliver frames into a JavaFX view
at interactive speed and receive focus, pointer, keyboard and resize events for that view.
The screenshot code establishes that GPUI can produce pixels from an existing window. It
does not establish efficient streaming, windowless rendering, or input forwarding.
[Pinned screenshot implementation](https://github.com/herdrdev/herdr-app/blob/3e6d56448272064fd06ed95f5b7dc2cf70ea67b4/src/control.rs#L148-L165)

Start with a small GPUI view, then a stock Herdr layout and one local shell. Keep terminal
lifecycle with herdr-core. Use a temporary Herdr App profile and an isolated test session.
The investigation machine reports a Wayland session. Rust, Cargo, Zig, Just, Herdr App and
`hc` were unavailable on its PATH, so verification so far is limited to reading upstream source.

The public Herdr App guide documents a standalone native client. A supported JavaFX embedding
API has not been identified. The upstream experimental GPUI embedding design is useful reference
material, but its demonstrated host is GPUI. JavaFX support and Wayland interaction need a
prototype on a real desktop.
[Herdr App source and build requirements](https://github.com/herdrdev/herdr-app) ·
[Experimental GPUI embedding design](https://github.com/zed-industries/embedded_gpui/blob/main/DESIGN.md)

The prototype passes when:

- A stock Herdr Desktop layout is interactive in a Sylph view on Wayland.
- Keyboard, mouse, clipboard, and resize work. Clicking the editor returns focus to the editor.
- Expanding and restoring the embedded surface preserves a running terminal command.
- Closing and reopening the view follows an explicit session policy and cleans up the helper.
- Pinned, reviewed Traveler and T3 bundles load in the isolated profile with their required assets,
  mod UI, and keybindings. Verify their main interactions visually.

Herdr mods can run programs and read files with the user's permissions. Treat the profile as data
isolation and review the exact bundled mods before loading them.
[Herdr Desktop mod security](https://github.com/herdrdev/herdr-app/blob/master/docs/security.md)

If the prototype cannot provide a stable GPUI surface and input bridge, record the missing upstream
capability before choosing a renderer port or reducing the feature scope.
