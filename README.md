# NBS Print

An Android app for printing PDFs, photos and text from your phone, with real control over **scaling**
and **which pages print**. Printing defaults to **100% (no scaling)**, so a page prints at its true
physical size instead of being shrunk to fit. It can print **straight to a Wi-Fi printer from the app**,
or hand the job to Android's own print dialog.

## Download

Grab the latest APK from the [Releases page](../../releases/latest) and open it on your phone
(you'll need to allow "Install unknown apps" for your browser or file manager the first time).

Requires Android 8.0 (API 26) or newer.

## Features

- **Scaling options:** 100% / no scaling (default), Fit to page, Fill page, or Custom 10–400%.
- **Page selection:** all pages, just the page you're looking at, or a range like `1-3, 5, 8-`. The
  preview dims pages that won't print, and the Print button shows how many will.
- **Print straight from the app.** Pick a printer once and NBS Print sends the job itself, with copies,
  color / black & white and two-sided printing, and no Android print screen. See below.
- **Android print dialog too.** Printers that can't be reached directly (USB, Bluetooth, or ones that need
  their maker's plugin) still print through Android's print system, with the same scaling and page
  selection. Switch with the printer row above the Print button.
- **Position:** centered or top-left, with auto-rotate for landscape-on-portrait pages.
- **Margins:** printer minimum, none, or custom (mm). Paper: Letter, A4, Legal, A5, A3, Tabloid, 4×6, 5×8.
- **Images print at true size.** Photos carry no reliable physical size, so 100% assumes an image
  resolution you can pick (72 / 150 / 300 / 600 dpi, default 300).
- **Open from anywhere.** Share a PDF, photo (or several) or text to NBS Print from any app, or use *Open with*.
- **Works with 3-button, 2-button and gesture navigation**, portrait or landscape. The UI is
  edge-to-edge and applies system-bar insets, so nothing hides behind the navigation bar.

## Printing directly to a printer

Tap the printer row above the Print button. NBS Print lists printers it finds on your Wi-Fi (the same
discovery AirPrint and Mopria use), or you can add one by IP address or name. Your choice is remembered.

Once a printer is chosen, the app asks it what it supports and only offers that: its paper sizes, color,
two-sided options and maximum copies. The preview also uses the printer's real margins. After you tap
Print it shows the printer's progress ("Printing…", "Out of paper") and lets you cancel.

How it works: the app speaks IPP, the standard network-printing protocol, and sends the pages you see in
the preview as a PDF. Printers that don't accept PDF get one JPEG per page instead (no two-sided there,
and copies come out uncollated). The job is marked "don't rescale" so 100% stays 100%. If a printer
doesn't offer that setting, the app tells you it may resize slightly.

Direct printing needs a printer that supports IPP on the local network. Most Wi-Fi and Ethernet printers
from the last decade do. Printers that require a login aren't supported yet. Because printers are addressed
by LAN IP, the app permits unencrypted (cleartext) connections; it only ever connects to printers you
chose or typed in. For `ipps://` printers the self-signed certificate is accepted only for local addresses.

## Setting up a printer for the Android dialog

If the print dialog only offers *Save as PDF*, no print service is installed yet. Tap the gear icon in
NBS Print (or go to Android Settings → Connected devices → Printing) and enable *Default Print Service*
or install your printer maker's plugin from Google Play.

## Limitations

- PDFs are rasterized at up to 300 dpi when printed (Android has no vector PDF-to-PDF path), so text in
  the printed output is not selectable.
- Password-protected PDFs aren't supported. Word, Excel and web pages need to be shared as PDF first.
- Content larger than the paper at 100% is cropped (centered), not split across several sheets.
- Direct printing has been tested against a mock IPP printer (see below), not yet against every make of
  real printer. If one misbehaves, the Android print dialog remains available.

## Building

Requires JDK 17+ and the Android SDK (Android Studio's bundled JDK works).

```bash
./gradlew :app:testDebugUnitTest   # scaling, page range, IPP and job-ticket tests
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

For a signed release build, create `keystore.properties` in the repo root (it is git-ignored):

```properties
storeFile=path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

then run `./gradlew :app:assembleRelease`.

## Testing direct printing without a printer

`tools/mock_ipp_printer.py` is a small fake IPP printer. It saves every document the app sends, plus the
job attributes, so you can inspect exactly what a real printer would receive:

```bash
python tools/mock_ipp_printer.py --port 8631 --out scratch/jobs
```

From the Android emulator the host is `10.0.2.2`, so add the printer as `10.0.2.2:8631`. Flags simulate
awkward printers: `--jpeg-only`, `--ipp11`, `--no-scaling-attr`, `--reason media-empty-error`,
`--abort-jobs`, and `--polls 40` (a slow printer, for testing Cancel).

## How it works

`layout/ScalingMath.kt` is pure Kotlin that turns content size, paper size, margins and scale settings
into a placement and transform; `layout/PageSelection.kt` parses page ranges. Both are unit tested without
Android. `doc/` renders PDFs (`PdfRenderer`), images and text onto a bitmap of the sheet using that
transform; the on-screen preview, the Android print dialog and direct printing all share this code, so the
preview matches what prints. `print/DocPrintAdapter.kt` implements Android's `PrintDocumentAdapter`.
`ipp/` holds the IPP codec, capability parsing, HTTP client, mDNS discovery and saved printers, and
`print/DirectPrintJob.kt` renders, sends and follows a direct job.

## License

MIT, see [LICENSE](LICENSE).
