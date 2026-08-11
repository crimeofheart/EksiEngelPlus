package org.duzgun.eksiengelplus.model

/**
 * The three choices a date-based bulk run is composed from.
 *
 * Ported from enums.js:102-117. Names are the extension's, verbatim, because
 * ParityTest matches them against that file and a tidier spelling here would
 * read as a missing action rather than as a rename.
 *
 * The mapping onto BanMode/TargetType lives on the enums rather than in the
 * screen that shows them: it is what each choice *means*, and the previous
 * version kept it in an activity, where a source could name a list the engine
 * never read.
 */

/** Which set of users the run walks. */
enum class DateBulkSource(
    /**
     * The relation list to scrape, or null for the stored author list.
     *
     * Null is the marker, not a missing value: the author list is resolved to
     * nicks before the run and carried in the request, so there is no list to
     * scrape at all.
     */
    val relationList: TargetType?,
) {
    BLOCKED_USERS(TargetType.USER),
    MUTED_USERS(TargetType.MUTE),
    AUTHOR_LIST(null),
}

/**
 * What is done to everyone the filter lets through.
 *
 * [then] is a second relation applied only after the first succeeds, which is
 * how the extension's two combined actions behave — following someone whose
 * unblock failed would leave them blocked and followed.
 */
enum class DateBulkAction(
    val mode: BanMode,
    val target: TargetType,
    val then: TargetType? = null,
) {
    ENGELLE(BanMode.BAN, TargetType.USER),
    SESSIZE_AL(BanMode.BAN, TargetType.MUTE),
    ENGEL_KALDIR(BanMode.UNDOBAN, TargetType.USER),
    SESSIZDEN_CIKAR(BanMode.UNDOBAN, TargetType.MUTE),
    TAKIP_ET(BanMode.BAN, TargetType.FOLLOW),
    ENGEL_KALDIR_VE_TAKIP_ET(BanMode.UNDOBAN, TargetType.USER, then = TargetType.FOLLOW),
    SESSIZDEN_CIKAR_VE_TAKIP_ET(BanMode.UNDOBAN, TargetType.MUTE, then = TargetType.FOLLOW),
    TAKIPTEN_CIKAR(BanMode.UNDOBAN, TargetType.FOLLOW),
}
