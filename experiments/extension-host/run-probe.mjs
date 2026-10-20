import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { readFile, writeFile, rm, mkdir } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('.', import.meta.url));
const generated = path.join(root, 'generated');
const workspace = path.join(generated, 'workspace');
const reports = path.join(generated, 'reports');
process.env.PLAYWRIGHT_BROWSERS_PATH ??= path.join(generated, 'browsers');
const { chromium } = await import('playwright-core');
const resultFile = path.join(reports, 'result.json');
await mkdir(reports, { recursive: true });
await rm(resultFile, { force: true });
await rm(path.join(workspace, 'ack.json'), { force: true });
await mkdir(path.join(generated, 'config'), { recursive: true });
const server = net.createServer();
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;
await new Promise(resolve => server.close(resolve));
const log = createWriteStream(path.join(reports, 'host.log'));
const host = spawn(process.execPath, ['src-gen/backend/main.js', '--hostname=127.0.0.1', `--port=${port}`,
  `--plugins=local-dir:${generated}/plugins`, workspace], {
  cwd: root,
  detached: true,
  env: { ...process.env, THEIA_CONFIG_DIR: path.join(generated, 'config'),
    SYLPH_SPIKE_WORKSPACE: workspace, SYLPH_SPIKE_RESULT: resultFile },
  stdio: ['ignore', 'pipe', 'pipe']
});
host.stdout.pipe(log);
host.stderr.pipe(log);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let browser;
let stop;
const done = new Promise(resolve => { stop = resolve; });
host.once('exit', stop);
let cleanup;
function close() {
  return cleanup ??= (async () => {
    const kill = signal => {
      try { process.kill(-host.pid, signal); }
      catch (error) { if (error.code !== 'ESRCH') throw error; }
    };
    try {
      kill('SIGTERM');
      await Promise.race([Promise.all([done, browser?.close().catch(() => {})]), delay(3000)]);
    } finally {
      kill('SIGKILL');
      log.end();
    }
  })();
}
for (const [signal, code] of [['SIGINT', 130], ['SIGTERM', 143]]) {
  process.once(signal, () => { void close().finally(() => process.exit(code)); });
}
try {
  let ready = false;
  for (let i = 0; i < 120; i++) {
    if (host.exitCode !== null) throw new Error(`Host exited with ${host.exitCode}; inspect host.log`);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/`, { signal: AbortSignal.timeout(1000) });
      if (response.ok) { ready = true; break; }
    } catch { /* Wait for backend startup. */ }
    await delay(500);
  }
  if (!ready) throw new Error('Host did not become ready');
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.on('pageerror', error => console.error('Browser:', error.message));
  await page.goto(`http://127.0.0.1:${port}/#${workspace}`);
  const handled = new Set();
  for (let i = 0; i < 360; i++) {
    try {
      const result = JSON.parse(await readFile(resultFile, 'utf8'));
      const current = result.current;
      const key = current && `${current.index}/${current.phase}`;
      if (current && !handled.has(key)) {
        handled.add(key);
        if (current.phase === 'type') {
          await page.keyboard.type(current.type);
          const item = page.locator('.suggest-widget.visible').getByText(current.expected, { exact: true }).first();
          await item.waitFor({ state: 'visible', timeout: 30000 });
          await page.screenshot({ path: path.join(reports, `case-${current.index}.png`) });
          await item.dblclick();
          await writeFile(path.join(workspace, 'ack.json'), JSON.stringify({ index: current.index, phase: 'accepted' }));
        } else if (current.phase === 'undo') {
          await page.keyboard.press('Control+z');
          await writeFile(path.join(workspace, 'ack.json'), JSON.stringify({ index: current.index, phase: 'undone' }));
        }
      }
      if (typeof result.passed === 'boolean') {
        console.log(JSON.stringify(result, null, 2));
        await page.screenshot({ path: path.join(reports, 'host.png') });
        if (!result.passed) process.exitCode = 1;
        break;
      }
    } catch (error) {
      if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) throw error;
    }
    if (i === 359) throw new Error('Extension probe timed out; inspect host.log');
    await delay(500);
  }
} catch (error) {
  if (browser) {
    await browser.contexts()[0]?.pages()[0]?.screenshot({ path: path.join(reports, 'failure.png') }).catch(() => {});
  }
  throw error;
} finally {
  await close();
}
