#!/usr/bin/env bash
# Build app-debug.apk inside a Docker container (no host Android SDK needed).
# Run on the build host (box92). Dev happens elsewhere; this only builds.
#
#   ./build.sh            # assembleDebug -> app/build/outputs/apk/debug/
#   ./build.sh clean assembleRelease
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=pm3bridge-build:latest
GRADLE_ARGS=("${@:-assembleDebug}")

echo ">> building image ${IMAGE} (first run pulls the SDK; later runs are cached)"
docker build -t "${IMAGE}" docker/

echo ">> gradle ${GRADLE_ARGS[*]}"
docker run --rm \
  -v "${PWD}:/workspace" -w /workspace \
  -v pm3bridge-gradle-cache:/root/.gradle \
  "${IMAGE}" \
  gradle --no-daemon "${GRADLE_ARGS[@]}"

APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "${APK}" ] && echo ">> APK: ${PWD}/${APK}" || echo ">> build finished (no debug APK; check the task above)"
