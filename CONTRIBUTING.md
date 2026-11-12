# Contributing / Katkıda bulunma

Thanks for helping improve EduCast TV! / EduCast TV'yi geliştirmeye yardım ettiğiniz için teşekkürler!

## Project layout / Proje yapısı

| Path | What it is |
|---|---|
| `app/src/main/java/com/derstv/app/MainActivity.java` | The whole app: WebView, remote control, video mode, full screen, subtitles bridge, auto-update |
| `app/src/main/assets/subtitles.js` | Subtitle overlay injected into lecture pages (caption fetch, translation, sync) |
| `app/src/main/res/values*/strings.xml` | UI texts — English (`values`) and Turkish (`values-tr`) |
| `tools/make_icons.py` | Generates the launcher icon and TV banner from `tools/icon.b64` and `tools/banner.b64` |
| `.github/workflows/build.yml` | CI: debug APK for pull requests, signed release on `main` |

## Building / Derleme

- Android Studio, or JDK 17 + Gradle 8.9: `python3 tools/make_icons.py && gradle assembleDebug`
- Every pull request is built automatically; download the debug APK from the workflow run's **Artifacts**.
  Her pull request otomatik derlenir; deneme APK'sı iş akışının **Artifacts** bölümündedir.
- Debug builds use a debug signing key and have auto-update disabled. Uninstall the release app before installing a debug build (Android refuses to install over a different signature).
  Deneme derlemesini kurmadan önce yayın sürümünü kaldırın (imzalar farklıdır).

## Rules / Kurallar

1. **Privacy first.** Never send user credentials, cookies, viewing history or any personal data to any server. Session data must stay in the WebView storage on the TV.
   Kullanıcı verisi hiçbir sunucuya gönderilmez; oturum bilgisi yalnızca TV'de kalır.
2. **Unofficial and neutral.** Do not add Udemy logos or trademarks to the app, icons or package name.
   Udemy logosu/markası uygulamaya, simgelere veya paket adına eklenmez.
3. **Both languages.** Every user-visible text goes into both `values/strings.xml` (English) and `values-tr/strings.xml` (Turkish).
   Her yeni metin İngilizce ve Türkçe olarak eklenir.
4. **No new dependencies without a good reason.** The app intentionally has none (no AndroidX), so it runs on old TVs (Android 5+).
5. Keep pull requests small and describe how you tested them on a real TV or emulator.

## Releases / Sürümler

Only the maintainer's `main` branch publishes signed releases. The signing key lives in the repository secrets
(`SIGNING_KEYSTORE_B64`, `SIGNING_PASSWORD`) and is never committed. Forks that add their own secrets publish
their own releases, and their builds check their own repository for updates.

By contributing you agree that your contributions are licensed under the [GPL-3.0](LICENSE).
