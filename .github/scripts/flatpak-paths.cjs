const { execFileSync } = require('node:child_process');
const { appendFileSync } = require('node:fs');

function needsFlatpak(paths) {
  return paths.some(path => path.startsWith('flatpak/') || path === 'Makefile' ||
    path.startsWith('.github/workflows/') || path.startsWith('.github/scripts/flatpak-paths.'));
}

function detectChanges(base, head, diff = execFileSync) {
  try {
    const paths = diff('git', ['diff', '--no-renames', '--name-only', '-z', base, head], { encoding: 'utf8' });
    return needsFlatpak(paths.split('\0').filter(Boolean));
  } catch {
    return true;
  }
}

if (require.main === module) {
  appendFileSync(process.env.GITHUB_OUTPUT,
    `flatpak=${detectChanges(process.env.DIFF_BASE, process.env.DIFF_HEAD)}\n`);
}

module.exports = { needsFlatpak, detectChanges };
