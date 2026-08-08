package org.duzgun.eksiengelplus.model

/**
 * Ported from frontend/app/assets/js/enums.js.
 *
 * The integer values are NOT arbitrary. They are primary keys in lookup tables of
 * the shared Django backend, holding rows the extension has already written. A
 * value changed here silently reinterprets historical telemetry, so they must
 * match `enums.js` exactly.
 */

interface HasPk {
    val pk: Int
}

/** api.BanSource — widened to 14 in api/migrations/0008_widen_ban_source_and_seed_missing.py. */
enum class BanSource(override val pk: Int) : HasPk {
    SINGLE(1),
    FAV(2),
    FOLLOW(3),
    LIST(4),
    UNDOBANALL(5),
    TITLE(6),
    BLOCKED_MUTED_TITLES(7),
    MIGRATE_BLOCKED_TO_MUTED(8),
    BLOCK_MUTED_USERS(9),
    REFRESH_MUTED_LIST(10),
    REFRESH_BLOCKED_LIST(11),
    DATE_BASED_BULK(12),
    UNMUTEALL(13),
    REFRESH_FOLLOWED_LIST(14);

    companion object {
        fun fromPk(pk: Int) = entries.firstOrNull { it.pk == pk }
    }
}

enum class BanMode(override val pk: Int) : HasPk {
    BAN(1),
    UNDOBAN(2);

    /** The URL segment this mode maps to (relationHandler.js:109-110). */
    val urlSegment: String get() = if (this == BAN) "addrelation" else "removerelation"

    companion object {
        fun fromPk(pk: Int) = entries.firstOrNull { it.pk == pk }
    }
}

/**
 * The `r=` query parameter of the mutation endpoint. The letter codes and the pks
 * are independent: pks go to our backend, letters go to Ekşi
 * (relationHandler.js:113-117).
 */
enum class TargetType(override val pk: Int, val relationCode: String) : HasPk {
    USER(1, "m"),
    TITLE(2, "i"),
    MUTE(3, "u"),
    FOLLOW(4, "b");

    companion object {
        fun fromPk(pk: Int) = entries.firstOrNull { it.pk == pk }
    }
}

enum class ClickSource(override val pk: Int) : HasPk {
    ENTRY(1),
    PROFILE(2),
    QUESTION(3),
    FOLLOWING(4),
    FOLLOWER(5),
    TITLE(6);

    companion object {
        fun fromPk(pk: Int) = entries.firstOrNull { it.pk == pk }
    }
}

enum class TimeSpecifier(override val pk: Int) : HasPk {
    LAST_24_H(1),
    LAST_1_W(2),
    LAST_1_M(3),
    LAST_3_M(4),
    ALL(5);

    companion object {
        fun fromPk(pk: Int) = entries.firstOrNull { it.pk == pk }
    }
}

/**
 * Deliberately divergent from the server's seed, and it must stay that way.
 *
 * The backend seeds this table as 1=DEBUG, 2=INFO, 3=WARNING, 4=ERROR
 * (api/migrations/0007_seed_lookup_data.py:38), but every client — the extension
 * at frontend/app/assets/js/log.js:35, and now this one — sends
 * {DISABLED:1, INFO:2, WARN:3, ERR:4}. Existing rows with log_level=1 therefore
 * mean "logging disabled", not DEBUG.
 *
 * Correcting the client to match the seed would change the meaning of a column
 * that already holds years of rows written under the client mapping. Carry the
 * divergence.
 */
enum class LogLevel(override val pk: Int) : HasPk {
    DISABLED(1),
    INFO(2),
    WARN(3),
    ERR(4);

    companion object {
        fun fromPk(pk: Int) = entries.firstOrNull { it.pk == pk }
    }
}

/** Which stored relation list a row belongs to. */
enum class ListType {
    BLOCKED,
    MUTED,
    FOLLOWED,

    /**
     * Title bans, the r=i relation.
     *
     * A list of its own rather than a property of the blocked users: the users
     * whose titles were banned are not the users currently blocked, so removing
     * title bans by walking the blocked list finds the wrong set.
     *
     * Stored by name through the existing converter, so the schema is unchanged.
     */
    TITLE_BANNED,
}
