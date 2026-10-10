#!/usr/bin/env python3
"""Allow only application/build source in the public GitHub checkout.

Generated test games stay local or are recreated in CI. This check never prints
matched secret values. --manifest prepares the exact source upload allowlist.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TOP = {
    '.gitignore', 'README.md', 'THIRD_PARTY_NOTICES.md',
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
    'gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.properties',
    'gradle/wrapper/gradle-wrapper.jar', 'licenses/Gradle-Apache-2.0.txt',
    '.github/workflows/android-release.yml',
    'app/build.gradle.kts', 'core/build.gradle.kts',
    'app/src/main/assets/runtime-lifecycle.js',
    'app/src/main/assets/tyranopocket-compat.js',
}
TOOLS = {
    'test-core.sh', 'test-shim.mjs', 'test-android.py', 'make_fixtures.py',
    'official-fixtures.mjs', 'package.json', 'package-lock.json',
    'release_support.py', 'prepare-release-kit.py', 'finish-release.py',
    'test_release_gates.py', 'check-public-source.py',
}
WRAPPER_SHA = '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'
SECRETS = [
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----'),
    re.compile(rb'\bgh[pousr]_[A-Za-z0-9]{30,}\b'),
    re.compile(rb'\bgithub_pat_[A-Za-z0-9_]{30,}\b'),
    re.compile(rb'\bAKIA[0-9A-Z]{16}\b'),
]


def allowed(name):
    path = Path(name)
    if name in TOP:
        return True
    if path.parent.as_posix() == 'tools' and path.name in TOOLS:
        return True
    if name.startswith('core/src/'):
        return path.suffix == '.java'
    if name.startswith('app/src/'):
        return path.suffix in {'.kt', '.xml'} and 'assets' not in path.parts
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tracked', action='store_true')
    parser.add_argument('--manifest', type=Path)
    args = parser.parse_args()
    if args.tracked:
        raw = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT)
        names = [p.decode('utf-8') for p in raw.split(b'\0') if p]
    else:
        names = [p.relative_to(ROOT).as_posix() for p in ROOT.rglob('*')
                 if p.is_file() and allowed(p.relative_to(ROOT).as_posix())]
    # Compare against the existing local passwords without displaying them.
    private_values = []
    properties = ROOT / 'signing.properties'
    if properties.is_file():
        for line in properties.read_text().splitlines():
            if '=' in line and line.split('=', 1)[0] in {'storePassword', 'keyPassword'}:
                value = line.split('=', 1)[1].encode()
                if value:
                    private_values.append(value)
    entries = []
    for name in sorted(names):
        path = ROOT / name
        if not allowed(name) or path.is_symlink() or not path.is_file():
            raise SystemExit('Disallowed public source path: ' + name)
        data = path.read_bytes()
        if path.suffix == '.jar':
            if hashlib.sha256(data).hexdigest() != WRAPPER_SHA:
                raise SystemExit('Gradle Wrapper checksum mismatch')
        else:
            data.decode('utf-8')
        if any(pattern.search(data) for pattern in SECRETS) or any(value in data for value in private_values):
            raise SystemExit('Secret scan rejected a source file: ' + name)
        entries.append({'path': name, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    if not entries:
        raise SystemExit('No public source files selected')
    if args.manifest:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + '\n')
    print(f'Public source audit passed: {len(entries)} files; generated games and private signing material excluded.')


if __name__ == '__main__':
    main()
