#!/usr/bin/env python3
"""Check that the reduced-JDK fallback cannot hide an unrelated compiler failure."""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from unittest import mock

import build_apk


class Java8OptionsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.work = Path(self.directory.name)
        self.platform = self.work / "android.jar"
        self.lambda_stubs = self.work / "core-lambda-stubs.jar"
        self.lambda_stubs.touch()

    def probe(self, code=0, stderr=""):
        return subprocess.CompletedProcess(["javac", "probe"], code, "", stderr)

    def test_normal_jdk_keeps_release_8(self):
        with mock.patch.object(build_apk.subprocess, "run", return_value=self.probe()) as run:
            self.assertEqual(["--release", "8"], build_apk.java8_options(["javac"], self.platform, self.lambda_stubs, self.work))
            self.assertEqual(1, run.call_count)
            self.assertEqual(["javac", "--release", "8"], run.call_args.args[0][:3])

    def test_missing_signatures_uses_android_bootclasspath(self):
        result = self.probe(2, "error: release version 8 not supported\nUsage: javac <options> <source files>\n")
        with mock.patch.object(build_apk.subprocess, "run", side_effect=[result, self.probe()]) as run:
            options = build_apk.java8_options(["java", "com.sun.tools.javac.Main"], self.platform, self.lambda_stubs, self.work)
            bootclasspath = str(self.lambda_stubs) + os.pathsep + str(self.platform)
            self.assertEqual(["-source", "8", "-target", "8", "-bootclasspath", bootclasspath], options)
            self.assertEqual(2, run.call_count)
            self.assertIn(bootclasspath, run.call_args.args[0])
            self.assertTrue(run.call_args.kwargs["check"])

    def test_unrelated_errors_do_not_fall_back(self):
        for diagnostic in ["error: cannot find symbol\n", "java.lang.OutOfMemoryError\n", "permission denied\n"]:
            with self.subTest(diagnostic=diagnostic):
                with mock.patch.object(build_apk.subprocess, "run", return_value=self.probe(2, diagnostic)) as run:
                    with self.assertRaises(subprocess.CalledProcessError):
                        build_apk.java8_options(["javac"], self.platform, self.lambda_stubs, self.work)
                    self.assertEqual(1, run.call_count)

    def test_fallback_probe_failure_remains_failure(self):
        result = self.probe(2, "error: release version 8 not supported\n")
        failure = subprocess.CalledProcessError(1, ["javac", "android probe"])
        with mock.patch.object(build_apk.subprocess, "run", side_effect=[result, failure]):
            with self.assertRaises(subprocess.CalledProcessError):
                build_apk.java8_options(["javac"], self.platform, self.lambda_stubs, self.work)

    def test_missing_lambda_companion_does_not_use_host_java_apis(self):
        self.lambda_stubs.unlink()
        result = self.probe(2, "error: release version 8 not supported\n")
        with mock.patch.object(build_apk.subprocess, "run", return_value=result) as run:
            with self.assertRaises(FileNotFoundError):
                build_apk.java8_options(["javac"], self.platform, self.lambda_stubs, self.work)
            self.assertEqual(1, run.call_count)


if __name__ == "__main__":
    unittest.main()
