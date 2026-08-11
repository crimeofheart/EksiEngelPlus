// User facing release notes shown on the welcome page after an install or update.
// Keys must match the version in the manifest exactly. Add an entry here whenever
// `npm run version:patch|minor|major` bumps the version, otherwise the welcome
// page falls back to a generic line.
//
// One version number covers the extension and the Android app, so a note has to
// say which of the two it is about. It used to say so in prose -- "bu sürümde
// eklentide bir değişiklik yok, Android uygulamasında ..." -- which meant the
// reader had to parse the sentence to find out whether the release concerned
// them at all, and left no way to state "nothing changed here" except by
// remembering to write it.
//
// So each version is keyed by platform instead:
//
//   []        an explicit "no changes in this one this time"
//   omitted   the platform did not exist yet; nothing is claimed about it
//
// Both surfaces show both sections. A user on one platform still wants to know
// the other got the fix -- that is the same release note, and hiding it would
// make the two clients look like they had diverged.
export const releaseNotes = {
  "0.2.0": {
    date: "2026-08-11",
    app: [
      "Bir yazarın profilinde artık engeli kaldırılabiliyor: zaten engellediğiniz birinde düğme \"engellemeyi bırak\" oluyor. Başlık engeli de ayrı olarak kaldırılabiliyor.",
      "Tarih bazlı toplu işlem yeniden yazıldı: liste (engelliler, sessizler, yazar listem), ölçüt (şu kadar süreden yeni/eski, şu tarihten önce/sonra) ve işlem (engelle, sessize al, engeli kaldır, sessizden çıkar, takip et, takipten çıkar ve iki birleşik işlem) ayrı ayrı seçiliyor.",
      "Aynı ekranda \"sessiz kullanıcılar\" seçilse bile işlem engelli listesi üzerinde çalışıyordu; artık seçilen liste üzerinde çalışıyor.",
      "Ayarlardaki tarih filtresi artık yalnızca engelleme, sessize alma ve başlık engellemede çalışıyor. Daha önce her işlemi kapsıyordu: varsayılan on yıl kuralı yüzünden \"tüm engelleri kaldır\" on yıldan eski hesapları atlıyor, o hesaplar engelli kalıyordu.",
      "Tarih bazlı işlemde seçilen ölçüt yalnızca o işlem için geçerli; ayarlardaki tarih filtresi kurallarına dokunmuyor.",
      "Son seçimleriniz hatırlanıyor."
    ],
    extension: []
  },
  "0.1.9": {
    date: "2026-08-10",
    app: [
      "Ana sayfalarda aşağı çekerek yenileme: tarayıcıda sayfayı, listelerde tüm listeleri, işlem durumunda yarım kalmış işlemleri tazeler.",
      "Bir başlığın içinde yatay kaydırma artık sayfalar arasında geziniyor; son sayfadan sonrası bugün, gündem, debe döngüsüne bağlanıyor.",
      "Yatay kaydırma yarıda kesildiğinde sayfanın ekranın ortasında asılı kalması giderildi."
    ],
    extension: []
  },
  "0.1.8": {
    date: "2026-08-10",
    app: [
      "Bir başlıktaki yazarlar taranırken son sayfadan sonrası hata sayılıyor, işlem kimseye dokunmadan yarıda kesiliyordu. Artık son sayfada düzgün duruyor."
    ],
    extension: []
  },
  "0.1.7": {
    date: "2026-08-10",
    app: [
      "Android uygulaması yayında: eklentideki işlemlerin tamamı, listeler ve CSV aktarımı ile birlikte.",
      "Tarih filtresi artık varsayılan olarak açık: on yıldan eski hesaplara dokunulmuyor.",
      "Kayıt tarihi bilinmeyen yazarlar için tarih, işlem sırasında tek tek çözülüyor.",
      "CSV içe aktarma raporu yapıştırılan her satırı sayıyor, tekrar eden nickler ayrıca belirtiliyor.",
      "Kayıt tarihi önbelleği süresi dolan kayıtları artık gerçekten siliyor; ayarlardan boyutu görülüp temizlenebiliyor.",
      "Ayarlara kullanım kılavuzu eklendi."
    ],
    extension: []
  },
  // Below here the Android app did not exist, so its section is omitted rather
  // than empty: there was no app for these releases to have changed.
  "0.1.6": {
    date: "2026-07-31",
    extension: [
      "Bu sayfa artık yüklü sürümü ve o sürüme ait notları otomatik gösteriyor."
    ]
  },
  "0.1.5": {
    date: "2026-07-31",
    extension: [
      "Kullanım istatistiklerine sürüm ve tarayıcı bilgisi eklendi."
    ]
  },
  "0.1.4": {
    date: "2026-07-29",
    extension: [
      "Firefox paketi küçültüldü, eklenti Firefox mağazasında sorunsuz yayınlanıyor."
    ]
  },
  "0.1.3": {
    date: "2026-07-28",
    extension: [
      "Sürüm paketleme ve yayınlama süreci otomatikleştirildi."
    ]
  },
  "0.1.2": {
    date: "2026-07-28",
    extension: [
      "Liste (LIST) işlemlerindeki yazar listesi hatası giderildi."
    ]
  },
  "0.1.0": {
    // From docs/changelog.txt; this one predates the v* tags in this repo.
    date: "2025-11-29",
    extension: [
      "Yeni isim: EksiEngelPlus. Ekşi Engel'den türetilmiş yeni sürüm.",
      "Firefox desteği eklendi."
    ]
  }
};

export const fallbackNote = "Bu sürüm için ayrıntılı not girilmemiş.";

/** What an empty platform list renders as. */
export const noChangesNote = "Bu sürümde değişiklik yok.";

export const platformLabels = {
  extension: "Eklenti",
  app: "Android uygulaması"
};

/**
 * The version's notes, grouped by platform.
 *
 * [order] puts the reader's own platform first -- the extension's welcome page
 * leads with the extension, the app's screen leads with the app -- because the
 * first question either one is asked is "what changed for me".
 *
 * Never empty, and no section is ever empty either: an unknown version yields a
 * single unlabelled fallback section, and a platform with nothing to report
 * yields one line saying exactly that. A caller that had to handle emptiness
 * would have to decide what to render, and every caller would decide separately.
 */
export function getSections(version, order = ["extension", "app"]) {
  const entry = releaseNotes[version];
  const sections = [];

  if (entry) {
    for (const platform of order) {
      const notes = entry[platform];
      // Omitted, not empty: nothing is being claimed about this platform.
      if (!notes) continue;
      sections.push({
        platform,
        label: platformLabels[platform],
        notes: notes.length ? notes : [noChangesNote]
      });
    }
  }

  if (sections.length) return sections;
  return [{ platform: null, label: "", notes: [fallbackNote] }];
}
