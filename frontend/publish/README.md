# Eklentiyi yayınlamadan önce gerekenler
- kodların yer aldığı zip dosyası (artık elle hazırlanmıyor, aşağıya bak)
- promo: market sayfasında kullanılacak 3 farklı büyüklükte tanıtım resmi, promotion images (/promo)
- uygulama için görüntüler ve videolar (/ss)

## Sürüm çıkarma

Versiyon numarası 7 ayrı yerde tutuluyor (package.json, package-lock.json ×2, iki
manifest varyantı, üretilen manifest.json ve android/version.json); elle değiştirme,
script hepsini birden günceller ve tutarsızlık varsa çalışmayı reddeder.

```bash
# PR master'a merge edildikten sonra
git checkout master && git pull
cd frontend/app
npm run release -- patch          # veya minor / major
git push origin master --follow-tags
```

Tag push edilince `.github/workflows/release.yml` tek bir GitHub Release'e dört dosya
koyuyor: iki tarayıcı zip'i, AAB ve APK. Zip'leri indirip Chrome Web Store ve
addons.mozilla.org'a yükle — aynı Firefox zip'i hem masaüstünü hem Firefox for
Android'i karşılıyor. Play'e gönderim ayrı ve isteğe bağlı: `release.yml`'ı
`workflow_dispatch` ile `submit_to_play` işaretleyerek çalıştır.

Zip'leri yayınlamadan yerelde denemek için:

```bash
cd frontend/app && npm run package     # -> frontend/publish/dist/*.zip
```

## Ayrıca
- Sürüm notlarını `frontend/app/assets/js/changelog.js` içine yaz; tek kaynak orası.
  Ardından `npm run changelog` ile `docs/changelog.json`'ı üret (`npm run check` bayat
  kalmışsa zaten hata veriyor). `docs/changelog.txt` arşiv, oraya sürüm eklenmiyor.
- Android tarafında `ReleaseNotes.kt`'yi aynı metinle güncelle; `ReleaseNotesTest`
  yayınlanan sürüm için kelimesi kelimesine eşleşme arıyor.
- welcome.html'i elle güncellemeye gerek yok: changelog.js'i kendisi okuyor.
- Site (eksiengelplus.duzgun.org) bu repodaki Django backend'i; sürüm ve notlar
  `api/release_info.py` üzerinden türetiliyor, şablon düzenlemek gerekmiyor. Host'ta
  `git pull` + `systemctl restart gunicorn-eksiengel` yeterli.
- Gerekiyorsa markettext.txt'i düzenle.
- Mağaza görselleri değiştiyse ./ss ve ./promo klasörlerini tazele.
