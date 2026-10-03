import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from installation import setup
from tools import start_web


class InstallerTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='glyph installer ')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def tar(self, files):
        archive = self.root / 'model.tar.bz2'
        with tarfile.open(archive, 'w:bz2') as dest:
            for name, body in files:
                info = tarfile.TarInfo(name)
                info.size = len(body)
                dest.addfile(info, io.BytesIO(body))
        return archive

    def test_model_extracts_only_expected_files(self):
        archive = self.tar([(setup.MODEL + '/' + name, b'model') for name in setup.MODEL_FILES] +
                           [(setup.MODEL + '/../../escape', b'bad'), ('test.wav', b'no recording')])
        dest = self.root / 'models'; dest.mkdir()
        setup.extract_model(archive, dest)
        self.assertEqual({p.name for p in dest.iterdir()}, setup.MODEL_FILES)
        self.assertFalse((self.root / 'escape').exists())

    def test_rejects_duplicate_missing_or_empty_model_members(self):
        for files in [[], [(setup.MODEL + '/tokens.txt', b'')],
                      [(setup.MODEL + '/tokens.txt', b'ok')] * 2]:
            archive = self.tar(files)
            with self.assertRaises(ValueError):
                setup.extract_model(archive, self.root)

    def test_node_archive_cannot_escape_destination(self):
        for name in ['../escape', '/absolute', 'C:/file', '..\\escape']:
            with self.assertRaises(ValueError): setup.safe_name(name)
        archive = self.root / 'node.zip'
        with zipfile.ZipFile(archive, 'w') as dest: dest.writestr('../escape', 'bad')
        with self.assertRaises(ValueError): setup.extract_node(archive, self.root / 'out')
        self.assertFalse((self.root / 'escape').exists())

    def test_node_tar_link_cannot_escape_destination(self):
        archive = self.root / 'node.tar.gz'
        with tarfile.open(archive, 'w:gz') as dest:
            info = tarfile.TarInfo('link'); info.type = tarfile.SYMTYPE; info.linkname = '../escape'
            dest.addfile(info)
        with self.assertRaises(tarfile.FilterError): setup.extract_node(archive, self.root / 'out')

    def test_verified_download_cache_needs_no_network(self):
        file = self.root / 'asset'; file.write_bytes(b'valid')
        with patch('urllib.request.urlopen') as request:
            self.assertEqual(setup.fetch('https://example.invalid', file, hashlib.sha256(b'valid').hexdigest()), file)
            request.assert_not_called()

    def test_bad_download_never_replaces_an_existing_file(self):
        file = self.root / 'asset'; file.write_bytes(b'previous')
        response = io.BytesIO(b'wrong download')
        response.headers = {'Content-Length': '14'}
        with patch('urllib.request.urlopen', return_value=response):
            with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
                setup.fetch('https://example.invalid', file, hashlib.sha256(b'expected').hexdigest())
        self.assertEqual(file.read_bytes(), b'previous')
        self.assertEqual(list(self.root.iterdir()), [file])

    def test_model_notices_are_copied_without_overwriting_custom_notices(self):
        source = self.root / 'installation/MODEL-NOTICE.txt'
        license = self.root / 'app/src/main/assets/licenses/NVIDIA-OPEN-MODEL-LICENSE.pdf'
        source.parent.mkdir(parents=True); source.write_text('attribution')
        license.parent.mkdir(parents=True); license.write_bytes(b'license')
        dest = self.root / 'weights'; dest.mkdir()
        with patch.object(setup, 'ROOT', self.root):
            setup.copy_model_notices(dest)
            self.assertEqual((dest / source.name).read_text(), 'attribution')
            (dest / source.name).write_text('preserve')
            setup.copy_model_notices(dest)
            self.assertEqual((dest / source.name).read_text(), 'preserve')

    def test_model_reuse_checks_content_not_just_names(self):
        for name in setup.MODEL_FILES: (self.root / name).write_bytes(b'corrupt')
        self.assertFalse(setup.model_ready(self.root))
        hashes = {name: hashlib.sha256(b'corrupt').hexdigest() for name in setup.MODEL_FILES}
        with patch.object(setup, 'MODEL_HASHES', hashes): self.assertTrue(setup.model_ready(self.root))

    def test_missing_or_partial_model_is_not_overwritten(self):
        with patch.object(setup, 'ROOT', self.root), patch.object(setup, 'fetch') as fetch:
            target = self.root / '.tools/local-models' / setup.MODEL
            target.mkdir(parents=True); (target / 'tokens.txt').write_text('custom')
            with self.assertRaisesRegex(ValueError, 'Rename it'):
                setup.install_model()
            self.assertEqual((target / 'tokens.txt').read_text(), 'custom')
            fetch.assert_not_called()

    def test_runtime_path_is_project_scoped_and_handles_spaces(self):
        node = self.root / '.tools/runtime/node space/bin'; node.mkdir(parents=True)
        config = self.root / '.tools/web-runtime.json'
        with patch.object(start_web, 'ROOT', self.root), patch.dict(os.environ, {'PATH': 'existing'}):
            config.write_text(json.dumps({'node_bin': str(node.relative_to(self.root))}))
            start_web.configure_runtime()
            self.assertEqual(os.environ['PATH'], str(node) + os.pathsep + 'existing')
            config.write_text(json.dumps({'node_bin': '/tmp'}))
            os.environ['PATH'] = 'existing'
            start_web.configure_runtime(); self.assertEqual(os.environ['PATH'], 'existing')

    def test_unsupported_platform_is_actionable(self):
        with patch('platform.system', return_value='Windows'), patch('platform.machine', return_value='ARM64'):
            with self.assertRaisesRegex(ValueError, 'Unsupported'): setup.install_node()

    def test_model_skip_never_downloads(self):
        with patch('sys.argv', ['setup', '--skip-model']), patch.object(setup, 'install_node'), \
             patch.object(setup.launcher, 'python_environment'), patch.object(setup.launcher, 'build_web'), \
             patch.object(setup, 'install_model') as model:
            setup.main(); model.assert_not_called()

    def test_explicit_model_choice_skips_download(self):
        with patch('sys.argv', ['setup', '--models-dir', str(self.root)]), patch.object(setup, 'install_node'), \
             patch.object(setup.launcher, 'python_environment'), patch.object(setup.launcher, 'build_web'), \
             patch.object(setup.launcher, 'models_directory') as save, patch.object(setup, 'install_model') as model:
            setup.main(); model.assert_not_called(); save.assert_called_once_with(str(self.root))


if __name__ == '__main__': unittest.main()
