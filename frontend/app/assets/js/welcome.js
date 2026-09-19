import * as enums from './enums.js';
import {commHandler} from './commHandler.js';
import {config} from './config.js';
import {getSections, getVersionsSince, compareVersions} from './changelog.js';

console.log("welcome.js: has been started.");

// Apply saved theme on load
function applyTheme() {
  const savedTheme = localStorage.getItem('theme') || 'light';
  document.documentElement.setAttribute('data-theme', savedTheme);
}

/*
 * The release that stopped clearing storage on update.
 *
 * The clearing is done by the *outgoing* worker, so upgrading into this version
 * still loses the settings one last time -- the fix cannot reach back into the
 * version being replaced. Anyone arriving from here or later kept theirs and
 * must not be told otherwise.
 */
const SETTINGS_KEPT_FROM = "0.5.1";

/*
 * The warning retires itself: no `from` is a fresh install, which never had
 * settings to lose, and every upgrade from SETTINGS_KEPT_FROM onwards kept
 * them. Left as static markup it would have to be remembered and deleted by
 * hand, one release after it stopped being true.
 */
function showSettingsResetNotice(from) {
  const notice = document.getElementById('settingsResetNotice');
  if (!notice) return;
  
  const wasCleared = Boolean(from) && compareVersions(from, SETTINGS_KEPT_FROM) < 0;
  notice.style.display = wasCleared ? 'block' : 'none';
}

// Show the installed version and its release notes instead of hard-coded ones
function showVersion() {
  const version = chrome.runtime.getManifest().version;

  const numberElem = document.getElementById('versionNumber');
  if (numberElem) {
    numberElem.textContent = version;
  }

  const notesElem = document.getElementById('versionNotes');
  if (!notesElem) return;
  notesElem.replaceChildren();
  
  // Set by background.js on an update. Everything after it is what this reader
  // has not seen yet; without it they are new here and get the whole list.
  const from = new URLSearchParams(window.location.search).get('from');
  showSettingsResetNotice(from);
  
  // Never anything newer than what is actually installed: an entry can be
  // written before its release is cut, and this page must not announce it.
  let versions = getVersionsSince(from)
    .filter((candidate) => compareVersions(candidate, version) <= 0);
  if (versions.length === 0) versions = [version];
  
  // The heading only earns its place when there is more than one release to
  // tell apart.
  const showVersions = versions.length > 1;
  
  for (const shown of versions) {
    if (showVersions) {
      notesElem.appendChild(noteRow(`Sürüm ${shown}`, 'note-version'));
    }
    // Extension first: this is the extension's own welcome page, and the first
    // thing its reader is asking is what changed for them.
    for (const section of getSections(shown, ['extension', 'app'])) {
      if (section.label) {
        notesElem.appendChild(noteRow(section.label, 'note-platform'));
      }
      for (const note of section.notes) {
        notesElem.appendChild(noteRow(note));
      }
    }
  }
}

function noteRow(text, className) {
  const row = document.createElement('tr');
  const cell = document.createElement('td');
  cell.textContent = text;
  if (className) cell.className = className;
  row.appendChild(cell);
  return row;
}

document.addEventListener('DOMContentLoaded', async function () {
  applyTheme();
  showVersion();
});
