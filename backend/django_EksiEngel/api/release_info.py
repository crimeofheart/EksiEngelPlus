"""What version the landing page says it is shipping, and what changed since the
last version that reached the stores.

The page used to hardcode the version in four places, so every release needed a
template edit that was easy to forget -- and a forgotten one is invisible, since
a stale number still renders fine. Both values are instead derived from files the
release tooling already maintains:

    android/version.json        the version -- one of the seven places `ext.mjs`
                                keeps in lockstep, and `npm run check` fails on a
                                mismatch
    docs/changelog.json         the notes -- generated from changelog.js by
                                `npm run changelog`, and `npm run check` fails
                                when it is stale
    docs/eksisozluk/announcements/
                                which versions were published: each one gets an
                                Ekşi announcement, and a tagged version that never
                                reached the stores gets none (CLAUDE.md, "Ekşi
                                Sözlük entries")

So CI already guarantees the first two are correct for the shipping release, and
the page needs no edit at all: it is current as soon as the host pulls and
restarts.

The notes run back to the last published version before the newest one, not just
the newest: versions are tagged that never ship (0.5.2) or ship together (0.5.4 to
0.6.2 went out as one update from 0.5.3), and a store user updating across them
gets all of their changes at once.

Separate files on purpose. They answer different questions, and a malformed
changelog must not blank out the version -- every accessor degrades to None and
the template omits that piece rather than raising. Nothing here is worth a 500 on
the landing page.
"""

import json
import logging
from functools import lru_cache
from pathlib import Path

from django.conf import settings

logger = logging.getLogger(__name__)

# settings.BASE_DIR is backend/django_EksiEngel; the deploy is a clone of the
# whole repo (/var/www/EksiEngelPlus), so docs/ and android/ are both present.
REPO_ROOT = Path(settings.BASE_DIR).parent.parent
VERSION_FILE = REPO_ROOT / "android" / "version.json"
CHANGELOG_FILE = REPO_ROOT / "docs" / "changelog.json"
ANNOUNCEMENTS_DIR = REPO_ROOT / "docs" / "eksisozluk" / "announcements"

# The badges `npm run changelog` prefixes notes with, in the order it emits them.
# A line with any other prefix is kept and shown unlabelled rather than dropped:
# failing to display a release note is worse than displaying it without a header.
PLATFORM_BADGES = ("Eklenti", "Uygulama")


def _mtime(path):
    """Cache key. A pull that forgets the restart still serves fresh values."""
    try:
        return path.stat().st_mtime
    except OSError:
        return None


def _read_json(path):
    try:
        with path.open(encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        logger.warning("landing page: cannot read %s", path, exc_info=True)
        return None


def _parse_version(text):
    """`"0.5.3"` -> `(0, 5, 3)`; anything else -> None."""
    if not isinstance(text, str):
        return None
    parts = text.strip().split(".")
    if len(parts) != 3 or not all(part.isdigit() for part in parts):
        return None
    return tuple(int(part) for part in parts)


@lru_cache(maxsize=4)
def _version(_key):
    data = _read_json(VERSION_FILE)
    if isinstance(data, dict):
        version = data.get("version")
        if isinstance(version, str) and version.strip():
            return version.strip()
    logger.warning("landing page: no usable version in %s", VERSION_FILE)
    return None


def _announced():
    """Every version with an announcement file, i.e. every published version."""
    try:
        names = [path.stem for path in ANNOUNCEMENTS_DIR.glob("*.txt")]
    except OSError:
        return []
    return [version for version in map(_parse_version, names) if version]


def _split_badge(line):
    """`"[Eklenti] foo"` -> `("Eklenti", "foo")`; anything else -> `(None, line)`."""
    for badge in PLATFORM_BADGES:
        prefix = f"[{badge}] "
        if line.startswith(prefix):
            return badge, line[len(prefix) :]
    return None, line


def _groups(entry):
    """One changelog entry's notes, grouped by platform in badge order."""
    groups = {}
    for line in entry.get("notes") or []:
        if not isinstance(line, str) or not line.strip():
            continue
        badge, text = _split_badge(line.strip())
        groups.setdefault(badge, []).append(text)

    # Badge order as emitted, with any unlabelled remainder last.
    ordered = [
        {"label": badge, "notes": groups[badge]}
        for badge in PLATFORM_BADGES
        if badge in groups
    ]
    if None in groups:
        ordered.append({"label": None, "notes": groups[None]})
    return ordered


@lru_cache(maxsize=4)
def _release_notes(_key):
    """The newest release and every one back to the last published version before it.

    `since` is that published version, set only when more than one release is
    shown; without an older announcement the newest release stands alone.
    """
    data = _read_json(CHANGELOG_FILE)
    if not isinstance(data, list) or not data or not isinstance(data[0], dict):
        return None

    newest = _parse_version(data[0].get("version"))
    older = [version for version in _announced() if newest and version < newest]
    since = max(older, default=None)

    # Walk from index 0 and stop at the first entry that is not newer than `since`
    # or not older than the one before it, never sort or max() the list: the
    # project restarted numbering at 0.1.0, so the legacy 3.2.0 tail sorts *above*
    # every modern release. The file is generated newest-first and concatenated,
    # never sorted -- see CLAUDE.md.
    releases = []
    previous = newest
    for index, entry in enumerate(data):
        if not isinstance(entry, dict):
            break
        if index:
            version = _parse_version(entry.get("version"))
            if (
                since is None
                or version is None
                or version <= since
                or version >= previous
            ):
                break
            previous = version
        groups = _groups(entry)
        if groups:
            releases.append(
                {
                    "version": entry.get("version") or None,
                    "date": entry.get("pub_date") or None,
                    "groups": groups,
                }
            )

    if not releases:
        return None
    return {
        "since": ".".join(map(str, since)) if since and len(releases) > 1 else None,
        "releases": releases,
    }


def landing_context():
    """Template context for the landing page. Never raises."""
    return {
        "version": _version(_mtime(VERSION_FILE)),
        # The directory's mtime changes when an announcement is added.
        "notes": _release_notes((_mtime(CHANGELOG_FILE), _mtime(ANNOUNCEMENTS_DIR))),
    }
