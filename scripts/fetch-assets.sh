#!/usr/bin/env bash
# Downloads the fonts and OCR language data that ship inside the APK.
#
# These are fetched at build time rather than committed so the repository stays
# text-only. The app degrades to the device's own fonts when a family is
# missing, so a partial fetch is a warning rather than a failure.
set -uo pipefail

FONTS=app/src/main/assets/fonts
TESSDATA=app/src/main/assets/tessdata
mkdir -p "$FONTS" "$TESSDATA"

ok=0
missing=""

# Tries the upstream repository, then a CDN mirror of the same commit.
fetch() {
  local dest="$1" name="$2" primary="$3" secondary="$4"
  if [ -s "$dest/$name" ]; then
    ok=$((ok + 1))
    return
  fi
  if curl -fsSL --retry 3 --max-time 300 -o "$dest/$name" "$primary" \
    || curl -fsSL --retry 3 --max-time 300 -o "$dest/$name" "$secondary"; then
    ok=$((ok + 1))
    echo "  $name ($(du -h "$dest/$name" | cut -f1))"
  else
    rm -f "$dest/$name"
    missing="$missing $name"
  fi
}

NOTO="
NotoSans/NotoSans-Regular
NotoSans/NotoSans-Bold
NotoSerif/NotoSerif-Regular
NotoNaskhArabic/NotoNaskhArabic-Regular
NotoSansHebrew/NotoSansHebrew-Regular
NotoSansDevanagari/NotoSansDevanagari-Regular
NotoSansBengali/NotoSansBengali-Regular
NotoSansTamil/NotoSansTamil-Regular
NotoSansTelugu/NotoSansTelugu-Regular
NotoSansKannada/NotoSansKannada-Regular
NotoSansMalayalam/NotoSansMalayalam-Regular
NotoSansGujarati/NotoSansGujarati-Regular
NotoSansGurmukhi/NotoSansGurmukhi-Regular
NotoSansSinhala/NotoSansSinhala-Regular
NotoSansThai/NotoSansThai-Regular
NotoSansLao/NotoSansLao-Regular
NotoSansKhmer/NotoSansKhmer-Regular
NotoSansMyanmar/NotoSansMyanmar-Regular
NotoSansEthiopic/NotoSansEthiopic-Regular
NotoSansGeorgian/NotoSansGeorgian-Regular
NotoSansArmenian/NotoSansArmenian-Regular
NotoSansThaana/NotoSansThaana-Regular
NotoSansCherokee/NotoSansCherokee-Regular
NotoSansMongolian/NotoSansMongolian-Regular
NotoSansTifinagh/NotoSansTifinagh-Regular
"

echo "Fonts:"
for entry in $NOTO; do
  family="${entry%%/*}"
  name="${entry##*/}.ttf"
  fetch "$FONTS" "$name" \
    "https://raw.githubusercontent.com/notofonts/notofonts.github.io/main/fonts/$family/hinted/ttf/$name" \
    "https://cdn.jsdelivr.net/gh/notofonts/notofonts.github.io@main/fonts/$family/hinted/ttf/$name"
done

# CJK must be the TrueType build: PdfBox cannot embed the CFF-based OpenType
# collection Android itself ships, so the device font is not a usable fallback.
for family in NotoSansSC NotoSansTC NotoSansJP NotoSansKR NotoSansHK; do
  name="$family-VF.ttf"
  fetch "$FONTS" "$name" \
    "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/Variable/TTF/Subset/$name" \
    "https://cdn.jsdelivr.net/gh/notofonts/noto-cjk@main/Sans/Variable/TTF/Subset/$name"
done

# tessdata_best is the float LSTM model: larger and more accurate than the
# standard build, which is the trade we want for Arabic-script text.
echo "OCR language data:"
for lang in fas ara; do
  name="$lang.traineddata"
  fetch "$TESSDATA" "$name" \
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/$name" \
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata/main/$name"
done

echo "Bundled $ok asset(s)."
[ -n "$missing" ] && echo "Unavailable (falling back to device fonts):$missing"
du -sh "$FONTS" "$TESSDATA" 2>/dev/null || true

if [ "$ok" -eq 0 ]; then
  echo "No assets could be fetched" >&2
  exit 1
fi
exit 0
