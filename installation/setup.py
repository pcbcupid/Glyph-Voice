"""Project-local web installer, invoked by the three OS entry points.

Pinned downloads are checked before extraction/execution. No system package
manager, administrator elevation, firmware tools, credentials or audio uploads.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools import start_web as launcher

NODE_VERSION = '24.21.0'
NODE = {
    ('Linux', 'x86_64'): ('linux-x64.tar.gz', '6e1db87ef58b8819e5d5402eff1536491b18edd8eb7bee5ef7897876e88dc5ff'),
    ('Darwin', 'x86_64'): ('darwin-x64.tar.gz', '1462cb3b3046b815cf8ea436d3da450ec1a9f11dac7e5a46b0ada5305d7e8097'),
    ('Darwin', 'arm64'): ('darwin-arm64.tar.gz', 'bed7eea5325e1108f32ce5228ddd6a5f0f08a499ee42aa7442aea583702f6057'),
    ('Windows', 'amd64'): ('win-x64.zip', '158f7685b44de51f6c0df1d153526cbcd3e1bc739a8dfc607721cef75de9e541'),
}
MODEL = 'sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-1120ms'
MODEL_SHA = '4788229a6dd03be33f8243ccee48e33a8d15df7b448cb99150b0ccddd1b02d74'
MODEL_FILES = {'encoder.int8.onnx', 'decoder.int8.onnx', 'joiner.int8.onnx', 'tokens.txt'}
MODEL_HASHES = {
    'encoder.int8.onnx': '1c03f1192de41771384af22972ca10203613ba56197a024f275b86727cd35911',
    'decoder.int8.onnx': '34fea72425d2506600772ba191a6d3f99c0710abdb68d9a3dc89fa8cb2aa473a',
    'joiner.int8.onnx': '869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a',
    'tokens.txt': 'dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff',
}


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def fetch(url, path, expected):
    if path.is_file() and digest(path) == expected:
        print('Verified cached download:', path.name, flush=True)
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(3):
        try:
            with tempfile.TemporaryDirectory(prefix='download-', dir=path.parent) as temp:
                staging = Path(temp) / path.name
                print(f'Downloading {path.name} (attempt {attempt + 1}/3)…', flush=True)
                request = urllib.request.Request(url, headers={'User-Agent': 'Glyph-Voice-Installer/1'})
                with urllib.request.urlopen(request, timeout=60) as response, staging.open('wb') as target:
                    size = int(response.headers.get('Content-Length', 0))
                    total = 0
                    last_report = time.monotonic()
                    while block := response.read(1024 * 1024):
                        target.write(block)
                        total += len(block)
                        if time.monotonic() - last_report >= 5:
                            print(f'  {total // 1_000_000} MB' + (f' / {size // 1_000_000} MB' if size else ''), flush=True)
                            last_report = time.monotonic()
                if digest(staging) != expected:
                    raise ValueError('Checksum mismatch: ' + path.name + '. Nothing from this download was installed.')
                staging.replace(path)
                return path
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            if attempt == 2:
                raise RuntimeError(f'Download failed: {url}. Check your connection/proxy, then rerun.') from error
            print('Connection interrupted; retrying…', flush=True)
    raise AssertionError('unreachable')


def safe_name(name):
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or '\\' in name or ':' in name:
        raise ValueError('Unsafe archive path: ' + name)
    return path


def extract_node(archive, destination):
    # Node's Unix archive contains npm symlinks; Python's data filter restricts
    # links to the extraction root. Windows ZIP has regular files only.
    if archive.suffix == '.zip':
        with zipfile.ZipFile(archive) as source:
            for member in source.infolist():
                safe_name(member.filename)
                if (member.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError('Unexpected symlink in Windows Node archive')
            source.extractall(destination)
    else:
        with tarfile.open(archive) as source:
            for member in source.getmembers():
                safe_name(member.name)
            source.extractall(destination, filter='data')


def install_node():
    key = (platform.system(), platform.machine().lower())
    if key not in NODE:
        raise ValueError(f'Unsupported computer: {key}. See installation/README.md.')
    artifact, sha = NODE[key]
    name = f'node-v{NODE_VERSION}-{artifact}'
    folder_name = name.removesuffix('.tar.gz').removesuffix('.zip')
    runtime = ROOT / '.tools/runtime'
    folder = runtime / folder_name
    bin_dir = folder if key[0] == 'Windows' else folder / 'bin'
    node = bin_dir / ('node.exe' if key[0] == 'Windows' else 'node')
    if not node.is_file():
        if folder.exists():
            raise ValueError(f'Incomplete Node folder: {folder}. Rename it and rerun; it has not been deleted.')
        archive = fetch(f'https://nodejs.org/dist/v{NODE_VERSION}/{name}', ROOT / '.tools/downloads' / name, sha)
        runtime.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='node-install-', dir=runtime) as temp:
            extract_node(archive, Path(temp))
            (Path(temp) / folder_name).rename(folder)
    result = subprocess.run([str(node), '--version'], check=True, capture_output=True, text=True)
    if result.stdout.strip() != 'v' + NODE_VERSION:
        raise ValueError(f'Unexpected Node version in {folder}; rename that folder and rerun.')
    # Relative paths allow a fresh launcher to find tools without changing PATH globally.
    (ROOT / '.tools/web-runtime.json').write_text(json.dumps({'node_bin': str(bin_dir.relative_to(ROOT))}) + '\n')
    launcher.configure_runtime()
    print(f'Node {NODE_VERSION} ready (project-local).', flush=True)


def model_ready(folder):
    return folder.is_dir() and all((folder / name).is_file() and digest(folder / name) == sha
                                  for name, sha in MODEL_HASHES.items())


def extract_model(archive, destination):
    found = set()
    with tarfile.open(archive, 'r|bz2') as source:
        for member in source:
            name = member.name.removeprefix(MODEL + '/')
            if not member.name.startswith(MODEL + '/') or name not in MODEL_FILES:
                continue
            if name in found or not member.isfile() or not 0 < member.size <= 800_000_000:
                raise ValueError('Unexpected model archive member: ' + name)
            found.add(name)
            with source.extractfile(member) as data, (destination / name).open('wb') as target:
                shutil.copyfileobj(data, target, 1024 * 1024)
    if found != MODEL_FILES:
        raise ValueError('Model archive is missing inference files.')


def install_model():
    collection = ROOT / '.tools/local-models'
    folder = collection / MODEL
    if model_ready(folder):
        print('Existing local model verified and retained:', folder, flush=True)
        copy_model_notices(folder)
        return collection
    if folder.exists():
        raise ValueError(f'Incomplete model folder: {folder}. Rename it and rerun, or use --models-dir for another collection.')
    # Reuse the legacy build-preparation cache, without modifying it.
    old = ROOT / '.tools/parakeet-download/parakeet-unified-streaming-1120ms.tar.bz2'
    archive = old if old.is_file() and digest(old) == MODEL_SHA else fetch(
        f'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{MODEL}.tar.bz2',
        ROOT / '.tools/downloads' / (MODEL + '.tar.bz2'), MODEL_SHA)
    collection.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='model-install-', dir=collection) as temp:
        staging = Path(temp) / MODEL
        staging.mkdir()
        print('Extracting the local English model…', flush=True)
        extract_model(archive, staging)
        copy_model_notices(staging)
        staging.rename(folder)
    return collection


def copy_model_notices(folder):
    # Keep attribution beside the weights if a workshop copies this collection.
    for source, name in [
        (ROOT / 'app/src/main/assets/licenses/NVIDIA-OPEN-MODEL-LICENSE.pdf', 'NVIDIA-OPEN-MODEL-LICENSE.pdf'),
        (ROOT / 'installation/MODEL-NOTICE.txt', 'MODEL-NOTICE.txt'),
    ]:
        if not source.is_file():
            raise ValueError(f'Missing required notice: {source}. Extract the entire project ZIP, not just installation/.')
        target = folder / name
        if not target.exists():
            shutil.copy2(source, target)


def main():
    parser = argparse.ArgumentParser(description='Install the Glyph Voice web app; no Arduino or Android tools')
    parser.add_argument('--models-dir', type=Path, help='Use your existing extracted model collection; do not download a model')
    parser.add_argument('--skip-model', action='store_true', help='Install software only; configure an extracted model collection before launching')
    args = parser.parse_args()
    if args.models_dir and args.skip_model:
        parser.error('Choose either --models-dir or --skip-model.')
    if args.models_dir and not args.models_dir.expanduser().is_dir():
        parser.error('--models-dir must be an existing directory containing extracted model folders.')
    if sys.version_info < (3, 12):
        raise ValueError('Run the OS installer; it supplies Python 3.13 automatically.')
    print('Preparing Glyph Voice. No server starts until you run start-web.', flush=True)
    install_node()
    launcher.python_environment()
    launcher.build_web()
    if args.models_dir:
        launcher.models_directory(str(args.models_dir))
    elif not args.skip_model:
        collection = install_model()
        # Do not replace a user's previous model selection on an update.
        saved = ROOT / '.tools/web-launcher.json'
        if not saved.exists():
            launcher.models_directory(str(collection))
    print('\nInstallation complete. Start with:', flush=True)
    print('  start-web.cmd' if os.name == 'nt' else '  bash start-web.sh')
    print('Keep the launcher window open while using http://localhost:8765.')
    print('Setup: docs/WORKSHOP.md | Licenses: THIRD_PARTY_NOTICES.md')
    if args.skip_model:
        print('Model intentionally skipped: launch with --models-dir /path/to/models.')


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\nCancelled. Rerun the installer to continue.', file=sys.stderr)
        sys.exit(130)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError, tarfile.TarError, zipfile.BadZipFile) as error:
        print('\nInstallation failed: ' + str(error), file=sys.stderr)
        print('Check internet access and free disk space. Rerun after fixing the issue; see installation/README.md.', file=sys.stderr)
        sys.exit(1)
