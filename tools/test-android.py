#!/usr/bin/env python3
"""Maintainer gate for an attached disposable Android 16 emulator. Not run here."""
from pathlib import Path
import os, re, shutil, subprocess, sys
root=Path(__file__).resolve().parents[1]
evidence=root/'evidence';evidence.mkdir(exist_ok=True)
def run(args):
    result=subprocess.run(args,cwd=root,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    if result.returncode:raise RuntimeError(result.stdout)
    return result.stdout
try:
    if not shutil.which('adb'):raise RuntimeError('Android SDK platform-tools and API 36 emulator unavailable')
    package=re.search(r'^tyranopocket.applicationId=(.+)$',(root/'gradle.properties').read_text(),re.M)[1]
    if run(['adb','shell','getprop','ro.kernel.qemu']).strip()!='1':raise RuntimeError('Requires an isolated emulator, not a personal phone')
    if run(['adb','shell','getprop','ro.build.version.sdk']).strip()!='36':raise RuntimeError('Required API 36 not present')
    run(['sh','gradlew',':app:assembleDebug',':app:assembleDebugAndroidTest'])
    for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
        run(['adb','install','-r','-t',apk])
    run(['adb','logcat','-c'])
    for phase in ['phaseOneImportRenderSaveAudioRotation','phaseTwoAfterForceStop']:
        output=run(['adb','shell','am','instrument','-w','-r','-e','class',f'com.local.tyranopocket.Android36Test#{phase}',package+'.test/androidx.test.runner.AndroidJUnitRunner'])
        (evidence/(phase+'.txt')).write_text(output)
        if 'OK (1 test)' not in output:raise RuntimeError('Instrumentation did not report a passing test')
        run(['adb','shell','am','force-stop',package])
    logs=run(['adb','logcat','-d','-v','threadtime']);(evidence/'logcat.txt').write_text(logs)
    for marker in ['FATAL EXCEPTION','SecurityException','FileNotFoundException','OutOfMemoryError']:
        if marker in logs:raise RuntimeError('Logcat requires review: '+marker)
    print('Synthetic renderer lifecycle phases passed. SAF picker, real TyranoScript, and release APK checks still required.')
except Exception as error:
    (evidence/'android-test-attempt.txt').write_text('BLOCKED OR FAILED: '+str(error)+'\n')
    print(str(error),file=sys.stderr);sys.exit(2)
