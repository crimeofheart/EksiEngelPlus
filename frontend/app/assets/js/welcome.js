import * as enums from './enums.js';
import {commHandler} from './commHandler.js';
import {config} from './config.js';
import {getSections} from './changelog.js';

console.log("welcome.js: has been started.");

// Apply saved theme on load
function applyTheme() {
  const savedTheme = localStorage.getItem('theme') || 'light';
  document.documentElement.setAttribute('data-theme', savedTheme);
}

// Show the installed version and its release notes instead of hard-coded ones
function showVersion() {
  const version = chrome.runtime.getManifest().version;

  const numberElem = document.getElementById('versionNumber');
  if (numberElem) {
    numberElem.textContent = version;
  }

  const notesElem = document.getElementById('versionNotes');
  if (notesElem) {
    notesElem.replaceChildren();
    // Extension first: this is the extension's own welcome page, and the first
    // thing its reader is asking is what changed for them.
    for (const section of getSections(version, ['extension', 'app'])) {
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
