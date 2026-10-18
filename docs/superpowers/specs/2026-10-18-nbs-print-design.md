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

## Out of scope

Direct IPP, Office/web conversion, tiling oversized content across sheets, vector PDF output,
password-protected PDFs.
