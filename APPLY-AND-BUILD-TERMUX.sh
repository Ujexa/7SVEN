#!/data/data/com.termux/files/usr/bin/bash
# 7SVEN v1.4.1 (agent build): applies the new sources over your existing ~/7SVEN project and builds the APK.
# Run in Termux:  bash ~/storage/shared/Download/APPLY-AND-BUILD-TERMUX.sh
set -Eeuo pipefail

ROOT="${SVEN_ROOT:-$HOME/7SVEN}"
GRADLE="${GRADLE_BIN:-$HOME/tools/gradle-8.2/bin/gradle}"
DL="$HOME/storage/shared/Download"
[ -x "$GRADLE" ] || GRADLE="$(command -v gradle || true)"
[ -n "$GRADLE" ] || { echo "ERROR: Gradle not found. Set GRADLE_BIN=/path/to/gradle"; exit 1; }
[ -d "$ROOT/app/src/main" ] || { echo "ERROR: $ROOT not found. Set SVEN_ROOT to your project folder."; exit 1; }

# The zip may arrive with dots or underscores in the version (v1.4.1 or v1_4_1).
ZIP="$(ls -t "$DL"/7SVEN-agent-v1[._]4[._]1-source*.zip 2>/dev/null | head -1 || true)"
[ -n "$ZIP" ] || { echo "ERROR: put 7SVEN-agent-v1_4_1-source.zip (or v1.4.1 spelling) in $DL"; exit 1; }
echo "Using $ZIP"

BACKUP="$HOME/7SVEN-backup-$(date +%Y%m%d-%H%M%S)"
echo "Backing up current sources to $BACKUP"
mkdir -p "$BACKUP"
cp -a "$ROOT/app/src/main/java" "$ROOT/app/src/main/AndroidManifest.xml" "$ROOT/app/build.gradle" "$BACKUP/" 2>/dev/null || true

TMP="$(mktemp -d)"
unzip -q -o "$ZIP" -d "$TMP"
SRC="$TMP"
[ -d "$SRC/app" ] || SRC="$(find "$TMP" -maxdepth 2 -type d -name app | head -1 | xargs dirname)"
cp -a "$SRC/app/src/." "$ROOT/app/src/"
cp -a "$SRC/app/build.gradle" "$ROOT/app/build.gradle"
rm -rf "$TMP"

# Sanity checks for pieces that were NOT replaced (native libs and wake-word model from v0.8.1).
if [ ! -f "$ROOT/app/src/main/jniLibs/arm64-v8a/libllama.so" ]; then
  echo "ERROR: native libraries missing in $ROOT/app/src/main/jniLibs/arm64-v8a/."
  echo "Re-run your v0.8.1 APPLY-AND-BUILD script once, then run this one again."
  exit 1
fi
if ! find "$ROOT/app/src/main/assets" -name tokens.txt 2>/dev/null | grep -q .; then
  echo "WARNING: wake-word model assets not found. The agent still works; wake word needs the v0.8.1 setup script."
fi

cd "$ROOT"
echo "Building (first run after changes can take several minutes)..."
"$GRADLE" --no-daemon assembleDebug

APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
[ -n "$APK" ] || { echo "ERROR: build finished but no APK was found."; exit 1; }
cp "$APK" "$DL/7SVEN-v1.4.1-debug.apk"
echo
echo "Done: $DL/7SVEN-v1.4.1-debug.apk"
echo "Install it (it updates 7SVEN in place and keeps your model and notes), then open SETUP in the app."
