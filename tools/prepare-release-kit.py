#!/usr/bin/env python3
"""CI only: check a real Gradle release APK using official installed build tools."""
from pathlib import Path
import json
import os
import re
import shutil
import subprocess
from release_support import application_id, version_name, sha256, validate_badging, validate_apk_archive, KIT_FILES

root = Path(__file__).resolve().parents[1]


def main():
    if (root / 'signing.properties').exists() or (root / 'signing').exists():
        raise RuntimeError('CI must not receive the private signing identity')
    source = root / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
    commit = os.environ.get('GITHUB_SHA', '')
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise RuntimeError('Missing GitHub build commit')
    # Never emit a successful kit from a missing or synthetic package.
    validate_apk_archive(source)
    kit = root / 'release-kit'
    if kit.exists():
        raise RuntimeError('Release kit already exists; use a clean CI checkout')
    kit.mkdir()
    destination = kit / 'app-release-unsigned.apk'
    subprocess.run([str(sdk / 'zipalign'), '-P', '16', '4', str(source), str(destination)], check=True)
    subprocess.run([str(sdk / 'zipalign'), '-c', '-P', '16', '4', str(destination)], check=True)
    badging = subprocess.check_output([str(sdk / 'aapt2'), 'dump', 'badging', str(destination)], text=True)
    metadata = validate_badging(badging, application_id(root), version_name(root))
    metadata.update(source_commit=commit, signed=False, android_runtime_tested=False,
                    native_abis=validate_apk_archive(destination),
                    github_run_id=os.environ.get('GITHUB_RUN_ID'),
                    github_repository=os.environ.get('GITHUB_REPOSITORY'),
                    build_tools='36.0.0', gradle='8.13', java='17',
                    signing_tool_source='Official Android SDK build-tools 36.0.0 on GitHub runner')
    (kit / 'apk-badging.txt').write_text(badging)
    (kit / 'build-evidence.json').write_text(json.dumps(metadata, indent=2) + '\n')
    shutil.copyfile(sdk / 'lib/apksigner.jar', kit / 'apksigner.jar')
    (kit / 'SHA256.json').write_text(json.dumps({name: sha256(kit / name) for name in sorted(KIT_FILES)}, indent=2) + '\n')
    print('Verified unsigned release kit created; local signing and Android execution remain separate gates.')


if __name__ == '__main__':
    main()
