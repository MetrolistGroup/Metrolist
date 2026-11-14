const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { mkdtempSync, mkdirSync, writeFileSync, renameSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { test } = require('node:test');
const { needsFlatpak, detectChanges } = require('./flatpak-paths.cjs');

test('translation changes keep Flatpak validation idle', () => {
  assert.equal(needsFlatpak(['shared/src/commonMain/composeResources/values/strings.xml',
    'shared/src/commonMain/composeResources/values-pl/strings.xml']), false);
});

test('Flatpak inputs and workflow changes require validation', () => {
  for (const path of ['flatpak/com.metrolist.desktop.yaml', 'Makefile',
    '.github/workflows/verify-repository-checks.yml', '.github/scripts/flatpak-paths.cjs',
    '.github/scripts/flatpak-paths.test.cjs']) {
    assert.equal(needsFlatpak([path]), true, path);
  }
});

test('changed paths use NUL separators', () => {
  assert.equal(detectChanges('base', 'head', (command, args) => {
    assert.equal(command, 'git');
    assert.deepEqual(args, ['diff', '--no-renames', '--name-only', '-z', 'base', 'head']);
    return 'shared/translation with\nnewline.xml\0Makefile\0';
  }), true);
});

test('moving a manifest outside flatpak still runs validation', t => {
  const root = mkdtempSync(join(tmpdir(), 'metrolist-flatpak-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const git = args => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' }).trim();
  git(['init', '--quiet']);
  mkdirSync(join(root, 'flatpak'));
  writeFileSync(join(root, 'flatpak/manifest.yaml'), 'app-id: com.metrolist.desktop\n');
  git(['add', '.']);
  const base = git(['write-tree']);
  renameSync(join(root, 'flatpak/manifest.yaml'), join(root, 'manifest.yaml'));
  git(['add', '-A']);
  const head = git(['write-tree']);
  assert.equal(detectChanges(base, head, (command, args, options) =>
    execFileSync(command, ['-C', root, ...args], options)), true);
});

test('failed changed-path detection runs validation', () => {
  assert.equal(detectChanges('missing', 'head', () => { throw new Error('Missing revision'); }), true);
});
