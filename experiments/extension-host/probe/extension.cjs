const vscode = require('vscode');
const fs = require('node:fs/promises');
const path = require('node:path');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const root = process.env.SYLPH_SPIKE_WORKSPACE;
const output = process.env.SYLPH_SPIKE_RESULT;
const ackFile = path.join(root, 'ack.json');

exports.activate = function () { setTimeout(run, 0); };

async function run() {
  const result = { host: 'Eclipse Theia', extensions: {}, cases: [] };
  const publish = () => fs.writeFile(output, JSON.stringify(result, null, 2));
  const waitFor = async (index, phase) => {
    const deadline = Date.now() + 35000;
    while (Date.now() < deadline) {
      try {
        const ack = JSON.parse(await fs.readFile(ackFile, 'utf8'));
        if (ack.index === index && ack.phase === phase) return;
      } catch (error) { if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) throw error; }
      await delay(50);
    }
    throw new Error(`Browser did not acknowledge ${index}/${phase}`);
  };
  try {
    if (!root || !output) throw new Error('Spike paths not configured');
    for (const id of ['ms-pyright.pyright', 'vscode.typescript-language-features']) {
      const extension = vscode.extensions.getExtension(id);
      if (!extension) throw new Error(`Real extension ${id} was not discovered`);
      result.extensions[id] = extension.packageJSON.version;
    }
    await fs.writeFile(path.join(root, 'helpers.py'),
      'class Rocket:\n    def launch(self) -> str:\n        return "up"\n\ndef greet(name: str) -> str:\n    return name\n');
    const cases = [
      ['builtin', 'pri', 'print'],
      ['variable', 'unique_variable = 42\nunique_va', 'unique_variable'],
      ['function', 'def greet(name: str) -> str:\n    return name\ngr', 'greet'],
      ['module import', 'import collect', 'collections'],
      ['symbol import', 'from helpers import gr', 'greet'],
      ['class member', 'from helpers import Rocket\nship = Rocket()\nship.la', 'launch'],
      ['dot trigger', 'message: str = "hello"\nmessage.', 'capitalize']
    ].map(item => [...item, 'python', 'py', 'ms-pyright.pyright']).concat([
      ['JavaScript member', 'const message = "hi";\nmessage.toU', 'toUpperCase', 'javascript', 'js', 'vscode.typescript-language-features'],
      ['TypeScript member', 'const total: number = 42;\ntotal.toF', 'toFixed', 'typescript', 'ts', 'vscode.typescript-language-features'],
      ['TypeScript class', 'class Rocket { launch() {} }\nconst rocket = new Rocket();\nrocket.la', 'launch', 'typescript', 'ts', 'vscode.typescript-language-features'],
      ['JSX member', 'const message = "hi";\nmessage.toU', 'toUpperCase', 'javascriptreact', 'jsx', 'vscode.typescript-language-features'],
      ['TSX member', 'const message: string = "hi";\nmessage.toU', 'toUpperCase', 'typescriptreact', 'tsx', 'vscode.typescript-language-features']
    ]);
    for (let i = 0; i < cases.length; i++) {
      const [name, text, expected, language, suffix, extensionId] = cases[i];
      const extension = vscode.extensions.getExtension(extensionId);
      const uri = vscode.Uri.file(path.join(root, `case_${i}.${suffix}`));
      const initial = text.slice(0, -1);
      await fs.writeFile(uri.fsPath, initial);
      const document = await vscode.workspace.openTextDocument(uri);
      const end = document.positionAt(initial.length);
      await vscode.window.showTextDocument(document, { selection: new vscode.Range(end, end) });
      if (document.languageId !== language) throw new Error(`Language was ${document.languageId}`);
      await extension.activate();
      result.current = { index: i, name, expected, type: text.slice(-1), phase: 'type' };
      await publish();
      await waitFor(i, 'accepted');
      const accepted = () => {
        const line = document.lineAt(document.lineCount - 1).text;
        return line.endsWith(expected) || line.endsWith(`${expected}()`);
      };
      const editDeadline = Date.now() + 3000;
      while (!accepted() && Date.now() < editDeadline) await delay(30);
      const lastLine = document.lineAt(document.lineCount - 1).text;
      if (!lastLine.endsWith(expected) && !lastLine.endsWith(`${expected}()`)) {
        throw new Error(`${name}: accepted text was ${JSON.stringify(lastLine)}`);
      }
      result.current.phase = 'undo';
      await publish();
      await waitFor(i, 'undone');
      const deadline = Date.now() + 3000;
      while (document.getText() !== text && Date.now() < deadline) await delay(30);
      if (document.getText() !== text) throw new Error(`${name}: Undo did not restore typed text`);
      result.cases.push({ name, language, extension: extensionId, expected, automaticSuggestions: true, accepted: true, undo: true });
      await publish();
    }
    delete result.current;
    result.passed = true;
  } catch (error) {
    result.passed = false;
    result.error = error.stack ?? String(error);
  }
  await publish();
}
