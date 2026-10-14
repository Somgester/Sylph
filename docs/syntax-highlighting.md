# Syntax highlighting

[Documentation index](README.md) · [Language support](editor-guide.md) · [File reference](file-reference.md)

## Pipeline

1. `EditorLanguage` detects language from the active path after a successful open or Save As.
2. `SyntaxHighlighter` observes plain-text edits, debounces for 120 ms, cancels superseded work, and tags requests with revisions.
3. `SyntaxTokenizer` loads a bundled grammar through its worker-owned `SyntaxGrammars` registry and tokenizes by line. Tokens use UTF-16 offsets to match Java strings and RichTextFX.
4. Unchanged prefixes reuse cached lines. Unchanged suffixes are reusable once the incoming grammar state matches, so multiline strings/comments propagate only as far as needed.
5. `SyntaxStyles` maps TextMate scopes to CSS categories and creates a changed-region style patch. JavaFX applies the patch only if the request revision is still current.

Text, selection, undo history, and dirty state are independent of styling. Plain text and Markdown clear source colors. Theme switching changes CSS colors without reclassifying the language.

## Budgets and failures

Each grammar line receives a 100 ms budget. Early termination leaves the affected remainder unstyled rather than reusing an invalid state. Partial or cancelled caches are not published. The highlighter retries once to accommodate first-use regex compilation; a persistent timeout remains a fallback, not proof of successful highlighting. Runtime failures are logged and clear stale colors; later edits can recover.

A grammar's non-advancing-match guard is different from a timeout. It can stop tokenization even when the returned result looks complete. Regression tests must check actual token categories and loop diagnostics, not merely output length or `complete()`.

## Bundled grammars

Resources live in [syntax/grammars](../app/src/main/resources/syntax/grammars); notices live in [syntax/licenses](../app/src/main/resources/syntax/licenses). C/C++, Java, JSON, Python, JavaScript/JSX, TypeScript/TSX, and shell use these packaged resources without runtime network requests. Preserve the upstream licenses when updating them.

The shell grammar notice records pinned VS Code/upstream revisions, its original checksum, and local TM4E compatibility changes:

- Resolve missing arithmetic-dollar and line-continuation includes.
- Match arithmetic expansion before ordinary command substitution.
- Prevent an empty normal statement from opening and closing without consuming text.
- Escape literal XOR carets so they do not become zero-width beginning-of-line assertions.

Do not overwrite these adaptations with an upstream download unless equivalent fixes are verified. Shell regression coverage includes functions, statements, arithmetic, substitutions, heredocs, multiline quotes, and incomplete edits. Restart a running Sylph instance after changing grammar resources, because grammars are cached.

## C and C++ resources

C uses the pinned VS Code conversion of `better-c-syntax`. C++ and its required `source.cpp.embedded.macro` grammar come from a pinned MIT-licensed `better-cpp-syntax` revision. Separate notices preserve the upstream licenses, revisions, and original checksums. The registry loads the macro grammar and its references back to the main C++ grammar.

Local adaptations remove optional assembly, GLSL, and embedded-regex includes that are not packaged in Sylph. Their containing strings retain lexical coloring. C++ also simplifies equivalent optional noncapturing whitespace and comment-banner repeats for Joni, preventing redundant-repeat warnings without changing captures. JSON is formatted for review. No assembly or shader language support is claimed.

Tests cover extension conventions, representative colors, preprocessor directives, continued macros, templates/lambdas, raw-string custom delimiters, multiline comment edits, Unicode offsets, incremental/full equivalence, incomplete edits, and rendered colors in both themes. A C lexical grammar does not infer arbitrary typedef names as semantic types; that needs richer language tooling.

## Adding a language

1. Add its enum value and explicit filename/extension detection to `EditorLanguage`; keep unknown files as plain text.
2. Vendor a pinned TextMate grammar and any required included grammars. Record source revision, license, and local adaptations with the resources.
3. Register the resource and scope in `SyntaxGrammars`. Check internal/external includes and embedded-language dependencies.
4. Map needed scopes in `SyntaxStyles`, preferring existing semantic categories. Add theme values only if a genuinely new category is needed.
5. Add fixtures and tests for detection, representative colors, multiline state, Unicode offsets, incomplete edits, and incremental-versus-full equivalence. Verify language switching and plain-text fallback in the editor.
6. Run the focused tests, then checks appropriate to the change. Update the language table and file reference.

Do not equate a filename match with complete language tooling: these grammars supply colors, not completion, diagnostics, execution, or a language server.
