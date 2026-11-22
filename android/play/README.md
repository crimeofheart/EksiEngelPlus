# Google Play submission kit

Everything the Play Console asks for on the first submission, ready to paste. The app is
`org.duzgun.eksiengelplus`; upload `frontend/publish/dist/eksiengelplus-<version>.aab` (the `play`
flavor, built by `tools/build-release.sh`). Keep this file in step with the privacy page
(`backend/django_EksiEngel/api/templates/privacy/index.html`) and with the Data Safety
consequences in `openspec/specs/android-persistence/spec.md`.

## Order of work

1. **Create the app** — default language Turkish (tr-TR), app (not game), free.
2. **Closed testing first.** A personal developer account created after 13 Nov 2023 needs a
   closed test with at least 12 opted-in testers for 14 consecutive days before it can apply
   for production. Upload the AAB to a closed track now so the 14 days start; the first upload
   enrolls the app in Play App Signing (the upload certificate is the pinned `UPLOAD_CERT`).
3. Fill **App content** (below), the **store listing**, then apply for production.
4. When Play serves the version, raise the `play` channel minimum only then:
   `tools/set-min-version.sh --min-play <version>`.

## Store listing

| Field | Value |
| --- | --- |
| App name | `EksiEngelPlus` |
| Category | Social |
| Contact email | `eksiengelplus@gmail.com` |
| Website | `https://eksiengelplus.duzgun.org` |
| Privacy policy | `https://eksiengelplus.duzgun.org/privacy/` |
| App icon | `icon-512.png` (512×512, no transparency; Play applies the rounded mask) |
| Feature graphic | `feature-graphic.png` (1024×500) |
| Phone screenshots | at least 2, 16:9 or 9:16 — **not made yet**, see below |

### Short description (max 80)

tr-TR (78):

```
Ekşi Sözlük için toplu engelleme, sessize alma ve takip. Resmi uygulama değil.
```

en-US (77):

```
Bulk block, mute and follow for Ekşi Sözlük. Not an official Ekşi Sözlük app.
```

### Full description (max 4000)

tr-TR:

```
EksiEngelPlus, Ekşi Sözlük'ü uygulamanın içinde gezerken toplu engelleme, sessize alma ve takip işlemleri yapmanızı sağlayan açık kaynaklı bir uygulamadır.

EksiEngelPlus, Ekşi Sözlük'ün resmi uygulaması değildir ve Ekşi Sözlük ile bir bağlantısı yoktur. İşlemler, uygulamada açtığınız kendi Ekşi Sözlük oturumunuzla yapılır.

TOPLU İŞLEMLER
• Bir entry'yi favorileyenleri engelleme, sessize alma ya da takip etme
• Bir yazarı takip edenleri ve takip ettiklerini engelleme, sessize alma ya da takip etme
• Bir başlıktaki tüm yazarları engelleme ya da sessize alma
• Liste halinde verilen kullanıcıları toplu engelleme, sessize alma ya da engelini kaldırma
• Tüm engelleri ya da tüm sessizleri toplu kaldırma, engellileri sessize taşıma
• Yazar listelerini CSV olarak kaydetme ve kaydedilen bir listeyle işlem yapma

ARKA PLANDA ÇALIŞIR
Toplu işlemler uygulamayı kapatsanız da sürer. Duraklatabilir, devam ettirebilir ya da istediğiniz an durdurabilirsiniz. Uygulama, sözlüğün istek sınırının altında kalacak şekilde kendini yavaşlatır; yarıda kalan bir işlem kaldığı yerden devam eder. Birden fazla işlem sırayla çalışır.

TARİH BAZLI FİLTRE
Hesap kayıt tarihine göre kural koyarak belirli yaştaki hesapları işlemlerin dışında tutabilirsiniz. Varsayılan kural on beş yıldan eski hesaplara dokunmaz. Takip işlemlerinde kural uygulanmaz.

SÖZLÜK UYGULAMANIN İÇİNDE
Entry'nin yanındaki menüden engelleyip okumaya devam edersiniz; ayrı bir ekrana geçmeniz gerekmez. Başlıklar arası yatay kaydırma, aşağı çekip yenileme ve bir listeye geri döndüğünüzde kaldığınız yerden devam etme de var.

CİHAZLAR ARASI EŞİTLEME
İşlemleriniz Chrome ve Firefox eklentisiyle ve diğer cihazlarınızla eşitlenir: bir cihazda başlattığınız işlemi diğerinde görüp iptal edebilirsiniz. Eşitlemeyi ayarlardan kapatabilir, sunucudaki verilerinizi silebilirsiniz.

GİZLİLİK
Ekşi Sözlük oturumunuz hiçbir zaman sunucumuza gönderilmez. Hangi verilerin gönderildiği ve nasıl silineceği gizlilik politikasında yazıyor: https://eksiengelplus.duzgun.org/privacy/

Açık kaynak: https://github.com/crimeofheart/EksiEngelPlus
İletişim: eksiengelplus@gmail.com
```

