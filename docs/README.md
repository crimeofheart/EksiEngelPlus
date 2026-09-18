# EksiEngelPlus /docs

Bu klasör artık bir siteyi yayınlamıyor: bu repoda GitHub Pages kapalı. Yayındaki
site (https://eksiengelplus.duzgun.org/) bu repodaki Django backend'i; sürüm
numarasını ve son sürüm notlarını `api/release_info.py` üzerinden buradaki
`changelog.json`'dan okuyor.

Buradaki HTML sayfaları (index, releaseNotes, privacypolicy, ss, markettext)
yerelde açılmak ve mağaza metinlerini bir arada tutmak için duruyor.

| Dosya | Ne |
| --- | --- |
| `changelog.json` | **üretilen** — `frontend/app/assets/js/changelog.js`'ten `npm run changelog` ile |
| `changelog.legacy.json` | yeniden adlandırmadan önceki sürümler (1.0.0–3.2.0), olduğu gibi |
| `changelog.txt` | arşiv: v3.3'e kadar geliştirici günlüğü + açık işler listesi. Buraya sürüm eklenmiyor |
