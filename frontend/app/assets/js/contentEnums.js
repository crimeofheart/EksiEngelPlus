/*
 * The enums the content script uses, as a classic script it can load.
 *
 * Content scripts cannot be modules, and script.js used to fetch enums.js with
 * import() and a computed URL -- the one call addons.mozilla.org flags because it
 * cannot see what is being loaded. Listed before script.js in both manifests.
 *
 * A copy of enums.js, not a second source: scripts/test-relation-actions.mjs fails
 * when any value here differs from it.
 */
self.EksiEngelEnums = Object.freeze({
  BanSource: Object.freeze({"SINGLE":"1","FAV":"2","FOLLOW":"3","LIST":"4","UNDOBANALL":"5","TITLE":"6","BLOCKED_MUTED_TITLES":"7","MIGRATE_BLOCKED_TO_MUTED":"8","BLOCK_MUTED_USERS":"9","REFRESH_MUTED_LIST":"10","REFRESH_BLOCKED_LIST":"11","DATE_BASED_BULK":"12","UNMUTEALL":"13","REFRESH_FOLLOWED_LIST":"14","FOLLOWEES":"15"}),
  BanMode: Object.freeze({"BAN":"1","UNDOBAN":"2"}),
  TargetType: Object.freeze({"USER":"1","TITLE":"2","MUTE":"3","FOLLOW":"4"}),
  ClickSource: Object.freeze({"ENTRY":"1","PROFILE":"2","QUESTION":"3","FOLLOWING":"4","FOLLOWER":"5","TITLE":"6"}),
  TimeSpecifier: Object.freeze({"LAST_24_H":"1","LAST_1_W":"2","LAST_1_M":"3","LAST_3_M":"4","ALL":"5"}),
});
