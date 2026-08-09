package org.duzgun.eksiengelplus.feature.settings

/**
 * What changed, per version, shown once after an install or an upgrade.
 *
 * Ported from frontend/app/assets/js/changelog.js, including its fallback: a
 * release must never be blocked on someone remembering to write a note, and a
 * version with no entry must never render as a blank screen.
 *
 * The notes are the app's, not the extension's. Both ship on one version number
 * -- see the seven places CLAUDE.md lists -- but a release whose whole content
 * was a Firefox packaging fix has nothing to tell an Android user. Versions with
 * no Android-visible change are simply absent, and `ReleaseNotesTest` asserts
 * the *current* version is not one of them, so the fallback stays a safety net
 * rather than the normal path.
 *
 * Turkish, because every other user-facing string in this app is.
 */
object ReleaseNotes {

    const val FALLBACK = "Bu sürüm için ayrıntılı not girilmemiş."

    private val notes: Map<String, List<String>> = mapOf(
        "0.1.8" to listOf(
            "Bir başlıktaki yazarlar taranırken son sayfadan sonrası hata sayılıyor, işlem kimseye dokunmadan yarıda kesiliyordu. Artık son sayfada düzgün duruyor.",
        ),
        "0.1.7" to listOf(
            "Tarih filtresi artık varsayılan olarak açık: on yıldan eski hesaplara dokunulmuyor.",
            "Kayıt tarihi bilinmeyen yazarlar için tarih, işlem sırasında tek tek çözülüyor.",
            "CSV içe aktarma raporu yapıştırılan her satırı sayıyor, tekrar eden nickler ayrıca belirtiliyor.",
            "Kayıt tarihi önbelleği süresi dolan kayıtları artık gerçekten siliyor; ayarlardan boyutu görülüp temizlenebiliyor.",
            "Ayarlara kullanım kılavuzu eklendi.",
        ),
    )

    /**
     * The notes for [version], or a single fallback line.
     *
     * Never empty. A caller that got an empty list would have to decide what to
     * render, and every caller would decide it separately.
     */
    fun forVersion(version: String): List<String> =
        notes[version]?.takeIf { it.isNotEmpty() } ?: listOf(FALLBACK)

    /** Whether [version] has notes of its own. Used by the drift test. */
    fun has(version: String): Boolean = notes[version]?.isNotEmpty() == true
}
