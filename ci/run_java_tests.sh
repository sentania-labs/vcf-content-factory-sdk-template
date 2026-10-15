#!/usr/bin/env bash
# Compiles and runs the adapter's plain-main Java tests (no test framework,
# no external deps beyond a JDK). Exits non-zero on any compile error or
# failed assertion. Run from anywhere:
#   ci/run_java_tests.sh
# The tested classes have no VCF Ops SDK or framework-jar dependency, so
# neither jar is needed; .github/workflows/pak-tests.yml runs this on every
# push and pull request. When you rename the example package, rename it
# here too, and add each new test class to the loop.
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" -sourcepath src:tests \
    tests/com/vcfcf/adapters/example/*.java
for t in ExampleConfigTest; do
    java -cp "$out" "com.vcfcf.adapters.example.$t"
done
