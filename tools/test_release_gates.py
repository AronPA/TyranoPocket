"""Release rejection checks use deliberately synthetic ZIPs, never installable APKs."""
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from release_support import KIT_FILES, sha256, validate_badging, validate_apk_archive, validate_release_kit

PACKAGE = 'com.local.tyranopocket.p7e7af1'
COMMIT = 'a' * 40
BADGING = f"""package: name='{PACKAGE}' versionCode='1' versionName='1.0.0' compileSdkVersion='36' compileSdkVersionCodename='16'
sdkVersion:'26'
targetSdkVersion:'36'
application-label:'TyranoPocket'
launchable-activity: name='com.local.tyranopocket.MainActivity' label='TyranoPocket' icon=''
"""


class ReleaseGates(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def synthetic_archive(self, entries=None):
        path = self.directory / 'app-release-unsigned.apk'
        # Header-like fixtures test rejection logic only; no real DEX or Android binary is generated.
        base = {'AndroidManifest.xml': b'\x03\x00\x08\x00', 'classes.dex': b'dex\n035\x00', 'resources.arsc': b'fixture'}
        base.update(entries or {})
        with zipfile.ZipFile(path, 'w') as archive:
            for name, data in base.items():
                archive.writestr(name, data)
        return path

    def make_kit(self):
        self.synthetic_archive()
        (self.directory / 'apksigner.jar').write_bytes(b'NOT AN EXECUTABLE TOOL')
        (self.directory / 'apk-badging.txt').write_text(BADGING)
        metadata = validate_badging(BADGING, PACKAGE, '1.0.0')
        metadata.update(source_commit=COMMIT, signed=False, native_abis=[])
        (self.directory / 'build-evidence.json').write_text(json.dumps(metadata))
        self.rehash()

    def rehash(self):
        (self.directory / 'SHA256.json').write_text(json.dumps({name: sha256(self.directory / name) for name in KIT_FILES}))

    def test_release_metadata_without_permissions(self):
        self.assertEqual(validate_badging(BADGING, PACKAGE, '1.0.0')['target_sdk'], 36)

    def test_reject_wrong_target_compile_package_or_version(self):
        for old, new in [("targetSdkVersion:'36'", "targetSdkVersion:'35'"),
                         ("compileSdkVersion='36'", "compileSdkVersion='35'"),
                         (PACKAGE, 'other.package'), ("versionName='1.0.0'", "versionName='2.0.0'")]:
            with self.subTest(old=old), self.assertRaises(ValueError):
                validate_badging(BADGING.replace(old, new), PACKAGE, '1.0.0')

    def test_reject_debug_split_or_missing_launcher(self):
        variants = [BADGING + 'application-debuggable\n',
                    BADGING.replace("versionCode='1'", "versionCode='1' split='config.arm64_v8a'"),
                    BADGING.replace('launchable-activity:', 'no-launcher:')]
        for text in variants:
            with self.assertRaises(ValueError):
                validate_badging(text, PACKAGE, '1.0.0')

    def test_reject_network_permission_allow_signature_internal_permission(self):
        with self.assertRaises(ValueError):
            validate_badging(BADGING + "uses-permission: name='android.permission.INTERNET'\n", PACKAGE, '1.0.0')
        text = BADGING + f"uses-permission: name='{PACKAGE}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'\n"
        self.assertEqual(len(validate_badging(text, PACKAGE, '1.0.0')['permissions']), 1)

    def test_reject_plain_manifest_or_missing_dex_magic(self):
        for entries in [{'AndroidManifest.xml': b'<manifest />'}, {'classes.dex': b'not executable'}]:
            with self.assertRaises(ValueError):
                validate_apk_archive(self.synthetic_archive(entries))

    def test_reject_paths_and_native_abi_mismatch(self):
        for entries in [{'../secret': b'x'}, {'lib/x86_64/example.so': b'x'}]:
            with self.assertRaises(ValueError):
                validate_apk_archive(self.synthetic_archive(entries))

    def test_kit_requires_exact_reviewed_commit(self):
        self.make_kit()
        with self.assertRaises(ValueError):
            validate_release_kit(self.directory, 'b' * 40, PACKAGE, '1.0.0')
        with self.assertRaises(ValueError):
            validate_release_kit(self.directory, 'main', PACKAGE, '1.0.0')

    def test_kit_rejects_tampered_tool_and_manifest_paths(self):
        self.make_kit()
        (self.directory / 'apksigner.jar').write_bytes(b'tampered')
        with self.assertRaises(ValueError):
            validate_release_kit(self.directory, COMMIT, PACKAGE, '1.0.0')
        hashes = json.loads((self.directory / 'SHA256.json').read_text())
        hashes['../outside'] = '0' * 64
        (self.directory / 'SHA256.json').write_text(json.dumps(hashes))
        with self.assertRaises(ValueError):
            validate_release_kit(self.directory, COMMIT, PACKAGE, '1.0.0')

    def test_kit_rejects_inconsistent_manifest_or_symlink(self):
        self.make_kit()
        path = self.directory / 'build-evidence.json'
        metadata = json.loads(path.read_text()); metadata['target_sdk'] = 35
        path.write_text(json.dumps(metadata)); self.rehash()
        with self.assertRaises(ValueError):
            validate_release_kit(self.directory, COMMIT, PACKAGE, '1.0.0')
        self.make_kit()
        tool = self.directory / 'apksigner.jar'; tool.rename(self.directory / 'other.jar')
        tool.symlink_to(self.directory / 'other.jar')
        with self.assertRaises(ValueError):
            validate_release_kit(self.directory, COMMIT, PACKAGE, '1.0.0')


if __name__ == '__main__':
    unittest.main()
