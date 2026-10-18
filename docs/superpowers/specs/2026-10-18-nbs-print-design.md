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

## v1.2: the "Sheet" redesign (approved 2026-10-18)

Chosen from three concepts (Sheet, Dial, Sentence) that were drawn up from research into highly rated
minimal mobile UI on Dribbble. Decision: build **Sheet**, borrowing Dial's **page filmstrip** and
**printing ring**.

- **Principles carried over from the research:** one hero object per screen; type does the work; a single
  accent (vermilion, only ever the "true size" dot and the current-page dot); options hidden behind
  compact tiles that open into sheets; pill buttons; no gradients.
- **Home:** header (file, page count, open and printer-settings buttons); the preview as a paper with a
  scale badge; a filmstrip (multi-page documents only) where tapping a thumbnail includes or skips that page
  and moves the preview to it; tiles Scale, Pages, Paper, Printer, plus Options when printing directly;
  a Print pill. Swiping the paper browses pages without changing the selection.
- **Sheets:** Scale (hero numeral, segmented 100% / Fit / Fill / Custom, slider with a detent mark at
  100, exact field), Pages (All, This page, Odd, Even, Range), Paper & layout, Options (copies, color,
  two-sided), and the printer picker. Confirm buttons sit in a footer pinned below the scrolling content, so
  they stay above 3-button navigation even on a short landscape screen.
- **Printing ring:** full-screen job progress. Tick marks fill as pages are prepared; a highlight sweeps
  round while the printer works; if the printer reports `job-impressions-completed` the ring shows real
  pages printed. Finished and failed states share the screen.
- **Theme:** Hanken Grotesk (variable font, OFL), warm-grey ground, white tiles, ink type; a dark variant.
- **Layout:** portrait stacks everything with Print last; landscape puts the paper and filmstrip left and
  the tiles right with Print pinned. Verified in 3-button and gesture navigation, light and dark.

## v1.2.1: wrong-size printouts on image-only printers (2026-10-18)

**Report:** a PDF page printed with no scaling came out at about a quarter of its size, printing directly
to a printer.

**Cause:** the printer didn't accept PDF, so the app sent each page as a 300 dpi JPEG with the density in
the JFIF header. Printers are free to ignore that and print a JPEG at their own native resolution; a
1200 dpi engine prints 2550 pixels as 2.125 in, 300/1200 = 25%. (The pages the app renders were verified
exact on Android 13 and 16, in the preview, in the system print dialog and in the output files, so the
fault was in how the printer read the file, not in the layout.)

**Fix:**
- New route order: PDF, then **PWG raster**, then JPEG. PWG raster (PWG 5102.4) carries `HWResolution` and
  `PageSize` in every page header and is mandatory for IPP Everywhere printers. The encoder is ported
  from CUPS's reference writer (header offsets checked mechanically against the C struct) and verified by
  round-tripping through a decoder written from CUPS's reader. The printer's own
  `pwg-raster-document-resolution-supported` and `-type-supported` choose the dpi (nearest 300, within
  150-400) and sRGB or gray.
- JPEG, now last: send only the printable area (paper minus hardware margins) with `print-scaling=fit`, so
  the result doesn't depend on how the printer reads resolution; accurate to a few percent, and the UI says so.
- **Send as** (Auto / PDF / Raster / JPEG) in the Options sheet when a printer takes several formats, so a
  misbehaving printer can be worked around without a new release.
- Two-sided stays PDF-only (raster duplex needs per-printer back-side transforms that can't be tested here).
- Verified end to end against the mock printer on Android 16 with the signed build: the PWG job decodes to
  2550x3300 px at 300 dpi = 8.50 x 11.00 in with the 1-inch calibration square exactly 72 pt at (72, 72).
  Not verified: real printers.

## v1.2.2: multi-page jobs failing with "0x0507" on Canon PIXMA (2026-10-18)

**Report:** printing 40 pages directly to a Canon MG3600 series printed page 1, then showed
"Couldn't print / 0x0507: Not allowed to print".

**Cause (inferred from the symptom; the printer itself was not available):** IPP status 0x0507 is
`server-error-busy` (RFC 8011), not "not allowed". Canon PIXMA inkjets advertise AirPrint raster and JPEG but
no PDF, so the app fell to the JPEG route, which sends one job per page. The printer refused the second job
because the first was still printing. The error text was also wrong: the status table had the 0x05xx codes
misnamed.

**Fix:**
- Status names corrected to RFC 8011; busy and temporary-error replies are retried every 3 s (up to 100
  times) with "Waiting for the printer to be ready...".
- JPEG route is sequential: submit a page, poll the job until it completes, then submit the next, with
  progress "Page n of N". A failure says which page failed and that earlier pages were already sent.
- New **AirPrint raster (URF)** route, tried after PWG raster and before JPEG: one multi-page job. Encoder is
  the same PackBits-style row coding as PWG raster with Apple's 12-byte file header and 32-byte page headers
  (ported from CUPS). Printers listing only 600 or 1200 dpi get a 300 dpi render with each pixel repeated, so
  header and size stay truthful without a huge bitmap.
- Sockets prefer the Wi-Fi network but fall back to the default route if connecting fails (never after a
  request was sent, so a job is never duplicated).
- **Printer details** disclosure in Options with Copy, to diagnose printers remotely.
- Verified against the mock on Android 16: a `--jpeg-only --one-at-a-time` printer received all 5 pages
  sequentially with progress; a `--urf-only --one-at-a-time` printer received a single 5-page URF job that
  decodes to 2550x3300 px at 300 dpi. Not verified: the real Canon (unknown which formats it lists).

## v1.2.3: AirPrint raster printed about 10% small on a real Canon (2026-10-18)

**Report:** after 1.2.2 a 1 inch square printed at about 0.9 inch on the user's Canon MG3600. No scaling must
mean true size, and 1.2.1's JPEG route had been right on that printer.

**Cause:** 1.2.2 made the AirPrint raster route the automatic choice for printers that list URF and JPEG. It
was exact on the mock printer but had never met a real printer, and this one does not print it 1:1.
The exact reason is unknown (the printer wasn't available).

**Fix:** route order is now PDF, PWG raster, **JPEG**, AirPrint raster. JPEG (cropped to the printable area,
`print-scaling=fit`) is the route with real-printer evidence of true size, and 1.2.2 already made it work for
many pages by sending one page at a time. AirPrint raster stays available under Options > Send as.

## Out of scope

Office/web conversion, tiling oversized content across sheets, vector PDF output, password-protected PDFs,
printer authentication, two-sided on the raster routes.