en-US:

```
EksiEngelPlus is an open-source app for blocking, muting and following people on Ekşi Sözlük in bulk, while you browse the site inside the app.

EksiEngelPlus is not an official Ekşi Sözlük app and is not affiliated with Ekşi Sözlük. Every action is carried out with your own Ekşi Sözlük session, which you sign in to inside the app.

BULK OPERATIONS
• Block, mute or follow everyone who favorited an entry
• Block, mute or follow an author's followers or the people they follow
• Block or mute every author in a title
• Block, mute or unblock a list of users you provide
• Remove every block or every mute at once, or move blocked users to muted
• Save author lists as CSV and run an operation from a saved list

RUNS IN THE BACKGROUND
Bulk operations keep going after you close the app. You can pause, resume or stop them at any time. The app slows itself down to stay under the site's request limit, and an interrupted operation continues where it stopped. Several operations run one after another.

DATE FILTER
Rules based on account registration date keep accounts of a certain age out of operations. The default rule leaves accounts older than fifteen years alone. Follow operations ignore the rule.

THE SITE INSIDE THE APP
Block someone from the menu next to an entry and keep reading; there is no separate screen. Swipe between titles, pull to refresh, and lists reopen where you left them.

SYNC ACROSS DEVICES
Your operations sync with the Chrome and Firefox extension and your other devices: start an operation on one and see or cancel it on another. Sync can be turned off in the settings, and your data on the server can be deleted there.

PRIVACY
Your Ekşi Sözlük session is never sent to our server. The privacy policy lists what is sent and how to delete it: https://eksiengelplus.duzgun.org/privacy/

Open source: https://github.com/crimeofheart/EksiEngelPlus
Contact: eksiengelplus@gmail.com
```

### Screenshots (still to do)

Take them on a phone with a **test Ekşi account**, not a personal one — every list screen shows
other people's nicknames. Useful screens: the entry menu with "engelle", "işlem durumu" with a
running operation, "toplu işlemler", settings with the date rules. `adb exec-out screencap -p
> shot.png` gives a full-resolution PNG.

## App content

### App access

All functionality is restricted. Paste, after creating a throwaway Ekşi account:

```
The app is a client for eksisozluk.com and needs an Ekşi Sözlük account; it creates none itself.
1. Open the app and tap "giriş" on the Ekşi Sözlük page it shows.
2. Sign in with: username <TEST_ACCOUNT>  password <TEST_PASSWORD>
3. Open the menu (…) next to any entry and choose "engelle" to block its author, or open
   "toplu işlemler" to start a bulk operation such as "engelli kullanıcıları sessize al";
   "işlem durumu" shows its progress.
```

### Foreground service declaration

Type: **Data sync** (`FOREGROUND_SERVICE_DATA_SYNC`, WorkManager `OperationWorker`).

```
EksiEngelPlus runs bulk block, mute and follow operations that the user starts explicitly. Each operation sends hundreds to thousands of rate-limited requests to eksisozluk.com with the user's own session and checkpoints its progress to a local database after every request, so it can take from minutes to hours. The operation runs as a data sync foreground service so that it continues when the user leaves the app, and a progress notification is shown for its whole duration. The service stops as soon as the operation and any operations the user queued behind it finish, are paused, or are stopped. It respects the Android 15 data sync time limit by tracking its own foreground time and pausing until the next day when the limit is reached.
```

