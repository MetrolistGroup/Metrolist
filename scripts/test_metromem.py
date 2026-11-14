"""Exercise launch discovery with stub executables; never start Gradle or the app."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class MetromemLaunchTest(unittest.TestCase):
    def launch(self, mode, initial_status=1, polling_status=0):
        source = Path(__file__).with_name('metromem').read_text()
        functions = source.split('if [[ $# -eq 0 ]]; then usage; fi', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tools = root / 'bin'
            tools.mkdir()
            scripts = {
                tools / 'pgrep': '''#!/bin/bash
if [[ ! -f "$TEST_ROOT/polled" ]]; then
    touch "$TEST_ROOT/polled"
    [[ "$INITIAL_STATUS" == 0 ]] && echo 100
    exit "$INITIAL_STATUS"
fi
[[ "$POLLING_STATUS" == 0 ]] && printf '100\\n200\\n'
exit "$POLLING_STATUS"
''',
                root / 'gradlew': '#!/bin/bash\nprintf "%s\\n" "$@" > "$TEST_ROOT/launched"\n',
                root / 'app.AppImage': '#!/bin/bash\necho appimage > "$TEST_ROOT/launched"\n',
            }
            for path, body in scripts.items():
                path.write_text(body)
                path.chmod(0o755)
            command = 'launch_gradle' if mode == 'gradle' else 'launch_appimage "$TEST_ROOT/app.AppImage"'
            result = subprocess.run(
                ['bash', '-c', functions + '\nPROJECT_ROOT="$TEST_ROOT"\nSESSION_DIR="$TEST_ROOT"\n'
                 + command + '\nwait\nprintf "selected=%s\\n" "$PID"\n'],
                env={**os.environ, 'PATH': f'{tools}:{os.environ["PATH"]}',
                     'TEST_ROOT': directory, 'INITIAL_STATUS': str(initial_status),
                     'POLLING_STATUS': str(polling_status)},
                capture_output=True, text=True, timeout=5,
            )
            launched = root / 'launched'
            return result, launched.read_text() if launched.exists() else None

    def test_empty_initial_process_list_launches_both_modes(self):
        for mode in ('gradle', 'appimage'):
            with self.subTest(mode=mode):
                result, launched = self.launch(mode)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn('selected=100', result.stdout)
                self.assertIsNotNone(launched)
                if mode == 'gradle':
                    for option in ('--no-daemon', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx1536m',
                                   '-Dkotlin.compiler.execution.strategy=in-process'):
                        self.assertIn(option, launched)

    def test_existing_process_is_not_selected(self):
        for mode in ('gradle', 'appimage'):
            result, _ = self.launch(mode, initial_status=0)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('selected=200', result.stdout)

    def test_initial_pgrep_errors_stop_before_launch(self):
        for mode in ('gradle', 'appimage'):
            for status in (2, 3):
                result, launched = self.launch(mode, initial_status=status)
                self.assertEqual(status, result.returncode, result.stderr)
                self.assertIsNone(launched)

    def test_polling_pgrep_errors_are_not_treated_as_no_matches(self):
        for mode in ('gradle', 'appimage'):
            result, _ = self.launch(mode, polling_status=2)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertNotIn('selected=', result.stdout)


if __name__ == '__main__':
    unittest.main()
