# MCR PDF Studio

An Android PDF editor. Opens anything the platform can read, edits it on the
device, and saves back where it came from.

**Install:** grab `mcr-pdf-studio.apk` from the
[latest release](https://github.com/McDc777/MCR/releases/tag/latest) on the
phone itself, tap it, and allow installation when Android asks.

---

## What it does

**Viewing**
- Continuous vertical reader, tap any page for pinch-to-zoom
- Night mode that inverts page colours without touching the file
- Opens PDFs shared from other apps (browser, mail, file managers)

**Text**
- Lists the text on a page run by run — the granularity a PDF actually stores
- Replaces text *in the original font*: the show-text operand is removed from
  the content stream and redrawn with the same font object, size and colour
- Find across the whole document, with or without case matching
- True redaction: paints over the text *and* strips the underlying characters
  so they cannot be copied back out
- Adds new text in any script, at any position

**Forms**
- Reads AcroForm fields — text, multiline, checkbox, radio, combo, list
- Fills them, refreshes appearances, and flattens when you are done
- Smart fill: describe your details in plain language and the fields get matched

**Markup**
- Pen and highlighter (multiply-blended, so text underneath stays readable)
- Rectangles, ovals, lines, arrows
- White-out boxes, sticky notes, image and signature stamps
- Everything is drawn into page content, so it renders in every viewer

**Pages**
- Rotate, delete, duplicate, reorder, insert blanks
- Extract a selection into a new file
- Merge other PDFs in, or split into chunks

**Convert in and out**
- Images to PDF (fit, fill or actual size), plain text to PDF, blank documents
- PDF to PNG / JPEG / WebP at 72–600 dpi, one tall image, plain text, or HTML

**Scans**
- On-device OCR that writes recognised words back as an invisible text layer,
  so the page looks identical but becomes searchable and selectable
- Five bundled recognition models — Latin, Chinese, Japanese, Korean and
  Devanagari — plus an automatic mode that runs them all and keeps whichever
  reads the page best. All offline; nothing is downloaded on first use.

**Document structure**
- Bookmarks: read the outline, jump to any entry, add your own, or generate
  one entry per page
- Embedded file attachments: list, attach, extract
- Imposition at 1/2/4/6/9/16 pages per sheet — which doubles as a true resize,
  since every page is scaled onto its slot
- Reversible cropping (moves the crop box, deletes nothing)
- Headers and footers in six slots with `{n}`, `{total}`, `{bates}`, `{date}`
  and `{title}`, including Bates numbering for legal and audit work
- Shrink a document by downsampling oversized images, leaving text and vector
  content untouched

**On the phone**
- Print through Android, so real printers and "Save as PDF" both work
- Read the page aloud, choosing the voice language from the page's own script
- Signature pad: draw once, then tap anywhere to place it — stored as a
  trimmed transparent PNG and reusable across documents

**Security and metadata**
- AES-256 passwords with per-operation permissions, or strip protection
- Title, author, subject, keywords
- Text and image watermarks, page numbering

**AI** (optional)
- Summarise, explain, translate, proofread, extract tables or fields, ask
  questions, draft new documents, smart-fill forms
- Uses your own Anthropic API key, stored encrypted on the device and sent
  only to `api.anthropic.com`. No key, no AI — everything else works offline.

**Any language**
- 30 Noto font families ship inside the APK and are unpacked on first run, so
  scripts render correctly even on devices with a thin font set; the device's
  own fonts remain the fallback
- CJK is bundled too — Simplified Chinese, Traditional Chinese, Hong Kong,
  Japanese and Korean. These are the TrueType builds on purpose: the
  `.ttc` Android itself ships is CFF-based OpenType, which PdfBox cannot embed
  as a Type 0 font, so relying on the device font would silently drop CJK text
- Japanese is distinguished from Chinese by the presence of kana, so text gets
  the right glyph forms rather than merely legible ones
- Fonts are chosen per script automatically:
  CJK, Arabic, Hebrew, Devanagari, Bengali, Tamil, Telugu, Kannada, Malayalam,
  Gujarati, Gurmukhi, Sinhala, Thai, Lao, Khmer, Myanmar, Ethiopic, Georgian,
  Armenian, Cyrillic, Greek and Latin
- Arabic is shaped into its contextual forms and bidirectional text is
  reordered, which is work a PDF file cannot do for itself

---

## How it is put together

Two PDF engines, each doing what it is best at:

| Job | Engine |
| --- | --- |
| Rendering pages to screen and to images | `android.graphics.pdf.PdfRenderer` |
| Reading and rewriting document structure | PdfBox-Android |
| OCR | ML Kit on-device text recognition |

```
core/     document session, undo snapshots, SAF I/O, preferences
fonts/    per-script font resolution, Arabic shaping, bidi
ops/      page, form, text, annotation, watermark, security, convert, OCR
viewer/   page rasterizer
ai/       Anthropic client and prompts
ui/       Compose screens and the view model
```

The file on disk is the single source of truth. Each edit loads the document,
mutates it, and saves straight back rather than holding a live object in
memory — a little slower, but the rendered page can never drift out of step
with the model, and memory stays flat on documents with hundreds of pages.
Undo works by snapshotting that file, so it covers every operation uniformly.

## Build

```sh
./gradlew :app:assembleRelease
```

CI builds every push and republishes the `latest` release. Each CI build is
signed with a freshly generated sideload key, so if Android refuses to install
over an older build, uninstall the previous version first.

The APK is deliberately a single universal build with every ABI, all five OCR
models and all bundled fonts — around 90 MB. Nothing is fetched at first run,
so everything works with no network and no Play Services.

Build outcomes are published as an annotated `ci-status` tag, so failures can
be read with `git fetch origin refs/tags/ci-status && git cat-file tag ci-status`
rather than opening the Actions UI.

## Honest limits

- **Text does not reflow.** Replacing a word with a longer one takes more
  width; it does not push later paragraphs down. No PDF editor reflows
  arbitrary page text, because a PDF stores positioned glyphs rather than
  paragraphs.
- **Font matching is best-effort.** The original font is reused whenever it can
  encode the new characters. Subsetted fonts frequently cannot — a font
  embedded with only the glyphs for "Invoice" has no `z` to give you — and then
  the closest font on the device is substituted.
- **OCR covers five scripts.** Latin, Chinese, Japanese, Korean and Devanagari
  are bundled. Arabic, Thai and Hebrew have no on-device model available.
- **Complex-script shaping is simplified.** Arabic joining and bidi reordering
  are implemented; full Indic reordering and rare ligatures are not.
- **Encrypted documents need their password** to be opened at all, which is
  the point of encryption.
