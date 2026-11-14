#!/usr/bin/env python3
"""Check image publication with fake build tools, without compiling or opening a UI."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import native_image


class BuildTest(unittest.TestCase):
    def exercise(self, fail):
        with tempfile.TemporaryDirectory(prefix="r6 build test ") as directory:
            root = Path(directory)
            work = root / "work"
            (work / "image").mkdir(parents=True)
            (work / "image/metrolist").write_text("working image")
            (work / "image/libjvm.so").write_text("working shim")
            (work / "classpath.txt").write_text(str(root / "input.jar"))
            graal = root / "graal"
            (graal / "bin").mkdir(parents=True)
            (graal / "lib/server").mkdir(parents=True)
            (graal / "lib/libjawt.so").write_text("jawt")
            (graal / "lib/libjava.so").write_text("full JVM library")
            (graal / "lib/server/libjvm.so").write_text("full JVM library")
            tools = root / "tools"
            tools.mkdir()
            programs = {
                graal / "bin/native-image": """#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
arguments = [json.loads(line) for line in Path(sys.argv[1][1:]).read_text().splitlines()]
Path(os.environ['BUILD_ARGUMENTS']).write_text(json.dumps(arguments))
if os.environ['FAIL_BUILD'] == '1':
    sys.exit(9)
target = Path(arguments[arguments.index('-o') + 1])
target.write_text('new image')
for name in ('libjava.so', 'libjvm.so'):
    (target.parent / name).write_text('generated shim')
""",
            }
            for path, content in programs.items():
                path.write_text(content)
                path.chmod(0o755)
            env = dict(os.environ, PATH=f"{tools}:{os.defpath}", NATIVE_EXPERIMENT_DIR=str(work),
                       GRAALVM_HOME=str(graal), NATIVE_IMAGE_GC="G1", NATIVE_IMAGE_OPTIMIZATION="-O2",
                       FAIL_BUILD=str(int(fail)),
                       BUILD_ARGUMENTS=str(root / "build.json"))
            with patch.dict(os.environ, env):
                if fail:
                    with self.assertRaises(subprocess.CalledProcessError) as raised:
                        native_image.build(graal, work, native_image.host_platform(), experiment=True)
                    self.assertEqual(raised.exception.returncode, 9)
                else:
                    native_image.build(graal, work, native_image.host_platform(), experiment=True)
            self.assertEqual((work / "image/metrolist").read_text(), "working image" if fail else "new image")
            self.assertEqual((work / "image/libjvm.so").read_text(), "working shim" if fail else "generated shim")
            self.assertEqual(list(work.glob("image-build.*")), [])
            build = json.loads((root / "build.json").read_text())
            self.assertIn("-J-Xmx12g", build)
            self.assertIn("-O2", build)
            self.assertIn("--gc=G1", build)
            if not fail:
                self.assertEqual((work / "image/libjava.so").read_text(), "generated shim")
                self.assertEqual((work / "image/lib/libjawt.so").read_text(), "jawt")

    def test_failed_build_preserves_working_image(self):
        self.exercise(fail=True)

    def test_success_preserves_generated_shims(self):
        self.exercise(fail=False)


if __name__ == "__main__":
    unittest.main()
