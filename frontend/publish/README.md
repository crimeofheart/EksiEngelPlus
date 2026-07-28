# Eklentiyi yayınlamadan önce gerekenler
- kodların yer aldığı zip dosyası (artık elle hazırlanmıyor, aşağıya bak)
- promo: market sayfasında kullanılacak 3 farklı büyüklükte tanıtım resmi, promotion images (/promo)
- uygulama için görüntüler ve videolar (/ss)

## Sürüm çıkarma

Versiyon numarası 6 ayrı yerde tutuluyor; elle değiştirme, script hepsini
birden günceller ve tutarsızlık varsa çalışmayı reddeder.

```bash
# PR master'a merge edildikten sonra
git checkout master && git pull
cd frontend/app
npm run release -- patch          # veya minor / major
git push origin master --follow-tags
```

Tag push edilince `.github/workflows/extension-release.yml` her iki zip'i
üretip GitHub Release'e ekler. Bu dosyaları indirip Chrome Web Store ve
addons.mozilla.org'a yükle.

Zip'leri yayınlamadan yerelde denemek için:

```bash
cd frontend/app && npm run package     # -> frontend/publish/dist/*.zip
```

## Ayrıca
- Github Pages sayfasındaki ss'leri güncellemeyi unutma. ./ss klasörünü ../docs/ss/ içine taşı ve gerekiyorsa ss.html'i düzenle.
- changelog.txt'e son halini ver.
- Gerekiyorsa markettext.txt'i düzenle.
- welcome.html sayfasını güncelle.
