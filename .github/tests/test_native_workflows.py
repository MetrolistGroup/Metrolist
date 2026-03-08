"""Exercise native dispatch and tester publication guards without spending runner time."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def guard(text, job):
    body = text.split(f'  {job}:\n',1)[1]
    rows = body.split('    if: ',1)[1].splitlines()
    value = rows[0]
    if value == '>-':
        continuation = []
        for row in rows[1:]:
            if not row.startswith('      '):
                break
            continuation.append(row.strip())
        value = ' '.join(continuation)

    return value


def evaluate(expression, bindings):
    for key,value in sorted(bindings.items(),key=lambda p:-len(p[0])):
        expression=expression.replace(key,repr(value))
    expression=expression.replace('always()','True').replace('&&',' and ').replace('||',' or ')
    return eval(expression,{'__builtins__':{}})


class NativeWorkflowTest(unittest.TestCase):
    def test_platforms_only_run_for_dispatch_or_tester_push_and_arm_enable(self):
        text=(ROOT/'.github/workflows/reusable-desktop-native.yml').read_text()
        condition=guard(text,'package')
        for event in ['push','pull_request','schedule','workflow_dispatch']:
            for target in ['linux-x64','windows-x64','linux-arm64','macos-arm64','windows-arm64','macos-x64']:
                for enabled in ['true','false']:
                    bindings={'github.event_name':event,'inputs.platform':target,'vars.ENABLE_NATIVE_MACOS_ARM64':enabled,'vars.ENABLE_NATIVE_LINUX_ARM64':enabled}
                    expected=event in ['workflow_dispatch','push'] and (target in ['linux-x64','windows-x64'] or enabled=='true' and target in ['linux-arm64','macos-arm64'])
                    self.assertEqual(evaluate(condition,bindings),expected,(event,target,enabled))

    def test_native_caches_exclude_application_outputs_and_credentials(self):
        text=(ROOT/'.github/workflows/reusable-desktop-native.yml').read_text()
        cache=text.split('      - name: Cache verified GraalVM archives\n',1)[1].split('      - name:',1)[0]
        self.assertIn('metrolist-graalvm/archives',cache)
        self.assertIn('scripts/graalvm-pins.json',cache)
        self.assertIn('toolchain.py',cache)
        self.assertNotIn('restore-keys:',cache)
        self.assertIn("cache-read-only: 'true'",text)
        self.assertIn("persist-configuration-cache: 'false'",text)
        self.assertIn('--no-build-cache --no-configuration-cache',text)
        self.assertNotIn('contents: write',text)


if __name__=='__main__':
    unittest.main()
