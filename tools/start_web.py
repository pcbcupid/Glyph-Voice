"""One-command local web launcher. No global package installs or model downloads."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def configure_runtime():
    """Prefer installer tools without a permanent system PATH change."""
    os.environ.setdefault("PIP_CACHE_DIR", str(ROOT / ".tools/pip-cache"))
    os.environ.setdefault("npm_config_cache", str(ROOT / ".tools/npm-cache"))
    os.environ.setdefault("PIP_DISABLE_PIP_VERSION_CHECK", "1")
    os.environ.setdefault("npm_config_update_notifier", "false")
    config = ROOT / ".tools/web-runtime.json"
    if config.is_file():
        try:
            folder = (ROOT / json.loads(config.read_text())["node_bin"]).resolve()
            if folder.is_dir() and folder.is_relative_to((ROOT / ".tools/runtime").resolve()):
                os.environ["PATH"] = str(folder) + os.pathsep + os.environ.get("PATH", "")
        except (OSError, ValueError, KeyError, TypeError):
            pass


def run(command, cwd=ROOT):
    subprocess.run([str(value) for value in command], cwd=cwd, check=True)


def works(command):
    try:
        return subprocess.run(command, cwd=ROOT, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL).returncode == 0
    except OSError:
        return False


def environment_python(folder):
    return folder / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def python_environment(no_install=False):
    requirements = []
    for line in (ROOT / "server/requirements.txt").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            requirements.append(line.strip().split("=="))
    check = ("import sys; assert sys.version_info >= (3,12); "
             "from importlib.metadata import version; "
             "assert all(version(n)==v for n,v in " + repr(requirements) + ")")
    # Reuse a matching repo environment without touching unrelated/global packages.
    for folder in (".tools/local-stt-venv", ".tools/web-venv", ".venv"):
        python = environment_python(ROOT / folder)
        if python.is_file() and works([str(python), "-c", check]):
            return python
    if no_install:
        raise ValueError("Local server dependencies are missing. Run once without --no-install while online.")
    candidates = [[sys.executable]]
    for name in ("python3.14", "python3.13", "python3.12", "python3", "python"):
        if shutil.which(name):
            candidates.append([shutil.which(name)])
    if os.name == "nt" and shutil.which("py"):
        candidates.extend([["py", "-3.14"], ["py", "-3.13"], ["py", "-3.12"]])
    base = next((cmd for cmd in candidates if works(
        cmd + ["-c", "import sys; assert sys.version_info >= (3,12)"])), None)
    if not base:
        raise ValueError("Install Python 3.12 or newer, then run this launcher again.")
    folder = ROOT / ".tools/web-venv"
    print("First-time setup: installing the local server in .tools/web-venv (internet required).", flush=True)
    run(base + ["-m", "venv", str(folder)])
    python = environment_python(folder)
    # Supported workshop platforms have wheels. Don't unexpectedly ask beginners
    # to install native compilers when a platform has no compatible package.
    run([python, "-m", "pip", "install", "--only-binary=:all:", "-r", ROOT / "server/requirements.txt"])
    return python


def build_web(no_install=False):
    npm, node = shutil.which("npm"), shutil.which("node")
    if not npm or not node:
        raise ValueError("Install Node.js 22.12+ (including npm), then run this launcher again.")
    check = "const [a,b]=process.versions.node.split('.').map(Number); process.exit(a>22 || a===22&&b>=12 ? 0 : 1)"
    if not works([node, "-e", check]):
        raise ValueError("Node.js 22.12 or newer is required.")
    web = ROOT / "web"
    # Verify installed direct dependencies; no registry request on normal launches.
    ready = subprocess.run([npm, "ls", "--depth=0", "--offline"], cwd=web,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not ready:
        if no_install:
            raise ValueError("Web dependencies are missing/outdated. Run once without --no-install while online.")
        print("First-time/update setup: installing web dependencies (internet required).", flush=True)
        run([npm, "ci"], cwd=web)
    print("Building the current web app…", flush=True)
    run([npm, "run", "build"], cwd=web)


def models_directory(explicit=None):
    saved = ROOT / ".tools/web-launcher.json"
    path = explicit
    if not path and saved.is_file():
        try:
            path = json.loads(saved.read_text()).get("models_dir")
        except (OSError, ValueError, AttributeError):
            pass
    if not path:
        path = next((str(ROOT / p) for p in (".tools/local-models", "models")
                     if (ROOT / p).is_dir()), None)
    if not path:
        print("Choose the folder containing your extracted streaming Parakeet model(s).")
        print("No model is downloaded automatically. See server/README.md for the model link.")
        if not sys.stdin.isatty():
            raise ValueError("No models folder found. Pass --models-dir /path/to/models.")
        path = input("Models folder: ").strip().strip('"')
        if not path:
            raise ValueError("A models folder is required.")
    folder = Path(path).expanduser().resolve(strict=True)
    if not folder.is_dir():
        raise ValueError("Models path must be a directory.")
    # Only the operator-chosen path is saved. Never save tokens or speech here.
    saved.parent.mkdir(parents=True, exist_ok=True)
    saved.write_text(json.dumps({"models_dir": str(folder)}, indent=2) + "\n")
    return folder


def main():
    parser = argparse.ArgumentParser(description="Build and start Glyph Voice locally in one step")
    parser.add_argument("--models-dir", help="Extracted model collection; remembered for next launch")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--glyph-host", action="append", default=[], help="Optional allowlisted board IP for the server bridge")
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--no-install", action="store_true", help="Never install missing dependencies (offline operation)")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("Port must be between 1 and 65535")
    configure_runtime()
    models = models_directory(args.models_dir)
    python = python_environment(args.no_install)
    build_web(args.no_install)
    command = [str(python), "-m", "server.app", "--models-dir", str(models), "--port", str(args.port)]
    for address in args.glyph_host:
        command += ["--glyph-host", address]
    if not args.no_browser:
        command.append("--open-browser")
    print("\nStarting Glyph Voice. Keep this window open; Ctrl+C stops the server.", flush=True)
    print("Copy the private token below into Local model settings. It is not a cloud API key.", flush=True)
    os.chdir(ROOT)
    os.execv(str(python), command)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped.")
        sys.exit(130)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("\nCould not start Glyph Voice: " + str(error), file=sys.stderr)
        print("Fix the issue and rerun the same launcher. See server/README.md.", file=sys.stderr)
        sys.exit(1)
