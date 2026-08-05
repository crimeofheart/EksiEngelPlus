package org.duzgun.eksiengelplus.eksi.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire shapes for the three JSON endpoints. Field names are PascalCase on the
 * wire, hence the explicit @SerialName on everything.
 *
 * ignoreUnknownKeys is not laziness: removerelation already returns an
 * undocumented `count` alongside `result`, so the site demonstrably adds fields
 * without notice.
 */
val EksiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

@Serializable
data class NickValue(@SerialName("Value") val value: String)

@Serializable
data class RelationItem(
    @SerialName("Id") val id: Long,
    @SerialName("Nick") val nick: NickValue,
)

@Serializable
data class RelationsBlock(
    @SerialName("IsLast") val isLast: Boolean,
    @SerialName("Items") val items: List<RelationItem> = emptyList(),
)

/** GET /relation-list?relationType={m|i|u}&pageIndex={n>=1}. Page size observed: 25. */
@Serializable
data class RelationListResponse(
    @SerialName("Relations") val relations: RelationsBlock,
)

/**
 * GET /follower|/following?nick={nick}&pageIndex={n>=1} -> a bare array.
 * No IsLast field: pagination ends on an empty array.
 */
@Serializable
data class FollowUser(
    @SerialName("Id") val id: Long,
    @SerialName("Nick") val nick: NickValue,
    @SerialName("IsFollowCurrentUser") val isFollowCurrentUser: Boolean = false,
    @SerialName("IsBuddy") val isBuddy: Boolean = false,
)
