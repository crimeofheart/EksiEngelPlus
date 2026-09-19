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
  "0.5.1": {
    date: "2026-09-19",
    app: [
      "Tarih filtresinin varsayılan kuralı on yıldan on beş yıla çıktı. Daha önceden kurulu sürümlerde de kural kendiliğinden güncelleniyor; kuralın değerini kendiniz değiştirdiyseniz sizin girdiğiniz değere dokunulmuyor.",
      "İşlem durumundaki \"sıradakiler\" listesinde artık \"tekrarla\" düğmesi yok: henüz çalışmamış bir işlemin tekrarlanacak bir sonucu da yok. O satırlarda \"git\" ve \"kaldır\" var, ikisi de aynı düğme biçiminde. \"Tekrarla\" yalnızca tamamlananlarda çıkıyor.",
      "Yükseltmeden sonra açılan sürüm notları artık yalnızca en son sürümü değil, kullandığınız sürümden bu yana çıkan bütün sürümleri gösteriyor."
    ],
    extension: [
      "Tarih filtresinin varsayılan kuralı on yıldan on beş yıla çıktı. Daha önceden kurulu sürümlerde de kural kendiliğinden güncelleniyor; kuralın değerini kendiniz değiştirdiyseniz sizin girdiğiniz değere dokunulmuyor.",
      "Ayarlarda yıl ya da ay olarak girilen kural değeri listede yanlış görünüyordu: on beş yıllık bir kural \"5475 yıl\" diye yazıyordu. Artık girildiği birimde görünüyor, kuralı açıp kaydetmek de değeri büyütmüyor.",
      "Tarih filtresi artık gerçekten koruyor: kuralın kapsamadığı hesaplara dokunulmuyor ve işlem sonunda kaç hesabın korunduğu yazıyor. Eskiden kural ne olursa olsun listedeki herkes işleme giriyordu, yani varsayılan kural kimseyi korumuyordu.",
      "İşlem durumundaki \"sıradakiler\" listesinde \"tekrarla\" yerine \"kaldır\" var: henüz çalışmamış bir işlemin tekrarlanacak bir sonucu yok, ama sıradan çıkarılabiliyor. O satırlarda \"git\" ve \"kaldır\", tamamlananlarda \"tekrarla\" ve \"git\" çıkıyor.",
      "Eklenti güncellendikten sonra açık kalan Ekşi Sözlük sekmelerinde düğmeler sessizce çalışmıyor, konsola da hata düşüyordu. Artık sayfanın kendi bildirim alanında \"sayfayı yenileyin\" uyarısı çıkıyor.",
      "Eklenti güncellendiğinde ayarlarınız korunuyor; eskiden her güncelleme hepsini sıfırlıyordu. Bu sürüme geçerken son bir kez sıfırlanır, sonraki güncellemelerde durur.",
      "Yükseltmeden sonra açılan sürüm notları artık yalnızca en son sürümü değil, kullandığınız sürümden bu yana çıkan bütün sürümleri gösteriyor."
    ]
  },
  "0.5.0": {
    date: "2026-09-18",
    app: [],
    extension: [
      "Eklenti artık Android'deki Firefox'a da kurulabiliyor. Telefonda da masaüstündeki bütün işlemler yapılabiliyor.",
      "Eklentinin kendi sayfaları — işlem durumu, ayarlar ve yardım, yazar listesi — telefon ekranına sığacak şekilde açılıyor. Tablolar sayfayı yana taşırmıyor, \"tekrarla\" ve \"git\" düğmeleri parmakla basılabilecek boyutta.",
      "Yardım balonları dokununca da açılıyor. Eskiden yalnızca fareyle üzerine gelince göründükleri için dokunmatik ekranda hiç açılmıyorlardı.",
      "Ekşi Sözlük hesabınıza giriş yapmadan bir EksiEngelPlus düğmesine bastığınızda artık sayfanın kendi bildirim alanında uyarı çıkıyor. Eskiden işlem sessizce kuyruğa giriyor ve saniyeler sonra başka bir sayfada hata olarak beliriyordu. Giriş yapılmamışken düğmeler soluk görünüyor.",
      "Ayarlar ve yardım sayfasındaki açıklamalar telefonda yarıda kesiliyordu; artık satır sonunda alta iniyorlar."
    ]
  },
  "0.4.0": {
    date: "2026-09-17",
    app: [
      "Bir listede aşağı inip bir entry'ye, bir yazara ya da bir (bkz:)'e girdikten sonra geri döndüğünüzde liste kaldığınız yerde açılıyor. Gündem, bir başlığın sayfaları, bir yazarın entry'leri, takipçi ve takip edilen listeleri; kaydırılan her sayfada çalışıyor.",
      "Engelleme ve sessize almanın olduğu her yerde takip etme de var: bir entry'nin yazarı, favlayanları, takipçileri ve takip ettikleri; hem entry menüsünde hem profilde.",
      "Takip etmek, engel ya da sessize alma varsa önce onu kaldırıyor. Eskiden takip başarılı görünüyor, hesap gizli kalmaya devam ediyordu.",
      "İşlem durumundaki satırlarda \"tekrarla\" ve \"git\" var: yarım kalan ya da biten bir işlem yeniden kuyruğa alınabiliyor, tek bir sayfaya dokunan işlemlerde o sayfa açılabiliyor."
    ],
    extension: [
      "Engelleme ve sessize almanın olduğu her yerde takip etme de var: bir entry'nin yazarı, favlayanları, takipçileri ve takip ettikleri; hem entry menüsünde hem profilde.",
      "Takip etmek, engel ya da sessize alma varsa önce onu kaldırıyor. Eskiden takip başarılı görünüyor, hesap gizli kalmaya devam ediyordu.",
      "İşlem durumunda yarım kalan satırlar \"tekrarla\" ve \"git\" ile yeniden denenebiliyor.",
      "Sessize alma açıkken \"favlayanları engelle\" ve \"başlıktakileri engelle\" düğmeleri hâlâ \"engelle\" yazıyordu. İşlem doğruydu, yazan yanlıştı; artık \"sessize al\" diyorlar."
    ]
  },
  "0.3.0": {
    date: "2026-08-16",
    app: [
      "Bir başlığa uzun basıldığında üç seçenekli bir menü açılıyor: başlığı kopyala, bağlantıyı kopyala ve paylaş. Başlığın nerede göründüğü fark etmiyor: gündemde, arama sonuçlarında, bir yazarın entry'lerinde ve başlığın kendi sayfasında çalışıyor.",
      "Menü ekranın ortasında açılıyor, dışına dokunmak kapatıyor ve seçenekler basıldıkları anda çalışıyor."
    ],
    extension: []
  },
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
      "Karşılama sayfası artık yüklü sürümü ve o sürüme ait notları otomatik gösteriyor."
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
      "Yeni sürümler artık otomatik paketlenip yayınlanıyor; güncellemeler daha hızlı geliyor."
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
export function compareVersions(a, b) {
  const pa = String(a).split(".").map(Number);
  const pb = String(b).split(".").map(Number);
  
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const diff = (pa[i] || 0) - (pb[i] || 0);
    if (diff !== 0) return diff < 0 ? -1 : 1;
  }
  return 0;
}

