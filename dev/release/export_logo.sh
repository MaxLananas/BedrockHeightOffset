#!/usr/bin/env bash
# Regenerates the logo exports in assets/logo/ from the 1024 px master (altus-1024.png).
# Requires ImageMagick 6 or 7 (`convert`). The master is the only source of truth.
#
# Monochrome variant: the glyph is isolated by thresholding the red channel (amber is high,
# navy is low), restricted to the fully opaque tile interior, then recoloured to black.
set -euo pipefail
cd "$(dirname "$0")/../../assets/logo"

MASTER=altus-1024.png
command -v convert >/dev/null || { echo "ImageMagick 'convert' is required" >&2; exit 1; }

for size in 512 128 32; do
  convert "$MASTER" -filter Lanczos -resize "${size}x${size}" "altus-${size}.png"
done

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
convert "$MASTER" -background black -flatten -channel R -separate +channel -threshold 45% "$tmp/mask.png"
convert "$MASTER" -alpha extract -threshold 60% -morphology Erode Disk:5 "$tmp/alpha.png"
convert "$tmp/mask.png" "$tmp/alpha.png" -compose Multiply -composite "$tmp/mask-final.png"
convert "$MASTER" -alpha set \( "$tmp/mask-final.png" \) -compose CopyOpacity -composite \
  -channel RGB -evaluate set 0 +channel PNG32:altus-mono-1024.png
convert altus-mono-1024.png -filter Lanczos -resize 512x512 PNG32:altus-mono-512.png
echo "exports written to assets/logo/"
