# NBS Print

An Android app for printing PDFs, photos and text from your phone, with real control over **scaling**.
Printing defaults to **100% (no scaling)**, so a page prints at its true physical size instead of being
shrunk to fit.

## Download

Grab the latest APK from the [Releases page](../../releases/latest) and open it on your phone
(you'll need to allow "Install unknown apps" for your browser or file manager the first time).

Requires Android 8.0 (API 26) or newer.

## Features

- **Scaling options:** 100% / no scaling (default), Fit to page, Fill page, or Custom 10–400%.
- **Position:** centered or top-left, with auto-rotate for landscape-on-portrait pages.
- **Margins:** printer minimum, none, or custom (mm). Paper: Letter, A4, Legal, A5, A3, Tabloid, 4×6, 5×8.
- **Images print at true size.** Photos carry no reliable physical size, so 100% assumes an image
  resolution you can pick (72 / 150 / 300 / 600 dpi, default 300).
- **Works with any printer Android can print to.** NBS Print hands finished pages to Android's print
  system, so it works with Mopria / Default Print Service, HP, Epson, Brother, Canon and Samsung print
  plugins, and *Save as PDF*. Copies, color, duplex and page range are chosen in Android's print dialog.
- **Open from anywhere.** Share a PDF, photo (or several) or text to NBS Print from any app, or use *Open with*.
- **Works with 3-button, 2-button and gesture navigation**, portrait or landscape. The UI is
  edge-to-edge and applies system-bar insets, so nothing hides behind the navigation bar.

## Setting up a printer

If the print dialog only offers *Save as PDF*, no print service is installed yet. Tap the gear icon in
NBS Print (or go to Android Settings → Connected devices → Printing) and enable *Default Print Service*
or install your printer maker's plugin from Google Play.

## Limitations

- PDFs are rasterized at up to 300 dpi when printed (Android has no vector PDF-to-PDF path), so text in
  the printed output is not selectable.
- Password-protected PDFs aren't supported. Word, Excel and web pages need to be shared as PDF first.
- Content larger than the paper at 100% is cropped (centered), not split across several sheets.

## Building

Requires JDK 17+ and the Android SDK (Android Studio's bundled JDK works).

```bash
./gradlew :app:testDebugUnitTest   # scaling math tests
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

## How it works

`layout/ScalingMath.kt` is pure Kotlin that turns content size, paper size, margins and scale settings
into a placement and transform. It is unit tested without Android. `doc/` renders PDFs (`PdfRenderer`),
images and text onto a bitmap of the sheet using that transform; the on-screen preview and the real print
job share this code, so the preview matches what prints. `print/DocPrintAdapter.kt` implements Android's
`PrintDocumentAdapter` on top of it.

## License

MIT, see [LICENSE](LICENSE).
