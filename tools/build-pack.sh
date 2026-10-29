#!/usr/bin/env bash
# Builds an offline tile pack (MBTiles + manifest.json) or checks one.
#
#   tools/build-pack.sh build --pbf FILE --bbox W,S,E,N --out DIR [--zoom 12,14] [--id ID] [--name NAME] [--layers transportation,water]
#   tools/build-pack.sh verify DIR|FILE.mbtiles
#   tools/build-pack.sh bbox --center LAT,LON --radius-km 200
#
# Planetiler does the slicing; this script owns the manifest, the atomic swap and the checks.
#
# Behind a proxy: PLANETILER_JAVA_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890"
#   (planetiler's downloader is a plain JVM HTTP client, so it reads those system properties).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${MIONAV_PACK_CACHE:-$HOME/.cache/mionav}"
PLANETILER_VERSION="${PLANETILER_VERSION:-0.8.0}"
PLANETILER_JAR="${PLANETILER_JAR:-$CACHE/planetiler-$PLANETILER_VERSION.jar}"
PLANETILER_URL="${PLANETILER_URL:-https://github.com/onthegomap/planetiler/releases/download/v$PLANETILER_VERSION/planetiler.jar}"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*" >&2; }

usage() {
  sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

require_tool() { command -v "$1" >/dev/null 2>&1 || die "missing tool: $1"; }

manifest_value() {  # manifest_value <file> <key>
  jq -r --arg key "$2" '.[$key] // empty' "$1"
}

# --- verify -------------------------------------------------------------------------------

cmd_verify() {
  local target="${1:-}"
  [[ -n "$target" ]] || die "verify needs a pack directory or an .mbtiles file"
  local mbtiles manifest
  if [[ -d "$target" ]]; then
    mbtiles="$target/region.mbtiles"
    manifest="$target/manifest.json"
  else
    mbtiles="$target"
    manifest="$(dirname "$target")/manifest.json"
  fi
  [[ -f "$mbtiles" ]] || die "no tiles at $mbtiles"
  [[ -f "$manifest" ]] || die "no manifest at $manifest"
  require_tool jq
  require_tool sha256sum
  require_tool stat

  local problems=0
  local want_bytes want_sha want_tiles want_zoom
  want_bytes="$(manifest_value "$manifest" bytes)"
  want_sha="$(manifest_value "$manifest" sha256)"
  want_tiles="$(manifest_value "$manifest" tiles)"
  want_zoom="$(jq -r '.zoom // [] | join(",")' "$manifest")"

  local have_bytes have_sha
  have_bytes="$(stat -c %s "$mbtiles")"
  have_sha="$(sha256sum "$mbtiles" | cut -d' ' -f1)"

  if [[ "$have_bytes" != "$want_bytes" ]]; then
    note "bytes mismatch: manifest $want_bytes, file $have_bytes"
    problems=$((problems + 1))
  fi
  if [[ "$have_sha" != "$want_sha" ]]; then
    note "sha256 mismatch: manifest ${want_sha:0:16}…, file ${have_sha:0:16}…"
    problems=$((problems + 1))
  fi

  if command -v sqlite3 >/dev/null 2>&1; then
    local have_tiles
    have_tiles="$(sqlite3 "$mbtiles" 'select count(*) from tiles;' 2>/dev/null || echo "")"
    if [[ -z "$have_tiles" ]]; then
      note "tiles: cannot read the mbtiles table"
      problems=$((problems + 1))
    elif [[ "$have_tiles" != "$want_tiles" ]]; then
      note "tiles mismatch: manifest $want_tiles, file $have_tiles"
      problems=$((problems + 1))
    fi
  else
    note "sqlite3 not found: tile count not checked"
  fi

  if [[ "$problems" -ne 0 ]]; then
    note "verify failed with $problems problem(s): $mbtiles"
    return 1
  fi
  note "ok: $mbtiles ($want_tiles tiles, $want_bytes bytes, zoom $want_zoom)"
}

# --- bbox ---------------------------------------------------------------------------------

cmd_bbox() {
  local center="" radius=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --center) center="$2"; shift 2 ;;
      --radius-km) radius="$2"; shift 2 ;;
      *) die "bbox: unknown argument $1" ;;
    esac
  done
  [[ -n "$center" && -n "$radius" ]] || die "bbox needs --center LAT,LON and --radius-km N"
  require_tool python3
  python3 - "$center" "$radius" <<'PY'
import math, sys
lat, lon = (float(v) for v in sys.argv[1].split(','))
km = float(sys.argv[2])
dlat = km / 111.32
dlon = km / (111.32 * max(math.cos(math.radians(lat)), 1e-6))
print(f"{lon - dlon:.5f},{lat - dlat:.5f},{lon + dlon:.5f},{lat + dlat:.5f}")
PY
}

# --- build --------------------------------------------------------------------------------

