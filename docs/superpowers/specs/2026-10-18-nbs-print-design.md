# NBS Print: design

Approved 2026-10-18.

## Goal

An Android app that prints from a phone to as many printers as possible, with real control over scaling.
Default is **100% (no scaling)**. It must work on devices with and without on-screen navigation buttons,
and ship as a downloadable APK in GitHub Releases.

## Decisions

| Question | Decision |
| --- | --- |
| Print engine | Android print framework (`PrintManager` + custom `PrintDocumentAdapter`). Works with any printer or print service Android can reach, plus Save as PDF. Direct IPP was rejected as large, network-only and untestable here. |
| Inputs | PDF, images (JPG/PNG/WebP/HEIC), plain text. Word/Excel/web are shared as PDF first. |
| Stack | Kotlin, Jetpack Compose, minSdk 26, targetSdk 35. |
| Repo | Public, `nbs-print`. |

## Scaling model (`layout/ScalingMath.kt`, pure Kotlin)

Units are points (1/72 in). Modes: ACTUAL (scale 1, the default), FIT, FILL, CUSTOM (10–400%).
Anchor: CENTER or TOP_LEFT, relative to the printable rectangle (paper minus margins). Content larger
than the printable area overflows symmetrically and is clipped. Optional auto-rotate turns a page a
quarter turn when its orientation disagrees with the paper's.

Images: 100% means physical size from an assumed DPI (default 300, user-selectable) because phone photos
carry no meaningful physical size. Text: scaling applies to the font (11 pt at 100%).

## Rendering

One code path, `PrintableDoc.render(page, sheet, options, pxPerPt)`, produces a bitmap of the sheet.
The preview uses it at ~115 dpi; the print job uses it at the printer's resolution (150–300 dpi, capped
by memory) and draws it onto a `PrintedPdfDocument` page, so the preview matches the output.

## Navigation modes

Edge-to-edge with explicit insets: the bottom Print bar pads for `safeDrawing` bottom + horizontal
insets, so it clears the 3-button bar, the gesture pill and cutouts. Verified on an emulator in
3-button and gesture modes, portrait and landscape.

## v1.1: page selection and direct IPP printing (approved 2026-10-18)

**Page selection.** `PageSelection` (All / This page / range text such as `1-3, 5, 8-`) is parsed by the
pure `PageRanges`. It applies to both print paths: `DocPrintAdapter` writes only the selected pages, and
direct printing renders only them. It resets to All for each new document, like scaling does.

**Direct printing.** Android's print framework always shows its dialog, so skipping it means speaking IPP
to the printer ourselves. The earlier "direct IPP" rejection (large, network-only, untestable) is
superseded at the user's request; the Android dialog stays as a second destination for USB, Bluetooth and
plugin-only printers.

- Discovery by mDNS (`_ipp._tcp`, `_ipps._tcp`) plus manual address entry, saved printers, remembered
  destination. Capabilities (formats, copies, color, sides, media, margins, scaling) come from
  Get-Printer-Attributes and shape the UI: only supported paper sizes, only supported options.
- Format: one PDF job when the printer accepts `application/pdf`; otherwise one JPEG job per page
  (density stamped into the JFIF header, no two-sided). PWG raster was left out: untestable without
  hardware and rarely the only option.
- Pages are rendered by the same `PrintableDoc.render` as the preview, at the printer's resolution
  (150-300 dpi), and sent with `print-scaling=none` when supported so 100% stays 100%. Landscape sheets are
  rotated into the portrait feed direction.
- Sockets are bound to the Wi-Fi/Ethernet network so a printer's no-internet Wi-Fi isn't bypassed via
  mobile data. Cleartext is permitted (printers are addressed by LAN IP); self-signed `ipps://` certificates
  are trusted only for local addresses. IPP 2.0 with a retry as 1.1.
- Job lifecycle: progress, completion polling, printer problems ("Out of paper"), cancel (Cancel-Job).
- Verified against `tools/mock_ipp_printer.py`: PDF and JPEG routes, IPP 1.1 fallback, page subset,
  copies, sides, color, media filtering, landscape rotation, error and abort handling, cancel. Not
  verified: mDNS discovery on a real LAN and real-printer quirks.

## Out of scope

Office/web conversion, tiling oversized content across sheets, vector PDF output, password-protected PDFs,
printer authentication, PWG-raster/URF output.
