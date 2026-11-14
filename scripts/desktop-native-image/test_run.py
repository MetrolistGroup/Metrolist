#!/usr/bin/env python3
"""Backend checks for the experiment launcher. These do not open a window."""

import argparse
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run


class ExperimentTest(unittest.TestCase):
    def test_profile_isolation_preserves_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory)
            with patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "inherited", "DISPLAY": ":0"}):
                env = run.environment(profile)
            self.assertNotIn("JAVA_TOOL_OPTIONS", env)
            self.assertNotIn("DISPLAY", env)
            self.assertEqual(env["HOME"], directory)
            self.assertTrue(Path(env["TMPDIR"]).is_relative_to(profile))
            for kind in ("CONFIG", "DATA", "CACHE", "STATE"):
                self.assertTrue(Path(env[f"XDG_{kind}_HOME"]).is_relative_to(profile))
            config = profile / "config/metrolist/settings.properties"
            self.assertIn("sentryEnabled=false", config.read_text())
            config.write_text("saved=true\n")
            run.environment(profile)
            self.assertEqual(config.read_text(), "saved=true\n")

    def test_jvm_command_keeps_shipped_flags_and_path_arguments(self):
        with tempfile.TemporaryDirectory(prefix="r6 test ") as directory:
            work = Path(directory)
            classpath = "/path with spaces/a.jar:/b.jar"
            (work / "classpath.txt").write_text(classpath)
            args = argparse.Namespace(work=work, java=Path("/jdk/bin/java"), mode="trace", media=None, jvm_config=None)
            command = run.command(args, work / "profile")
            self.assertIn("-Xmx384m", command)
            self.assertIn("-XX:TieredStopAtLevel=1", command)
            self.assertEqual(command[-2:], [classpath, run.MAIN])
            self.assertIn(f"-agentlib:native-image-agent=config-merge-dir={work / 'agent'}", command)

    def test_process_sample_units(self):
        sample = run.sample(os.getpid())
        self.assertGreaterEqual(sample["rss_kib"], sample["pss_kib"])
        self.assertGreater(sample["pss_kib"], 0)
        self.assertGreaterEqual(sample["cpu_seconds"], 0)

    def test_native_command_uses_package_home_and_same_heap(self):
        with tempfile.TemporaryDirectory(prefix="r6 native ") as directory:
            work = Path(directory)
            args = argparse.Namespace(work=work, mode="native", registration_mode="Exit", media=None)
            command = run.command(args, work / "profile")
            self.assertEqual(command[0], str(work / "image/metrolist"))
            self.assertIn(f"-Djava.home={work / 'image'}", command)
            self.assertIn("-XX:MissingRegistrationReportingMode=Exit", command)
            self.assertIn("-Xms32m", command)
            self.assertIn("-Xmx384m", command)

    def test_release_options_use_private_cache_and_packaged_jars(self):
        with tempfile.TemporaryDirectory(prefix="r6 package ") as directory:
            work = Path(directory)
            jar = work / "app.jar"
            jar.touch()
            config = work / "Metrolist.cfg"
            config.write_text("app.classpath=$APPDIR/app.jar\njava-options=-Xmx384m\n"
                              "java-options=-XX:SharedArchiveFile=$METROLIST_CDS_CACHE/app.jsa\n"
                              "java-options=-XX:ErrorFile=$METROLIST_JVM_ERROR_FILE\n")
            options, classpath = run.release_options(config, work / "profile")
            self.assertEqual(classpath, str(jar))
            self.assertIn(f"-XX:SharedArchiveFile={work / 'profile/cache/metrolist/app.jsa'}", options)
            self.assertTrue(all("$" not in option for option in options))
            config.write_text("app.classpath=$APPDIR/*\n")
            self.assertEqual(run.release_options(config, work / "profile")[1], str(work / "*"))

    def test_release_options_reject_unknown_environment_variables(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            config = work / "Metrolist.cfg"
            config.write_text("app.classpath=$HOME/private.jar\n")
            with self.assertRaisesRegex(ValueError, "Unrecognized package variable"):
                run.release_options(config, work / "profile")

    def test_playback_requires_real_time_progress(self):
        def output(advance):
            return "\n".join(
                f"[NativeImagePlayback] positionMs={index * 1000 if advance else 0} "
                "durationMs=180000 playing=true ready=true error=null" for index in range(30)
            )
        self.assertTrue(run.playback_summary(output(True))["valid"])
        self.assertFalse(run.playback_summary(output(False))["valid"])


if __name__ == "__main__":
    unittest.main()
