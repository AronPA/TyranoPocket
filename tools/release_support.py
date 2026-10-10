"""Shared release gates; these validate artifacts, not Android runtime behavior."""
from pathlib import Path
import hashlib
import json
import re
import zipfile


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def application_id(root):
    match = re.search(r'^tyranopocket.applicationId=([a-zA-Z0-9_.]+)$',
                      (root / 'gradle.properties').read_text(), re.M)
    if not match:
        raise ValueError('Missing application ID')
    return match[1]


def version_name(root):
    match = re.search(r'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"',
                      (root / 'app/build.gradle.kts').read_text())
    if not match:
        raise ValueError('Missing release version')
    return match[1]


def validate_badging(text, package, version):
    package_line = next((line for line in text.splitlines() if line.startswith('package:')), '')
    attributes = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    expected = {'name': package, 'versionCode': '1', 'versionName': version, 'compileSdkVersion': '36'}
    if any(attributes.get(key) != value for key, value in expected.items()):
        raise ValueError('APK package/version/compileSdk differs from the reviewed project')
    if attributes.get('split'):
        raise ValueError('Split APK cannot be distributed as the single installer')
    for field, value in [('sdkVersion', '26'), ('targetSdkVersion', '36')]:
        if re.search(rf"^{field}:'{value}'$", text, re.M) is None:
            raise ValueError('APK SDK requirement mismatch: ' + field)
    if re.search(r'^application-debuggable\b', text, re.M):
        raise ValueError('Release APK is debuggable')
    if not re.search(r"^launchable-activity:.*\bname='com.local.tyranopocket.MainActivity'", text, re.M):
        raise ValueError('Missing expected launcher activity')
    permissions = re.findall(r"^uses-permission(?:-[\w-]+)?: name='([^']+)'", text, re.M)
    if set(permissions) - {package + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}:
        raise ValueError('Unexpected release permissions: ' + ', '.join(permissions))
    return {'application_id': package, 'version_name': version, 'version_code': 1,
            'compile_sdk': 36, 'target_sdk': 36, 'min_sdk': 26,
            'debuggable': False, 'permissions': permissions}


def validate_apk_archive(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK entries')
        if any(name.startswith('/') or '..' in name.split('/') or '\\' in name for name in names):
            raise ValueError('Unsafe APK entry')
        if not {'AndroidManifest.xml', 'classes.dex', 'resources.arsc'}.issubset(names):
            raise ValueError('Not a compiled Android application package')
        with archive.open('AndroidManifest.xml') as manifest:
            if manifest.read(4) != b'\x03\x00\x08\x00':
                raise ValueError('Android manifest is not binary AXML')
        with archive.open('classes.dex') as dex:
            if not dex.read(8).startswith(b'dex\n'):
                raise ValueError('Missing executable DEX header')
        broken = archive.testzip()
        if broken:
            raise ValueError('APK ZIP CRC failure')
        abis = sorted({name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')})
        if abis and 'arm64-v8a' not in abis:
            raise ValueError('APK native libraries do not support arm64-v8a')
    return abis


KIT_FILES = {'app-release-unsigned.apk', 'apksigner.jar', 'build-evidence.json', 'apk-badging.txt'}


def validate_release_kit(directory, commit, package, version):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('A verified full CI commit SHA is required')
    hashes = json.loads((directory / 'SHA256.json').read_text())
    if not isinstance(hashes, dict) or set(hashes) != KIT_FILES:
        raise ValueError('Unexpected release kit manifest')
    for name, digest in hashes.items():
        path = directory / name
        if path.is_symlink() or not path.is_file() or not re.fullmatch(r'[0-9a-f]{64}', digest):
            raise ValueError('Invalid release kit file')
        if sha256(path) != digest:
            raise ValueError('Release kit checksum mismatch: ' + name)
    evidence = json.loads((directory / 'build-evidence.json').read_text())
    if evidence.get('source_commit') != commit or evidence.get('signed') is not False:
        raise ValueError('Unexpected CI commit or signing state')
    metadata = validate_badging((directory / 'apk-badging.txt').read_text(), package, version)
    abis = validate_apk_archive(directory / 'app-release-unsigned.apk')
    if any(evidence.get(key) != value for key, value in metadata.items()) or evidence.get('native_abis') != abis:
        raise ValueError('Inconsistent APK build evidence')
    return evidence
