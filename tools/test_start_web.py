import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock

from tools import start_web as launcher


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        # macOS /var is a symlink; compare canonical paths like the launcher does.
        self.root = Path(self.temp.name).resolve()
        self.patch = patch.object(launcher, "ROOT", self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def test_existing_model_collection_is_detected_and_remembered(self):
        folder = self.root / ".tools/local-models"
        folder.mkdir(parents=True)
        self.assertEqual(launcher.models_directory(), folder)
        self.assertEqual(json.loads((self.root / ".tools/web-launcher.json").read_text()),
                         {"models_dir": str(folder)})

    def test_explicit_model_path_overrides_saved_path_and_handles_spaces(self):
        folder = self.root / "my models"
        folder.mkdir()
        self.assertEqual(launcher.models_directory(str(folder)), folder)
        self.assertEqual(launcher.models_directory(), folder)
        with self.assertRaises(FileNotFoundError):
            launcher.models_directory(str(folder / "missing"))

    def test_missing_model_in_noninteractive_mode_has_actionable_error(self):
        with patch("sys.stdin.isatty", return_value=False):
            with self.assertRaisesRegex(ValueError, "--models-dir"):
                launcher.models_directory()

    def test_reuses_matching_environment_without_installing(self):
        requirements = self.root / "server/requirements.txt"
        requirements.parent.mkdir()
        requirements.write_text("aiohttp==3.14.3\n")
        python = launcher.environment_python(self.root / ".tools/local-stt-venv")
        python.parent.mkdir(parents=True)
        python.touch()
        with patch.object(launcher, "works", return_value=True), patch.object(launcher, "run") as run:
            self.assertEqual(launcher.python_environment(True), python)
            run.assert_not_called()

    def test_offline_missing_python_dependencies_never_installs(self):
        requirements = self.root / "server/requirements.txt"
        requirements.parent.mkdir()
        requirements.write_text("aiohttp==3.14.3\n")
        with patch.object(launcher, "run") as run:
            with self.assertRaisesRegex(ValueError, "without --no-install"):
                launcher.python_environment(True)
            run.assert_not_called()

    def test_offline_missing_web_dependencies_never_installs(self):
        with patch("shutil.which", side_effect=lambda name: name), \
             patch.object(launcher, "works", return_value=True), \
             patch("subprocess.run", return_value=Mock(returncode=1)), \
             patch.object(launcher, "run") as run:
            with self.assertRaisesRegex(ValueError, "Web dependencies"):
                launcher.build_web(True)
            run.assert_not_called()

    def test_installed_web_dependencies_build_without_npm_ci(self):
        with patch("shutil.which", side_effect=lambda name: name), \
             patch.object(launcher, "works", return_value=True), \
             patch("subprocess.run", return_value=Mock(returncode=0)), \
             patch.object(launcher, "run") as run:
            launcher.build_web(True)
            run.assert_called_once_with(["npm", "run", "build"], cwd=self.root / "web")

    def test_launch_is_loopback_and_forwards_only_explicit_bridge_without_shell(self):
        python = self.root / "space here/python"
        models = self.root / "models"
        with patch("sys.argv", ["launcher", "--no-browser", "--no-install", "--glyph-host", "192.168.4.2"]), \
             patch.object(launcher, "models_directory", return_value=models), \
             patch.object(launcher, "python_environment", return_value=python), \
             patch.object(launcher, "build_web"), patch("os.chdir"), patch("os.execv") as execute:
            launcher.main()
            command = execute.call_args.args[1]
            self.assertNotIn("--host", command)
            self.assertNotIn("--open-browser", command)
            self.assertEqual(command[-2:], ["--glyph-host", "192.168.4.2"])
            self.assertEqual(command[command.index("--models-dir") + 1], str(models))


if __name__ == "__main__":
    unittest.main()
