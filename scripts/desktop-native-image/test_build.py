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

    def test_macos_stubs_and_builder_share_deployment_target_and_preserve_failed_image(self):
        for failure in (None, 'clang', 'native-image'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(prefix='macos build ') as directory:
                work = Path(directory); graal = work/'graal'
                (work/'image').mkdir(); (work/'image/Metrolist').write_text('previous')
                (work/'classpath.txt').write_text('app.jar')
                (graal/'lib').mkdir(parents=True); (graal/'lib/libjawt.dylib').write_text('jawt')
                commands = []
                def run(command, **kwargs):
                    commands.append(command)
                    if command[0] == 'clang':
                        self.assertIn('-mmacosx-version-min=12.3', command)
                        self.assertEqual(kwargs['env']['MACOSX_DEPLOYMENT_TARGET'], '12.3')
                        if failure == 'clang':
                            raise subprocess.CalledProcessError(1, command)
                        Path(command[-1]).write_text('cursor and encoding stubs')
                    elif str(command[0]).endswith('javac'):
                        args = [json.loads(line) for line in Path(command[-1][1:]).read_text().splitlines()]
                        self.assertTrue(any(arg.endswith('/macos/MacosAwtFeature.java') for arg in args))
                        self.assertEqual(args[args.index('--add-exports') + 1], 'org.graalvm.nativeimage/org.graalvm.nativeimage.impl=ALL-UNNAMED')
                    else:
                        self.assertEqual(kwargs['env']['MACOSX_DEPLOYMENT_TARGET'], '12.3')
                        args = [json.loads(line) for line in Path(command[-1][1:]).read_text().splitlines()]
                        self.assertIn('-H:NativeLinkerOption=-mmacosx-version-min=12.3', args)
                        self.assertIn('-H:NativeLinkerOption=' + str(work/'macos-awt.o'), args)
                        self.assertTrue((work/'macos-awt.o').is_file())
                        if failure == 'native-image':
                            raise subprocess.CalledProcessError(2, command)
                        Path(args[args.index('-o') + 1]).write_text('macOS native executable')
                with patch.object(native_image, 'host_platform', return_value='macos-arm64'), patch.dict(os.environ, MACOSX_DEPLOYMENT_TARGET='12.3'), patch('subprocess.run', side_effect=run):
                    if failure:
                        with self.assertRaises(subprocess.CalledProcessError):
                            native_image.build(graal, work, 'macos-arm64')
                    else:
                        native_image.build(graal, work, 'macos-arm64')
                self.assertEqual((work/'image/Metrolist').read_text(), 'previous' if failure else 'macOS native executable')
                self.assertEqual(list(work.glob('image-build.*')), [])
                self.assertEqual(commands[0][0], 'clang')

    def test_other_platforms_do_not_compile_or_register_macos_glue(self):
        for target in ('linux-x64', 'linux-arm64', 'windows-x64'):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                work = Path(directory); (work/'classpath.txt').write_text('app.jar')
                library = 'jawt.dll' if target == 'windows-x64' else 'libjawt.so'
                graal = work/'graal'; source = graal/('bin' if target == 'windows-x64' else 'lib')/library
                source.parent.mkdir(parents=True); source.write_text('jawt')
                def run(command, **kwargs):
                    self.assertNotEqual(command[0], 'clang')
                    self.assertNotIn('env', kwargs)
                    args = [json.loads(line) for line in Path(command[-1][1:]).read_text().splitlines()]
                    self.assertFalse(any('MacosAwt' in arg or 'macos-awt' in arg or '-mmacosx' in arg or 'com.oracle.svm.core.jdk' in arg for arg in args))
                    if '-o' in args:
                        output = Path(args[args.index('-o') + 1])
                        if target == 'windows-x64':
                            output = output.with_suffix('.exe')
                        output.write_text('executable')
                with patch.object(native_image, 'host_platform', return_value=target), patch('subprocess.run', side_effect=run):
                    native_image.build(graal, work, target)
                self.assertFalse((work/'macos-awt.o').exists())


if __name__ == "__main__":
    unittest.main()