const VERSION_PATTERN = /^\d+(\.\d+)*$/;

/**
 * Whether a value is a version this file can reason about.
 *
 * Anything else is "no usable previous version". Worth exporting rather than
 * leaving private: compareVersions reads an unparseable segment as zero, so
 * "abc" compares equal to 0.0.0 and would silently look like the oldest
 * release there has ever been.
 */
export function isVersion(value) {
  return typeof value === "string" && VERSION_PATTERN.test(value);
}

/**
 * Every version newer than [previousVersion], newest first.
 *
 * Someone upgrading from an old store build has missed every release in
 * between, and showing them only the newest one hides the rest for good --
 * the welcome page is the only place these notes are ever shown.
 *
 * Only the modern numbering lives in this file, which is what makes comparing
 * these keys safe: docs/changelog.legacy.json holds 1.0.0-3.2.0 from before the
 * rename, and numbering restarted at 0.1.0 afterwards, so 3.2.0 is *older* than
 * 0.1.2 while every version comparison says the opposite. Never merge the two
 * lists and then sort them.
 *
 * A missing or unparseable previousVersion yields everything, which is what a
 * fresh install should see.
 */
export function getVersionsSince(previousVersion) {
  const all = Object.keys(releaseNotes).sort((a, b) => compareVersions(b, a));
  
  if (!isVersion(previousVersion)) return all;
  return all.filter((version) => compareVersions(version, previousVersion) > 0);
}

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
