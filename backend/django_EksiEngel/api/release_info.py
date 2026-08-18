"""What version the landing page says it is shipping, and that version's notes.

The page used to hardcode the version in four places, so every release needed a
template edit that was easy to forget -- and a forgotten one is invisible, since
a stale number still renders fine. Both values are instead derived from files the
release tooling already maintains:

    android/version.json   the version -- one of the seven places `ext.mjs` keeps
                           in lockstep, and `npm run check` fails on a mismatch
    docs/changelog.json    the notes -- generated from changelog.js by
                           `npm run changelog`, and `npm run check` fails when it
                           is stale

So CI already guarantees both are correct for the shipping release, and the page
needs no edit at all: it is current as soon as the host pulls and restarts.

Two files rather than one on purpose. They answer different questions, and a
malformed changelog must not blank out the version -- every accessor degrades to
None and the template omits that piece rather than raising. Nothing here is worth
a 500 on the landing page.
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


@lru_cache(maxsize=4)
def _version(_key):
    data = _read_json(VERSION_FILE)
    if isinstance(data, dict):
        version = data.get("version")
        if isinstance(version, str) and version.strip():
            return version.strip()
    logger.warning("landing page: no usable version in %s", VERSION_FILE)
    return None


def _split_badge(line):
    """`"[Eklenti] foo"` -> `("Eklenti", "foo")`; anything else -> `(None, line)`."""
    for badge in PLATFORM_BADGES:
        prefix = f"[{badge}] "
        if line.startswith(prefix):
            return badge, line[len(prefix):]
    return None, line


@lru_cache(maxsize=4)
def _release(_key):
    """The newest release in docs/changelog.json, its notes grouped by platform."""
    data = _read_json(CHANGELOG_FILE)
    if not isinstance(data, list) or not data:
        return None

    # Index 0, never max() by version: the project restarted numbering at 0.1.0,
    # so the legacy 3.2.0 tail sorts *above* every modern release. The file is
    # generated newest-first and concatenated, never sorted -- see CLAUDE.md.
    entry = data[0]
    if not isinstance(entry, dict):
        return None

    groups = {}
    for line in entry.get("notes") or []:
        if not isinstance(line, str) or not line.strip():
            continue
        badge, text = _split_badge(line.strip())
        groups.setdefault(badge, []).append(text)

    if not groups:
        return None

    # Badge order as emitted, with any unlabelled remainder last.
    ordered = [
        {"label": badge, "notes": groups[badge]}
        for badge in PLATFORM_BADGES
        if badge in groups
    ]
    if None in groups:
        ordered.append({"label": None, "notes": groups[None]})

    return {
        "version": entry.get("version") or None,
        "date": entry.get("pub_date") or None,
        "groups": ordered,
    }


def landing_context():
    """Template context for the landing page. Never raises."""
    return {
        "version": _version(_mtime(VERSION_FILE)),
        "release": _release(_mtime(CHANGELOG_FILE)),
    }
