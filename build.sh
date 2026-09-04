#!/usr/bin/env bash
set -euo pipefail

rm -rf build bin

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_NAME="XProxy"
MAIN_CLASS="org.jjgroup.xproxy.XProxyKt"
ICON_PATH="${SCRIPT_DIR}/resources/ninja.icns"
JAR_PATH="${SCRIPT_DIR}/build/libs/xproxy.jar"
PACKAGE_DIR="${SCRIPT_DIR}/build/package"
JPACKAGE_INPUT_DIR="${PACKAGE_DIR}/input"
APP_IMAGE_DIR="${PACKAGE_DIR}/app-image"
DMG_DIR="${PACKAGE_DIR}/dmg"
INFO_FILE="${SCRIPT_DIR}/src/org/jjgroup/xproxy/core/Info.kt"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Error: DMG packaging is only supported on macOS."
  exit 1
fi

if ! command -v jpackage >/dev/null 2>&1; then
  echo "Error: jpackage not found. Please install JDK 17+ with jpackage available."
  exit 1
fi

if [[ ! -f "${ICON_PATH}" ]]; then
  echo "Error: icon file not found: ${ICON_PATH}"
  exit 1
fi

if [[ ! -f "${INFO_FILE}" ]]; then
  echo "Error: version file not found: ${INFO_FILE}"
  exit 1
fi

TODAY_VERSION="v$(date +%Y.%-m.%-d)"
export TODAY_VERSION

perl -i -pe 's/(const\s+val\s+version\s*=\s*")v\d+\.\d+\.\d+(")/$1$ENV{TODAY_VERSION}$2/' "${INFO_FILE}"

echo "Updated Info.version to ${TODAY_VERSION}"

echo "[1/3] Building fat jar..."
"${SCRIPT_DIR}/gradlew" fatJar

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Error: fat jar not found after build: ${JAR_PATH}"
  exit 1
fi

echo "[2/3] Building macOS app image..."
rm -rf "${JPACKAGE_INPUT_DIR}" "${APP_IMAGE_DIR}" "${DMG_DIR}"
mkdir -p "${JPACKAGE_INPUT_DIR}" "${APP_IMAGE_DIR}" "${DMG_DIR}"
cp "${JAR_PATH}" "${JPACKAGE_INPUT_DIR}/"

jpackage \
  --type app-image \
  --name "${APP_NAME}" \
  --input "${JPACKAGE_INPUT_DIR}" \
  --main-jar "$(basename "${JAR_PATH}")" \
  --main-class "${MAIN_CLASS}" \
  --icon "${ICON_PATH}" \
  --java-options "-Xmx4g" \
  --dest "${APP_IMAGE_DIR}"

echo "[3/4] Building DMG..."
jpackage \
  --type dmg \
  --name "${APP_NAME}" \
  --app-image "${APP_IMAGE_DIR}/${APP_NAME}.app" \
  --icon "${ICON_PATH}" \
  --dest "${DMG_DIR}" \
  --about-url http://github.com/TheKingOfDuck/xproxy

echo "[4/4] Renaming DMG with version..."
dmg_files=("${DMG_DIR}"/*.dmg)
if [[ ${#dmg_files[@]} -eq 0 || ! -f "${dmg_files[0]}" ]]; then
  echo "Error: no DMG generated in ${DMG_DIR}"
  exit 1
fi

target_dmg="${DMG_DIR}/${APP_NAME}_${TODAY_VERSION}.dmg"
mv -f "${dmg_files[0]}" "${target_dmg}"

echo "Done. DMG generated: ${target_dmg}"