Video: screen recording of starting an operation in the app, leaving the app, and the progress
notification staying up while the count advances (`adb shell screenrecord /sdcard/fgs.mp4`),
uploaded unlisted to YouTube.

### Data safety

Source of truth: the privacy page's "Sunucumuza gönderilenler" section and
`openspec/specs/android-persistence/spec.md` (Telemetry defaults, Consequences).

| Question | Answer |
| --- | --- |
| Collects or shares required data types | Yes |
| Encrypted in transit | Yes (HTTPS) |
| Users can request deletion | Yes — in-app "Sunucudaki verilerimi sil"; URL `https://eksiengelplus.duzgun.org/privacy/#silme` |
| Account creation | No — users sign in with their existing Ekşi Sözlük account; the server keys sync to it with an anonymous device key, there is no sign-up |
| Data shared with third parties | None |

Collected (none shared, none processed ephemerally):

| Category → type | What it is | Required? | Purposes |
| --- | --- | --- | --- |
| Personal info → User IDs | the user's Ekşi username and id; usernames and ids of the authors each operation targeted (people who are not users of the app) | **Required** (spec decision: third parties cannot opt out) | App functionality, Analytics |
| App activity → Other actions | kind, result and time of every block, mute, title-block and follow; queued operations and status; counts of which buttons are used | Optional (sync and data sending can be turned off) | App functionality, Analytics |
| Device or other IDs | random per-device key (`client_uid`; the server stores only a hash) | Optional | App functionality |
| App info and performance → Diagnostics | debug log in the run report while log sending is on | Optional | Analytics |

Retention: synced history until the user deletes it; run reports until the user asks for
deletion.

### Other declarations

| Section | Answer |
| --- | --- |
| Ads | No ads |
| Target audience | 18 and over only (keeps the app out of the Families policy; the site shows adult content) |
| Content rating (IARC) | Category: Social / communication. User interaction: yes (users can post on the site through the app). Shares location: no. Unrestricted internet access: yes. Digital purchases: no. |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | Not a health app |
| COVID-19 contact tracing | No |

## Review risks

- **WebView of a site we do not own.** Play's spam policy rejects apps whose main purpose is to
  show someone else's website in a WebView without permission. The listing leads with the app's
  own work (bulk operations, background runs, date filter, sync); keep it that way in the
  review notes too.
- **Name and brand.** "Ekşi" in the name can trigger the impersonation and IP policy. The short
  and full descriptions both say the app is not official and not affiliated; the graphics use
  only the app's own drop-and-sword art, never Ekşi Sözlük's logo.
- **Automation on a third-party service.** Bulk actions on eksisozluk.com may be read against the
  device and network abuse policy. The app paces itself under the site's request limit and acts
  only with the user's own session — say so if review asks.

## Regenerating the graphics

Both come from `android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png` over the
launcher's white background, in the extension header's green (`#6CA64E` → `#8DB872`):

```bash
fg=android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png
magick -size 512x512 xc:white \( "$fg" -gravity center -crop 360x360+0+0 +repage -filter Lanczos -resize 512x512 \) \
  -gravity center -composite -alpha off -strip PNG24:android/play/icon-512.png
font=$(fc-match -f '%{file}' 'Open Sans:style=ExtraBold'); font2=$(fc-match -f '%{file}' 'Open Sans:style=SemiBold')
magick -size 1024x500 gradient:'#6CA64E'-'#8DB872' -rotate 90 -resize 1024x500\! \
  \( -size 300x300 xc:none -fill white -draw 'roundrectangle 0,0 299,299 64,64' \) -geometry +90+100 -composite \
  \( "$fg" -trim +repage -resize 236x236 \) -geometry +122+130 -composite \
  -fill white -font "$font" -pointsize 76 -annotate +440+225 'EksiEngelPlus' \
  -font "$font2" -pointsize 34 -annotate +444+290 'Ekşi Sözlük için toplu engelleme,' -annotate +444+338 'sessize alma ve takip' \
  -alpha off -strip PNG24:android/play/feature-graphic.png
```
