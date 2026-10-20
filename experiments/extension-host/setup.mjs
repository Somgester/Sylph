import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cp, mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('.', import.meta.url));
process.chdir(root);
if (process.platform !== 'linux' || process.arch !== 'x64') {
  throw new Error('This isolated experiment currently supports Linux x64 only.');
}
if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('Node.js 22 or newer is required.');
for (const command of ['npm', 'make', 'g++', 'curl', 'tar', 'python3']) {
  try { execFileSync(command, ['--version'], { stdio: 'ignore' }); }
  catch { throw new Error(`Missing prerequisite: ${command}. See README.md; setup does not install system packages.`); }
}

const generated = path.join(root, 'generated');
const downloads = path.join(generated, 'downloads');
const plugins = path.join(generated, 'plugins');
await mkdir(downloads, { recursive: true });
await mkdir(plugins, { recursive: true });
const run = (command, args, options = {}) => execFileSync(command, args, { stdio: 'inherit', ...options });
const sha256 = data => createHash('sha256').update(data).digest('hex');

async function download(name, url, hash) {
  const target = path.join(downloads, name);
  try { if (sha256(await readFile(target)) === hash) return target; }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const temporary = `${target}.part`;
  run('curl', ['--location', '--fail', '--silent', '--show-error', '--retry', '2',
    '--connect-timeout', '15', '--max-time', '120', url, '--output', temporary]);
  if (sha256(await readFile(temporary)) !== hash) {
    await rm(temporary, { force: true });
    throw new Error(`SHA-256 mismatch for ${name}; nothing from that archive was executed.`);
  }
  await rename(temporary, target);
  return target;
}

console.log('Installing locked experiment dependencies...');
// Explicitly build only the native modules exercised here; skip Puppeteer's redundant browser download.
run('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund']);
console.log('Building the drive-list dependency...');
run(process.execPath, [path.join(root, 'node_modules/node-gyp/bin/node-gyp.js'), 'rebuild'],
  { cwd: path.join(root, 'node_modules/drivelist') });
const keytar = await download('keytar-prebuild.tgz',
  'https://github.com/atom/node-keytar/releases/download/v7.9.0/keytar-v7.9.0-napi-v3-linux-x64.tar.gz',
  '2d75753fabf9dceb49537ae231e1897de53727bc0fadbc2a01e6374a1443c67c');
run('tar', ['-xzf', keytar, '-C', path.join(root, 'node_modules/keytar')]);

const extensions = [
  ['ms-pyright', 'pyright', '1.1.402', '7ffa5a8649fa800b282c25ad70ce290367e036238d52727da7cdcac3d57439e9'],
  ['vscode', 'typescript-language-features', '1.95.3', 'b1494642a46d1b139ab799e2ee0ae2453bdcef6ed1a718acca034445cfd9d87f'],
  ['vscode', 'typescript', '1.95.3', '493019c0375ad6d46a8811e041d70f68eb57ed5e5a9698125e4691eaf6f2d8fc'],
  ['vscode', 'javascript', '1.95.3', 'df55b5174c57ec057d78d4b9ddd225cc36095b6fc845f1a0df87f1e13dfb64b2']
];
for (const [publisher, name, version, hash] of extensions) {
  const archive = `${publisher}.${name}-${version}.vsix`;
  console.log(`Preparing ${publisher}.${name}@${version}...`);
  const file = await download(archive, `https://open-vsx.org/api/${publisher}/${name}/${version}/file/${archive}`, hash);
  const destination = path.join(plugins, `${publisher}.${name}`);
  await rm(destination, { recursive: true, force: true });
  run('python3', [path.join(root, 'unpack-extension.py'), file, destination]);
  const manifest = JSON.parse(await readFile(path.join(destination, 'package.json'), 'utf8'));
  if (manifest.publisher !== publisher || manifest.name !== name || manifest.version !== version) {
    throw new Error(`Unexpected manifest in ${archive}`);
  }
}
await cp(path.join(root, 'probe'), path.join(plugins, 'sylph.host-probe'), { recursive: true });
const workspaceSettings = path.join(generated, 'workspace/.theia');
await mkdir(workspaceSettings, { recursive: true });
// Only this generated test workspace bypasses trust prompts. No user project is opened.
await writeFile(path.join(workspaceSettings, 'settings.json'), JSON.stringify({
  'security.workspace.trust.enabled': false,
  'telemetry.telemetryLevel': 'off'
}));
run(process.execPath, [path.join(root, 'node_modules/@theia/cli/bin/theia.js'), 'build', '--mode', 'development']);
run(process.execPath, [path.join(root, 'node_modules/playwright-core/cli.js'), 'install', 'chromium', '--only-shell'], {
  env: { ...process.env, PLAYWRIGHT_BROWSERS_PATH: path.join(generated, 'browsers') }
});
console.log('Experiment ready. Run npm test here. No Sylph production settings were changed.');
