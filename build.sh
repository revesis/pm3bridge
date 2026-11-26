#!/usr/bin/env bash
# Build app-debug.apk inside a Docker container (no host Android SDK needed).
# Run on the build host (box92). Dev happens elsewhere; this only builds.
#
#   ./build.sh            # assembleDebug -> app/build/outputs/apk/debug/
#   ./build.sh clean assembleRelease
set -euo pipefail
cd "$(dirname "$0")"

# Container engine: honour $CONTAINER_ENGINE, else prefer podman, else docker.
# (This box has podman; the box92 build host has docker — same script works.)
ENGINE="${CONTAINER_ENGINE:-}"
if [ -z "${ENGINE}" ]; then
  if command -v podman >/dev/null 2>&1; then ENGINE=podman
  elif command -v docker >/dev/null 2>&1; then ENGINE=docker
  else echo "need podman or docker (or set CONTAINER_ENGINE)"; exit 1; fi
fi

IMAGE=pm3bridge-build:latest
GRADLE_ARGS=("${@:-assembleDebug}")

echo ">> engine: ${ENGINE}"
echo ">> building image ${IMAGE} (first run pulls the SDK; later runs are cached)"
"${ENGINE}" build -t "${IMAGE}" docker/

echo ">> gradle ${GRADLE_ARGS[*]}"
"${ENGINE}" run --rm \
  -v "${PWD}:/workspace" -w /workspace \
  -v pm3bridge-gradle-cache:/root/.gradle \
  "${IMAGE}" \
  gradle --no-daemon "${GRADLE_ARGS[@]}"

APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "${APK}" ] && echo ">> APK: ${PWD}/${APK}" || echo ">> build finished (no debug APK; check the task above)"
