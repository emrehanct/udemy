# EduCast TV

> **Bu resmi bir uygulama değildir.** Kullanıcıların halihazırda sahip oldukları eğitim içeriklerini TV arayüzünde izlemelerini sağlayan bağımsız (unofficial) bir istemcidir (client).
>
> **This is not an official app.** It is an independent (unofficial) client that lets users watch the learning content they already own on a TV interface.
>
> EduCast TV is not affiliated with, endorsed by or sponsored by Udemy, Inc. "Udemy" is a trademark of its respective owner.

**Eğitimin Büyük Ekran Hali** — Android TV için, kumandayla kullanılan sade bir Udemy istemcisi.
A simple, remote-friendly Udemy client for Android TV.

[Türkçe](#türkçe) · [English](#english)

---

## Türkçe

### Özellikler
- Udemy'ye kendi hesabınızla giriş yapın ve satın aldığınız kursları TV'de izleyin.
- Kumandayla kullanım: yön tuşları imleci gezdirir; ders sayfasında video modu (oynat/durdur, 10 sn ileri/geri).
- Tam ekran, ▲ ile sığdır / doldur / yakınlaştır.
- **VLC tarzı alt menü** (yalnızca video izlerken, OK ile açılır): oynatma hızı (0,75×–2×), altyazı dili ve boyutu, görüntü, önceki/sonraki ders.
- Her zaman en yüksek görüntü kalitesi.
- **Türkçe altyazı:** yabancı dilli derslerde altyazı otomatik Türkçeye çevrilir; Türkçe derslerde altyazı gösterilmez. Uzun OK ile Otomatik / Kapalı / Her zaman açık arasında geçilir.
- Video hatasında dersi kendiliğinden yeniden yükler ve kaldığı yerden devam eder.
- **Otomatik güncelleme:** yeni sürüm yayınlanınca ekranda uyarı çıkar, onaylayınca kendini günceller.
- Arayüz Türkçe ve İngilizce (TV'nin diline göre).

### Kurulum
1. [Releases](../../releases/latest) sayfasından `EduCastTV.apk` dosyasını indirin.
2. TV'ye kurun (USB bellek, "Downloader" uygulaması ya da `adb install EduCastTV.apk`).
3. Sonraki güncellemeler uygulamanın içinden gelir.

### Kumanda
| Tuş | İmleç modu | Video modu |
|---|---|---|
| Yön tuşları | İmleci gezdirir | ◀ ▶ 10 sn geri/ileri, ▼ tam ekran, ▲ imleç (tam ekranda: yakınlaştır) |
| OK | Tıklar | **Alt menüyü açar:** önceki/sonraki ders, ±10 sn, oynat/durdur, hız, altyazı (mod, yazı boyutu, çeviri dili), görüntü, tam ekran. Menüde ◀ ▶ gezinir, OK seçer, ▲ / Geri kapatır |
| Uzun OK | Ders sayfasında video modu, diğer sayfalarda yazı boyutu | Altyazı: Otomatik → Kapalı → Her zaman açık |
| Menü | Yazı boyutu | Alt menüyü aç / kapat |
| Geri | Geri / çıkış | Tam ekrandan / video modundan çık |

### Gizlilik ve veri güvenliği
- Udemy oturumunuz (çerezler) yalnızca TV'deki Android WebView deposunda, uygulamanın kendi özel klasöründe tutulur. Uygulama bu bilgileri okumaz, kopyalamaz, yedeklenmesine izin vermez (`allowBackup=false`).
- Geliştiricinin bir sunucusu yoktur; hiçbir kullanıcı veya izleme verisi toplanmaz, analiz/reklam kütüphanesi yoktur.
- Uygulamanın bağlandığı adresler: **udemy.com** (içerik), **Google Çeviri** (yalnızca altyazı açıkken, sadece altyazı metni), **GitHub** (yalnızca güncelleme kontrolü).

---

## English

### Features
- Sign in to Udemy with your own account and watch the courses you own on your TV.
- Remote-friendly: the arrow keys move a cursor; on lecture pages, video mode gives play/pause and 10-second seek.
- Full screen, with ▲ to fit / fill / zoom.
- **VLC-style player menu** (only while watching, opened with OK): playback speed (0.75×–2×), subtitle language and size, picture, previous/next lecture.
- Always the highest video quality.
- **Translated subtitles:** lectures in another language get subtitles translated into the TV's language; lectures already in that language show none. Long OK cycles Auto / Off / Always on.
- Recovers from video errors by reloading the lecture and resuming where it stopped.
- **Automatic updates:** when a new version is published, the app asks once and updates itself.
- English and Turkish user interface (follows the TV language).

### Install
1. Download `EduCastTV.apk` from the [latest release](../../releases/latest).
2. Install it on the TV (USB stick, the "Downloader" app, or `adb install EduCastTV.apk`).
3. Later updates are offered inside the app.

### Remote control
| Key | Cursor mode | Video mode |
|---|---|---|
| Arrows | Move the cursor | ◀ ▶ seek 10 s, ▼ full screen, ▲ cursor (full screen: zoom) |
| OK | Click | **Opens the player menu:** previous/next lecture, ±10 s, play/pause, speed, subtitles (mode, text size, translation language), picture, full screen. In the menu ◀ ▶ move, OK selects, ▲ / Back closes |
| Long OK | Video mode on lecture pages, text size elsewhere | Subtitles: Auto → Off → Always on |
| Menu | Text size | Open / close the player menu |
| Back | Back / exit | Leave full screen / video mode |

### Privacy and data security
- Your Udemy session (cookies) is stored only in the Android WebView storage inside the app's private folder on the TV. The app does not read, copy or back up this data (`allowBackup=false`).
- There is no developer server: no user or viewing data is collected, and there are no analytics or ad libraries.
- Network endpoints used: **udemy.com** (content), **Google Translate** (only while subtitles are on, caption text only), **GitHub** (update checks only).

---

## Development / Geliştirme

See [CONTRIBUTING.md](CONTRIBUTING.md). Katkıda bulunmak için [CONTRIBUTING.md](CONTRIBUTING.md) dosyasına bakın.

## License / Lisans

[GPL-3.0](LICENSE)
