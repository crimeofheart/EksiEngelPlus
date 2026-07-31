import * as enums from './enums.js';
import {commHandler} from './commHandler.js';
import {config} from './config.js';
import {getNotes} from './changelog.js';

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
    for (const note of getNotes(version)) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.textContent = note;
      row.appendChild(cell);
      notesElem.appendChild(row);
    }
  }
}

document.addEventListener('DOMContentLoaded', async function () {
  applyTheme();
  showVersion();
});
