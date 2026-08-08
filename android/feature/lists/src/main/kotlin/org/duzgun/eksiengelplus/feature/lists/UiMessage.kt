package org.duzgun.eksiengelplus.feature.lists

/**
 * Something to tell the user, and whether it is worth a way through.
 *
 * A queued run used to announce itself with a Toast and leave the user to find
 * İşlem durumu on their own -- which is the one thing they want next, since the
 * run they just asked for is not visible anywhere else on the screen they are
 * standing on.
 */
data class UiMessage(val text: String, val showOperations: Boolean = false)
