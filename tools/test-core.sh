#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .core-classes evidence
python3 tools/make_fixtures.py
shopt -s globstar
java -m jdk.compiler/com.sun.tools.javac.Main --release 8 -Xlint:all -d .core-classes core/src/**/*.java
java -Xmx48m -cp .core-classes com.local.tyranopocket.core.CoreTests fixtures .test-work | tee evidence/core-tests.txt
