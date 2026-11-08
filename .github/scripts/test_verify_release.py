import hashlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import zipfile

import verify_release as verify


class VerificationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.tools = self.directory / 'tools'
        self.package = 'org.smarttube.stable'
        self.version = '32.74'
        self.code = 2464
        self.cert = verify.CERT
        self.create_assets()
        self.runner = patch.object(verify.subprocess, 'run', side_effect=self.command)
        self.runner.start()
        self.addCleanup(self.runner.stop)

    def create_assets(self):
        for abi, name in verify.NAMES.items():
            self.create_apk(self.directory / name, verify.ABIS[abi])

    def create_apk(self, path, abis):
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('AndroidManifest.xml', b'binary manifest')
            for abi in sorted(abis):
                archive.writestr(f'lib/{abi}/libtest.so', b'ELF fixture')

    def command(self, args, **kwargs):
        if Path(args[0]).name == 'aapt':
            output = f"package: name='{self.package}' versionCode='{self.code}' versionName='{self.version}' platformBuildVersionName='14'\n"
        else:
            output = f'Verifies\nSigner #1 certificate SHA-256 digest: {self.cert}\n'
        return subprocess.CompletedProcess(args, 0, output, '')

    def check(self):
        return verify.artifacts(self.directory, '32.74', 2464, self.tools)

    def test_valid_assets_have_deterministic_hashes(self):
        expected_names = {'SmartTube_stable_arm64-v8a.apk',
                          'SmartTube_stable_armeabi-v7a.apk',
                          'SmartTube_stable_x86.apk',
                          'SmartTube_stable_universal.apk'}
        expected = {name: hashlib.sha256((self.directory / name).read_bytes()).hexdigest()
                    for name in expected_names}
        self.assertEqual(self.check(), expected)

    def test_wrong_package_version_code_and_signer_fail(self):
        for attribute, value in [('package', 'org.smarttube.beta'), ('version', '32.73'),
                                 ('code', 2463), ('cert', '0' * 64), ('cert', '')]:
            with self.subTest(attribute=attribute, value=value):
                old = getattr(self, attribute)
                setattr(self, attribute, value)
                with self.assertRaises(ValueError):
                    self.check()
                setattr(self, attribute, old)

    def test_invalid_signature_command_fails_closed(self):
        with patch.object(verify.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'apksigner')):
            with self.assertRaises(subprocess.CalledProcessError):
                self.check()

    def test_wrong_abi_contents_fail(self):
        for abis in [set(), {'x86'}, {'arm64-v8a', 'x86'}]:
            with self.subTest(abis=abis):
                self.create_apk(self.directory / verify.NAMES['arm64-v8a'], abis)
                with self.assertRaises(ValueError):
                    self.check()

    def test_missing_or_extra_assets_fail(self):
        path = self.directory / verify.NAMES['x86']
        original = path.read_bytes()
        path.unlink()
        with self.assertRaises(ValueError):
            self.check()
        path.write_bytes(original)
        (self.directory / 'unexpected.apk').write_bytes(original)
        with self.assertRaises(ValueError):
            self.check()

    def data(self):
        return {'package': {key: [f'https://example.test/{abi}.apk',
                                 f'https://mirror.test/{abi}.apk']
                            for key, abi in verify.KEYS.items()},
                '32.73': {'versionCode': 2463}, '32.74': {'versionCode': 2464}}

    def fetch(self, url, path):
        abi = Path(url).stem
        Path(path).write_bytes((self.directory / verify.NAMES[abi]).read_bytes())

    def test_manifest_checks_every_url_and_highest_version(self):
        with patch.object(verify, 'download', side_effect=self.fetch) as fetch:
            records = verify.manifest(self.data(), self.directory, self.tools)
        self.assertEqual(fetch.call_count, 8)
        expected = [
            {'url': url, 'abi': abi, 'versionName': '32.74', 'versionCode': 2464,
             'sha256': hashlib.sha256(
                 (self.directory / f'SmartTube_stable_{abi}.apk').read_bytes()).hexdigest()}
            for key, abi in sorted(verify.KEYS.items())
            for url in self.data()['package'][key]
        ]
        self.assertEqual(records, expected)

    def test_manifest_rejects_stale_download_even_with_valid_signature(self):
        self.version = '32.73'
        self.code = 2463
        with patch.object(verify, 'download', side_effect=self.fetch):
            with self.assertRaises(ValueError):
                verify.manifest(self.data(), self.directory, self.tools)

    def test_manifest_missing_variant_or_url_fails(self):
        for missing in [True, False]:
            data = self.data()
            if missing:
                del data['package']['downloadUrlList_x86']
            else:
                data['package']['downloadUrlList_x86'] = []
            with patch.object(verify, 'download', side_effect=self.fetch):
                with self.assertRaises(ValueError):
                    verify.manifest(data, self.directory, self.tools)

    def test_source_version_ignores_old_manifest(self):
        source = self.directory / 'build.gradle'
        source.write_text('versionCode 2465\nversionName "32.75"\n')
        self.assertEqual(verify.source_version(source), ('32.75', 2465))

    def test_artifact_mode_rejects_wrong_tag_before_inspection(self):
        source = self.directory / 'build.gradle'
        source.write_text('versionCode 2464\nversionName "32.74"\n')
        args = ['verify_release.py', 'artifacts', '--directory', str(self.directory),
                '--gradle', str(source), '--tag', 'v32.73',
                '--output', str(self.directory / 'SHA256SUMS')]
        with patch('sys.argv', args), self.assertRaises(ValueError):
            verify.main()

    def test_download_rejects_untrusted_initial_urls(self):
        for url in ['http://github.com/a.apk', 'file:///etc/passwd',
                    'https://localhost/a.apk', 'https://github.com.evil.test/a.apk',
                    'https://github.com:444/a.apk', 'https://user@github.com/a.apk',
                    'https://release-assets.githubusercontent.com/a.apk']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                verify.download(url, self.directory / 'download.apk')

    def test_redirect_rejects_untrusted_hosts_before_request(self):
        handler = verify.SafeRedirect()
        for url in ['http://github.com/a', 'https://127.0.0.1/a',
                    'https://evil.test/a', 'https://githubusercontent.com.evil.test/a']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                handler.redirect_request(None, None, 302, 'Found', {}, url)
        verify.validate_url('https://release-assets.githubusercontent.com/asset')
        verify.validate_url('https://objects.githubusercontent.com/asset')

    def test_download_caps_declared_and_streamed_size(self):
        for headers, payload in [({'Content-Length': '9'}, b''),
                                 ({}, b'123456789')]:
            response = MagicMock()
            response.__enter__.return_value = response
            response.url = 'https://release-assets.githubusercontent.com/asset'
            response.headers = headers
            response.read.side_effect = io.BytesIO(payload).read
            opener = MagicMock()
            opener.open.return_value = response
            with patch.object(verify, 'MAX_DOWNLOAD_BYTES', 8), \
                    patch.object(verify.urllib.request, 'build_opener', return_value=opener):
                with self.assertRaises(ValueError):
                    verify.download('https://github.com/a.apk', self.directory / 'large.apk')

    def test_download_accepts_bounded_github_asset_and_hashable_bytes(self):
        response = MagicMock()
        response.__enter__.return_value = response
        response.url = 'https://release-assets.githubusercontent.com/asset'
        response.headers = {'Content-Length': '3'}
        response.read.side_effect = io.BytesIO(b'apk').read
        opener = MagicMock()
        opener.open.return_value = response
        destination = self.directory / 'download.apk'
        with patch.object(verify.urllib.request, 'build_opener', return_value=opener):
            verify.download('https://github.com/a.apk', destination)
        self.assertEqual(destination.read_bytes(), b'apk')


if __name__ == '__main__':
    unittest.main()
