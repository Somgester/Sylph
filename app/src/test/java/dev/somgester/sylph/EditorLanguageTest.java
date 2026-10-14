package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditorLanguageTest {

    @ParameterizedTest
    @CsvSource({
        "main.c, C",
        "preprocessed.i, C",
        "main.C, CPP",
        "main.cpp, CPP",
        "main.CPP, CPP",
        "main.cc, CPP",
        "main.cxx, CPP",
        "main.c++, CPP",
        "header.h, CPP",
        "header.H, CPP",
        "header.hpp, CPP",
        "header.hh, CPP",
        "header.hxx, CPP",
        "header.h++, CPP",
        "preprocessed.ii, CPP",
        "header.inl, CPP",
        "header.ipp, CPP",
        "template.tpp, CPP",
        "template.txx, CPP",
        "module.cppm, CPP",
        "module.ccm, CPP",
        "module.cxxm, CPP",
        "module.c++m, CPP",
        "module.ixx, CPP",
        "sketch.ino, CPP",
        ".c, PLAIN_TEXT",
        ".C, PLAIN_TEXT",
        "main.c.bak, PLAIN_TEXT",
        "main.cpp.bak, PLAIN_TEXT",
        "folder.cpp/notes.txt, PLAIN_TEXT",
        "Sample.java, JAVA",
        "Sample.JAVA, JAVA",
        "nested/file.JaVa, JAVA",
        "config.json, JSON",
        "config.JSON, JSON",
        ".config.JsOn, JSON",
        "script.py, PYTHON",
        "script.PY, PYTHON",
        "window.pyw, PYTHON",
        "module.pyi, PYTHON",
        "script.js, JAVASCRIPT",
        "script.JS, JAVASCRIPT",
        "module.mjs, JAVASCRIPT",
        "module.cjs, JAVASCRIPT",
        "Component.jsx, JAVASCRIPT_JSX",
        "Component.JSX, JAVASCRIPT_JSX",
        "script.ts, TYPESCRIPT",
        "script.TS, TYPESCRIPT",
        "types.d.ts, TYPESCRIPT",
        "module.mts, TYPESCRIPT",
        "module.cts, TYPESCRIPT",
        "Component.tsx, TYPESCRIPT_TSX",
        "Component.TSX, TYPESCRIPT_TSX",
        "run.sh, SHELL",
        "script.SH, SHELL",
        "script.bash, SHELL",
        "script.zsh, SHELL",
        "script.ksh, SHELL",
        "theme.zsh-theme, SHELL",
        ".config.sh, SHELL",
        ".bashrc, SHELL",
        ".bash_aliases, SHELL",
        ".bash_profile, SHELL",
        ".bash_login, SHELL",
        ".bash_logout, SHELL",
        ".profile, SHELL",
        ".zshrc, SHELL",
        ".zshenv, SHELL",
        ".zprofile, SHELL",
        ".zlogin, SHELL",
        ".zlogout, SHELL",
        ".envrc, SHELL",
        "zshrc, SHELL",
        "script.sh.bak, PLAIN_TEXT",
        ".bashrc.bak, PLAIN_TEXT",
        ".sh, PLAIN_TEXT",
        "folder.sh/notes.txt, PLAIN_TEXT",
        "README.md, MARKDOWN",
        "README.MD, MARKDOWN",
        "notes.markdown, MARKDOWN",
        ".md, PLAIN_TEXT",
        "README.md.bak, PLAIN_TEXT",
        ".py, PLAIN_TEXT",
        ".js, PLAIN_TEXT",
        ".ts, PLAIN_TEXT",
        "script.py.bak, PLAIN_TEXT",
        "script.js.map, PLAIN_TEXT",
        "folder.ts/file.txt, PLAIN_TEXT",
        "notes.txt, PLAIN_TEXT",
        "README, PLAIN_TEXT",
        "java, PLAIN_TEXT",
        ".java, PLAIN_TEXT",
        ".json, PLAIN_TEXT",
        "file., PLAIN_TEXT",
        "Sample.java.bak, PLAIN_TEXT",
        "config.jsonc, PLAIN_TEXT",
        "folder.java/notes.txt, PLAIN_TEXT"
    })
    void detectsSupportedExtensionsAndShellStartupFiles(String name, EditorLanguage expected) {
        assertEquals(expected, EditorLanguage.forPath(Path.of(name)));
    }

    @Test
    void untitledAndPathsWithoutFileNamesArePlainText() {
        assertEquals(EditorLanguage.PLAIN_TEXT, EditorLanguage.forPath(null));
        assertEquals(EditorLanguage.PLAIN_TEXT, EditorLanguage.forPath(Path.of("")));
        assertEquals(EditorLanguage.PLAIN_TEXT, EditorLanguage.forPath(Path.of("/").toAbsolutePath().getRoot()));
    }
}
