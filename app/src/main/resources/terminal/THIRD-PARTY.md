# Terminal browser assets

Bundled from the upstream npm release archives. SHA-512 integrity was checked against npm release metadata before extraction; these assets are loaded locally and are not fetched at runtime.

| Package | Version | Files | License |
| --- | --- | --- | --- |
| [@xterm/xterm](https://www.npmjs.com/package/@xterm/xterm) | 6.0.0 | `xterm.js`, `xterm.css`, `LICENSE-xterm.txt` | MIT |
| [@xterm/addon-fit](https://www.npmjs.com/package/@xterm/addon-fit) | 0.11.0 | `addon-fit.js`, `LICENSE-fit.txt` | MIT |

Source: [xterm.js](https://github.com/xtermjs/xterm.js). The Java PTY backend, [pty4j 0.14.0](https://github.com/JetBrains/pty4j), is resolved through Gradle and uses Eclipse Public License 1.0.

Release archive integrity:

- `@xterm/xterm@6.0.0`: `sha512-TQwDdQGtwwDt+2cgKDLn0IRaSxYu1tSUjgKarSDkUM0ZNiSRXFpjxEsvc/Zgc5kq5omJ+V0a8/kIM2WD3sMOYg==`
- `@xterm/addon-fit@0.11.0`: `sha512-jYcgT6xtVYhnhgxh3QgYDnnNMYTcf8ElbxxFzX0IZo+vabQqSPAjC3c1wJrKB5E19VwQei89QCiZZP86DCPF7g==`

`index.html`, `terminal.css`, and `terminal.js` belong to Sylph. Avoid modifying vendored upstream files directly; keep integration changes in these application files.
