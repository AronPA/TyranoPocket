#!/usr/bin/env python3
"""Maintainer only. Keep the existing private signing identity local.

Before invocation the operator must verify the artifact belongs to a successful
run of the reviewed workflow and exact expected commit through GitHub Actions.
A manifest checksum is an integrity check, not an independent provenance proof.
"""
from pathlib import Path
import argparse
import json
import os
import re
import subprocess
import tempfile
from release_support import application_id, version_name, sha256, validate_release_kit, validate_apk_archive

root = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('release_kit', type=Path)
    parser.add_argument('--expected-commit', required=True)
    args = parser.parse_args()
    kit = args.release_kit.resolve()
    evidence = validate_release_kit(kit, args.expected_commit, application_id(root), version_name(root))
    # This is the existing key, never a newly generated debug/release identity.
    properties = dict(line.split('=', 1) for line in (root / 'signing.properties').read_text().splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    key = (root / properties['storeFile']).resolve()
    if key != (root / 'signing/tyranopocket.p12').resolve() or not key.is_file():
        raise RuntimeError('The existing project signing key is unavailable')
    fingerprint = re.search(r'Certificate SHA256: ([0-9a-f]{64})',
                            (root / 'evidence/signing-identity.txt').read_text())[1]
    env = os.environ.copy()
    env['TYRANOPOCKET_STORE_PASSWORD'] = properties['storePassword']
    env['TYRANOPOCKET_KEY_PASSWORD'] = properties['keyPassword']
    output = root.parent / 'deliverables'
    output.mkdir(exist_ok=True)
    final = output / ('TyranoPocket-' + version_name(root) + '.apk')
    if final.exists():
        raise RuntimeError('Refusing to overwrite an existing release APK')
    signer = ['java', '-jar', str(kit / 'apksigner.jar')]
    with tempfile.TemporaryDirectory(prefix='.tyranopocket-sign-', dir=output) as stage:
        candidate = Path(stage) / final.name
        subprocess.run(signer + ['sign', '--ks', str(key), '--ks-type', 'PKCS12',
            '--ks-key-alias', properties['keyAlias'], '--ks-pass', 'env:TYRANOPOCKET_STORE_PASSWORD',
            '--key-pass', 'env:TYRANOPOCKET_KEY_PASSWORD', '--min-sdk-version', '26',
            '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
            '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false',
            '--out', str(candidate), str(kit / 'app-release-unsigned.apk')],
            check=True, env=env, capture_output=True, text=True)
        verification = subprocess.check_output(signer + ['verify', '--verbose', '--print-certs',
            '--min-sdk-version', '26', '--max-sdk-version', '36', str(candidate)], text=True)
        match = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', verification)
        if match is None or match[1].lower() != fingerprint:
            raise RuntimeError('APK signer does not match the preserved signing identity')
        if not re.search(r'Verified using v2 scheme.*: true', verification):
            raise RuntimeError('APK Signature Scheme v2 verification failed')
        validate_apk_archive(candidate)
        evidence.update(signed=True, apk_sha256=sha256(candidate), certificate_sha256=fingerprint,
                        android_runtime_tested=False)
        (root / 'evidence/release-signature.txt').write_text(verification)
        (root / 'evidence/release-build.json').write_text(json.dumps(evidence, indent=2) + '\n')
        candidate.rename(final)
    print('Signed APK generated and signature verified: ' + final.name)
    print('Android 16 execution has NOT been inferred from successful signing.')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError:
        raise SystemExit('Official APK signing or verification failed; no final APK was published.')
