#!/bin/bash
# Baja los núcleos libretro (arm64) y extrae las skins .deltaskin del IPA de Delta.
# Uso: tools/fetch_assets.sh /ruta/Delta_1_6.ipa
set -euo pipefail
IPA="${1:?Uso: $0 /ruta/Delta.ipa}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"; SKINS="$ROOT/app/src/main/assets/skins"
mkdir -p "$LIBS" "$SKINS"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

for core in fceumm snes9x gambatte mgba mupen64plus_next_gles3 melonds genesis_plus_gx; do
  out="$LIBS/lib${core}_libretro_android.so"
  [ -s "$out" ] && continue
  echo "Núcleo $core"
  curl -fsSL "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/${core}_libretro_android.so.zip" -o "$TMP/c.zip"
  unzip -p "$TMP/c.zip" "${core}_libretro_android.so" > "$out"
done

# Base de títulos/carátulas por SHA-1 que usa Delta
unzip -p "$IPA" Payload/Delta.app/openvgdb.sqlite > "$ROOT/app/src/main/assets/openvgdb.sqlite"

# id del sistema en la app -> skin dentro del IPA
while read -r id path; do
  unzip -p "$IPA" "Payload/Delta.app/$path/Standard.deltaskin" > "$SKINS/$id.deltaskin"
  echo "Skin $id"
done <<'LIST'
nes NESDeltaCore.bundle
snes SNESDeltaCore.bundle
gbc GBCDeltaCore.bundle
gba GBADeltaCore.bundle
n64 Mupen64Plus.bundle
ds DSDeltaCore.bundle
genesis Frameworks/Systems.framework/GPGXDeltaCore_GPGXDeltaCore.bundle
LIST
