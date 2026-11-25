# Lumina Gallery v4.0 Pro

Kotlin + Jetpack Compose + Material 3 gallery. Package `com.lumina.gallery`. AMOLED black, English UI, minSdk 30 (Android 11), target/compile SDK 34.

## Build status (read this first)
The project was written in a sandbox with **no network, no Android SDK and no Gradle**, so it has **not been compiled**.
Static checks that were run: brace/paren balance, cross-file symbol references, missing imports (heuristic), XML and workflow YAML parsing.
The first real compile happens in GitHub Actions. If it fails, open the failed step, copy the first `e: file://...` error lines and fix or send them back.

## Build the Release APK on GitHub
1. Create a repo, push this folder (the `.github/workflows/build-apk.yml` file must stay at that path).
2. Actions -> "Build Release APK" -> Run workflow (it also runs on every push to main/master).
3. Download the `LuminaGallery-release-apk` artifact.

Signing: add secrets `LUMINA_KEYSTORE_BASE64`, `LUMINA_KEYSTORE_PASSWORD`, `LUMINA_KEY_ALIAS`, `LUMINA_KEY_PASSWORD` to use your own key.
Without them CI generates a throw-away key, so each build has a different signature (uninstall the old one before installing a new one).

CI note: the workflow uses the SDK preinstalled on the GitHub runner. If a build fails, open the run page and copy the "Build errors" summary.

Local build: open in Android Studio (JDK 17). The Gradle wrapper jar is binary and could not be generated offline; Android Studio creates it, or run `gradle wrapper --gradle-version 8.9`.

## Permissions
Media access (images/video), All-files access (PDF search, `.thumbnails` cleaner, WhatsApp `.Statuses`), Camera (QR), Biometric, Internet (Gemini OCR only).

## Honest notes on the "AI" parts
- **People**: ML Kit ACCURATE face detection with a strict 5-landmark geometry gate, min size, sharpness and YCbCr skin check, then an eye-aligned 64x64 crop turned into a hand-built feature vector (normalised patch + LBP histograms). This is not a deep face-embedding model, so expect some mistakes; Merge People exists for that. Thresholds are constants in `ai/Faces.kt` (`FaceEngine`). Swapping in a TFLite MobileFaceNet is the upgrade path.
- **AI HD Enhance / Magic Eraser / Natural Beauty**: classical image processing (upscale+unsharp, onion-peel inpainting, masked skin smoothing on detected faces). Not generative.
- **Studio working size** is capped at 2560 px on the long edge to keep memory safe.
- **Subject cut-out** uses Google Play services ML Kit; the model downloads on first use, so it needs internet once.
- **OmniSearch** indexes with ML Kit image labels, colours and OCR (screenshots, first 3 PDF pages). Indexing runs while the app process is alive and resumes from its saved progress.
- **OCR**: offline ML Kit (Latin/English) instantly; Bangla/Arabic/all scripts through Gemini with the fallback chain gemini-2.5-flash -> gemini-2.5-flash-lite -> gemini-2.0-flash. Every failure is shown with the exact model and HTTP error. There is no other fallback server.
- **Vault**: AES-256-GCM (1 MB chunks), Android Keystore key, PBKDF2 PIN hash, fingerprint unlock, FLAG_SECURE, auto-lock when the app leaves the foreground.
- **Back order**: Studio -> Viewer -> (clear multi-select) -> Folder -> other tab -> Albums -> exit.
