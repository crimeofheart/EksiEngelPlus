// User facing release notes shown on the welcome page after an install or update.
// Keys must match the version in the manifest exactly. Add an entry here whenever
// `npm run version:patch|minor|major` bumps the version, otherwise the welcome
// page falls back to a generic line.
export const releaseNotes = {
  "0.1.6": [
    "Bu sayfa artık yüklü sürümü ve o sürüme ait notları otomatik gösteriyor."
  ],
  "0.1.5": [
    "Kullanım istatistiklerine sürüm ve tarayıcı bilgisi eklendi."
  ],
  "0.1.4": [
    "Firefox paketi küçültüldü, eklenti Firefox mağazasında sorunsuz yayınlanıyor."
  ],
  "0.1.3": [
    "Sürüm paketleme ve yayınlama süreci otomatikleştirildi."
  ],
  "0.1.2": [
    "Liste (LIST) işlemlerindeki yazar listesi hatası giderildi."
  ],
  "0.1.0": [
    "Yeni isim: EksiEngelPlus. Ekşi Engel'den türetilmiş yeni sürüm.",
    "Firefox desteği eklendi."
  ]
};

export const fallbackNote = "Bu sürüm için ayrıntılı not girilmemiş.";

export function getNotes(version) {
  const notes = releaseNotes[version];
  return notes && notes.length ? notes : [fallbackNote];
}
