package org.duzgun.eksiengelplus.feature.settings

/**
 * What changed, per version, shown once after an install or an upgrade.
 *
 * Mirrors frontend/app/assets/js/changelog.js, including its fallback: a release
 * must never be blocked on someone remembering to write a note, and a version
 * with no entry must never render as a blank screen. `ReleaseNotesTest` asserts
 * the two files agree on the version being shipped, so the copy cannot quietly
 * drift from the original.
 *
 * One version number covers both artifacts, so a note has to say which of the
 * two it is about. That used to be settled by leaving the version out of this
 * file entirely when nothing Android-visible had changed -- which meant the app
 * showed the generic fallback for a release that did have something to say, just
 * not about the app. Now every shipped version is here, split by platform:
 *
 *   emptyList()   an explicit "no changes in this one this time"
 *   null          the platform did not exist yet; nothing is claimed about it
 *
 * Both sections are shown. A user on one platform still wants to know the other
 * got the fix -- it is the same release -- and hiding it would make the two
 * clients look like they had diverged.
 *
 * Turkish, because every other user-facing string in this app is.
 */
object ReleaseNotes {

    const val FALLBACK = "Bu sürüm için ayrıntılı not girilmemiş."

    /** What an empty platform list renders as. */
    const val NO_CHANGES = "Bu sürümde değişiklik yok."

    enum class Platform(val label: String) {
        APP("Android uygulaması"),
        EXTENSION("Eklenti"),
    }

    /** One platform's notes for one version. [notes] is never empty. */
    data class Section(val platform: Platform?, val label: String, val notes: List<String>)

    private data class VersionNotes(
        val app: List<String>? = null,
        val extension: List<String>? = null,
    )

    private val notes: Map<String, VersionNotes> = mapOf(
        "0.1.9" to VersionNotes(
            app = listOf(
                "Ana sayfalarda aşağı çekerek yenileme: tarayıcıda sayfayı, listelerde tüm listeleri, işlem durumunda yarım kalmış işlemleri tazeler.",
                "Bir başlığın içinde yatay kaydırma artık sayfalar arasında geziniyor; son sayfadan sonrası bugün, gündem, debe döngüsüne bağlanıyor.",
                "Yatay kaydırma yarıda kesildiğinde sayfanın ekranın ortasında asılı kalması giderildi.",
            ),
            extension = emptyList(),
        ),
        "0.1.8" to VersionNotes(
            app = listOf(
                "Bir başlıktaki yazarlar taranırken son sayfadan sonrası hata sayılıyor, işlem kimseye dokunmadan yarıda kesiliyordu. Artık son sayfada düzgün duruyor.",
            ),
            extension = emptyList(),
        ),
        "0.1.7" to VersionNotes(
            app = listOf(
                "Android uygulaması yayında: eklentideki işlemlerin tamamı, listeler ve CSV aktarımı ile birlikte.",
                "Tarih filtresi artık varsayılan olarak açık: on yıldan eski hesaplara dokunulmuyor.",
                "Kayıt tarihi bilinmeyen yazarlar için tarih, işlem sırasında tek tek çözülüyor.",
                "CSV içe aktarma raporu yapıştırılan her satırı sayıyor, tekrar eden nickler ayrıca belirtiliyor.",
                "Kayıt tarihi önbelleği süresi dolan kayıtları artık gerçekten siliyor; ayarlardan boyutu görülüp temizlenebiliyor.",
                "Ayarlara kullanım kılavuzu eklendi.",
            ),
            extension = emptyList(),
        ),
        // Below here the Android app did not exist, so its section is null rather
        // than empty: there was no app for these releases to have changed.
        "0.1.6" to VersionNotes(
            extension = listOf(
                "Bu sayfa artık yüklü sürümü ve o sürüme ait notları otomatik gösteriyor.",
            ),
        ),
        "0.1.5" to VersionNotes(
            extension = listOf(
                "Kullanım istatistiklerine sürüm ve tarayıcı bilgisi eklendi.",
            ),
        ),
        "0.1.4" to VersionNotes(
            extension = listOf(
                "Firefox paketi küçültüldü, eklenti Firefox mağazasında sorunsuz yayınlanıyor.",
            ),
        ),
        "0.1.3" to VersionNotes(
            extension = listOf(
                "Sürüm paketleme ve yayınlama süreci otomatikleştirildi.",
            ),
        ),
        "0.1.2" to VersionNotes(
            extension = listOf(
                "Liste (LIST) işlemlerindeki yazar listesi hatası giderildi.",
            ),
        ),
        "0.1.0" to VersionNotes(
            extension = listOf(
                "Yeni isim: EksiEngelPlus. Ekşi Engel'den türetilmiş yeni sürüm.",
                "Firefox desteği eklendi.",
            ),
        ),
    )

    /**
     * The notes for [version], grouped by platform.
     *
     * The app leads, because this is the app: the first question its reader is
     * asking is what changed for them.
     *
     * Never empty, and no section is ever empty either. An unknown version gives
     * one unlabelled fallback section, and a platform with nothing to report
     * gives one line saying exactly that. A caller that had to handle emptiness
     * would have to decide what to render, and every caller would decide it
     * separately.
     */
    fun forVersion(version: String): List<Section> {
        val entry = notes[version]
        val sections = buildList {
            for (platform in ORDER) {
                val lines = entry?.forPlatform(platform) ?: continue
                add(
                    Section(
                        platform = platform,
                        label = platform.label,
                        notes = lines.ifEmpty { listOf(NO_CHANGES) },
                    ),
                )
            }
        }
        return sections.ifEmpty { listOf(Section(null, "", listOf(FALLBACK))) }
    }

    /** Whether [version] has notes of its own. Used by the drift test. */
    fun has(version: String): Boolean = notes.containsKey(version)

    /** What one platform says about [version], or null if it says nothing. */
    fun linesFor(version: String, platform: Platform): List<String>? =
        notes[version]?.forPlatform(platform)

    private fun VersionNotes.forPlatform(platform: Platform): List<String>? = when (platform) {
        Platform.APP -> app
        Platform.EXTENSION -> extension
    }

    private val ORDER = listOf(Platform.APP, Platform.EXTENSION)
}