ensure_planetiler() {
  [[ -f "$PLANETILER_JAR" ]] && return 0
  require_tool curl
  mkdir -p "$(dirname "$PLANETILER_JAR")"
  note "downloading planetiler $PLANETILER_VERSION (about 25 MB) to $PLANETILER_JAR"
  curl -fsSL -o "$PLANETILER_JAR.part" --retry 2 "$PLANETILER_URL" ||
    die "download failed; set PLANETILER_JAR=/path/to/planetiler.jar"
  mv "$PLANETILER_JAR.part" "$PLANETILER_JAR"
}

cmd_build() {
  local pbf="" bbox="" out="" zoom="12,14" id="" name="" layers=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --pbf) pbf="$2"; shift 2 ;;
      --bbox) bbox="$2"; shift 2 ;;
      --out) out="$2"; shift 2 ;;
      --zoom) zoom="$2"; shift 2 ;;
      --layers) layers="$2"; shift 2 ;;
      --id) id="$2"; shift 2 ;;
      --name) name="$2"; shift 2 ;;
      *) die "build: unknown argument $1" ;;
    esac
  done
  [[ -n "$pbf" && -n "$bbox" && -n "$out" ]] || die "build needs --pbf, --bbox and --out"
  [[ -f "$pbf" ]] || die "no OSM extract at $pbf"
  [[ "$bbox" =~ ^-?[0-9.]+,-?[0-9.]+,-?[0-9.]+,-?[0-9.]+$ ]] || die "bad --bbox: $bbox"
  [[ "$zoom" =~ ^[0-9]+,[0-9]+$ ]] || die "bad --zoom: $zoom"

  require_tool java
  ensure_planetiler

  local min_zoom="${zoom%,*}" max_zoom="${zoom#*,}"  local slug="${id:-$(basename "$pbf" .osm.pbf)}"
  slug="$(printf '%s' "$slug" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]\+/-/g; s/^-//; s/-$//')"
  local label="${name:-$slug}"

  mkdir -p "$(dirname "$out")"
  # Build beside the target, then swap: a failed run must not destroy a good pack.
  local staging=""
  trap 'rm -rf "${staging:-}"' EXIT
  staging="$(mktemp -d "$(dirname "$out")/.build-pack.XXXXXX")"

  # The HUD draws roads and water only; dropping the rest is the biggest size lever there is.
  local only_layers=()
  if [[ -n "$layers" ]]; then
    [[ "$layers" =~ ^[a-z_,]+$ ]] || die "bad --layers: $layers"
    only_layers=(--only_layers="$layers")
  fi

  local sources_dir="$CACHE/planetiler-sources"
  mkdir -p "$sources_dir"

  local java_opts="${PLANETILER_JAVA_OPTS:-}"
  note "slicing $pbf ($bbox, z$min_zoom–$max_zoom) into $staging/region.mbtiles"
  note "sources cached in $sources_dir (first run downloads ~1.3 GB)"
  # shellcheck disable=SC2086
  java -Xmx2g ${java_opts} -jar "$PLANETILER_JAR" \
    --profile=openmaptiles \
    --osm-path="$pbf" \
    --bounds="$bbox" \
    --minzoom="$min_zoom" \
    --maxzoom="$max_zoom" \
    --download \
    "${only_layers[@]}" \
    --download_dir="$sources_dir" \
    --force \
    --output="$staging/region.mbtiles" >&2

  [[ -f "$staging/region.mbtiles" ]] || die "planetiler produced no tiles"

  local bytes sha tiles
  bytes="$(stat -c %s "$staging/region.mbtiles")"
  sha="$(sha256sum "$staging/region.mbtiles" | cut -d' ' -f1)"
  if command -v sqlite3 >/dev/null 2>&1; then
    tiles="$(sqlite3 "$staging/region.mbtiles" 'select count(*) from tiles;')"
  else
    note "sqlite3 not found: writing tiles=0"
    tiles=0
  fi

  local version
  version="${MIONAV_PACK_VERSION:-$(date -u +%Y.%m.%d)}"

  jq -n \
    --arg id "$slug" --arg name "$label" --arg bbox "$bbox" \
    --argjson minZoom "$min_zoom" --argjson maxZoom "$max_zoom" \
    --arg version "$version" --argjson tiles "$tiles" --argjson bytes "$bytes" \
    --arg sha "$sha" --arg layers "$layers" \
    '{
       id: $id,
       name: $name,
       bbox: ($bbox | split(",") | map(tonumber)),
       zoom: [$minZoom, $maxZoom],
       version: $version,
       tiles: $tiles,
       bytes: $bytes,
       sha256: $sha,
       style: "motohud-6class-v1",
       layers: ($layers | split(","))
     }' > "$staging/manifest.json"

  cmd_verify "$staging"

  mkdir -p "$out"
  mv -f "$staging/region.mbtiles" "$out/region.mbtiles"
  mv -f "$staging/manifest.json" "$out/manifest.json"
  note "wrote $out/region.mbtiles ($bytes bytes, $tiles tiles)"
}

# --- entry --------------------------------------------------------------------------------

cmd="${1:-}"
shift || true
case "$cmd" in
  build) cmd_build "$@" ;;
  verify) cmd_verify "$@" ;;
  bbox) cmd_bbox "$@" ;;
  -h|--help|help|"") usage 0 ;;
  *) usage 1 ;;
esac
