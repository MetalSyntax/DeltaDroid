#!/bin/bash
# Baja los núcleos libretro (arm64). Las skins "Classic" originales ya están en el repo.
# Opcional, SOLO para compilaciones personales: con la ruta a tu propio IPA de Delta reemplaza las skins
# por las Standard de Delta y agrega su base de títulos. Esos archivos no tienen licencia para
# redistribuirse: no publiques un APK compilado así.
# Uso: tools/fetch_assets.sh [/ruta/Delta.ipa]
set -euo pipefail
IPA="${1:-}"
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

[ -z "$IPA" ] && exit 0
echo "Uso personal: skins y base de Delta (no redistribuir)"
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
