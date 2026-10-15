#!/usr/bin/env bash
# Compiles and runs the adapter's plain-main Java tests (no test framework,
# no external deps beyond a JDK). Exits non-zero on any compile error,
# failed assertion, or when no tests are found. Run from anywhere:
#   ci/run_java_tests.sh
# Every tests/**/*Test.java is a test: its class is derived from its path
# and its main() is run, so a new test needs no edit here. Helpers (T.java)
# are compiled but not run. The tested classes must have no VCF Ops SDK or
# framework-jar dependency; .github/workflows/pak-tests.yml runs this on
# every push and pull request.
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
mapfile -t sources < <(find tests -name '*.java' | sort)
mapfile -t tests < <(find tests -name '*Test.java' | sort)
if [ "${#tests[@]}" -eq 0 ]; then
    echo "no tests/**/*Test.java found" >&2
    exit 1
fi
javac -d "$out" -sourcepath src:tests "${sources[@]}"
for f in "${tests[@]}"; do
    cls="${f#tests/}"
    cls="${cls%.java}"
    java -cp "$out" "${cls//\//.}"
done
