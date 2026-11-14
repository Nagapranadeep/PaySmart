#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p work/classes
mapfile -t sources < <(find src/main/java src/test/java -name '*.java' -print | sort)
if command -v javac >/dev/null 2>&1; then
  javac --release 21 -encoding UTF-8 -d work/classes "${sources[@]}"
else
  java --module jdk.compiler/com.sun.tools.javac.Main -source 21 -target 21 -encoding UTF-8 -d work/classes "${sources[@]}"
fi
