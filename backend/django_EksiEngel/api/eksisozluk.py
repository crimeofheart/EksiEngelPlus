"""Deep links from the admin back to Ekşi Sözlük.

Every admin page here displays scraped names and ids, and the follow-up question is
always "who/what is that?". The URL shapes live in this module once; admin classes
declare a column with ``eksi_link(...)`` rather than writing a ``format_html`` call
each time.
"""

import re
from urllib.parse import quote

from django.conf import settings
from django.utils.html import format_html

# Overridable so a mirror domain (the site moves when it gets blocked -- see
# where_is_eksisozluk) can be pointed at without touching this module.
BASE_URL = getattr(settings, "EKSISOZLUK_URL", "https://eksisozluk.com").rstrip("/")

# Ekşi Sözlük title slugs are ASCII: the site folds Turkish letters instead of
# percent-encoding them. Only the id after "--" is used for lookup, so a slug that
# drifts from the current title still resolves.
_TR_FOLD = str.maketrans({
    "ı": "i", "İ": "i", "ş": "s", "Ş": "s", "ğ": "g", "Ğ": "g",
    "ü": "u", "Ü": "u", "ö": "o", "Ö": "o", "ç": "c", "Ç": "c",
    "â": "a", "Â": "a", "î": "i", "Î": "i", "û": "u", "Û": "u",
})

EMPTY = "—"


def slugify_title(name):
    folded = (name or "").translate(_TR_FOLD).lower()
    return re.sub(r"[^a-z0-9]+", "-", folded).strip("-")


def user_url(name):
    # Usernames are stored with spaces already collapsed to dashes by the extension
    # (scrapingHandler.scrapeClientNameAndId), but author lists come from other paths.
    return f"{BASE_URL}/biri/{quote(str(name).replace(' ', '-'), safe='')}"


def title_url(name, pk):
    slug = slugify_title(name)
    if pk:
        return f"{BASE_URL}/{slug}--{pk}" if slug else f"{BASE_URL}/--{pk}"
    return f"{BASE_URL}/?q={quote(str(name or ''), safe='')}"


def entry_url(pk):
    return f"{BASE_URL}/entry/{pk}"


def _user(name, pk):
    return (user_url(name), str(name)) if name else None


def _title(name, pk):
    if not (name or pk):
        return None
    return title_url(name, pk), str(name or f"#{pk}")


def _entry(name, pk):
    return (entry_url(pk), f"#{pk}") if pk else None


# kind -> (url, label) builder. Adding a linkable thing means one entry here.
BUILDERS = {
    "user": _user,
    "title": _title,
    "entry": _entry,
}


def _resolve(obj, path):
    """Walk a dotted attribute path, returning None as soon as anything is missing."""
    if not path:
        return None
    for part in path.split("."):
        obj = getattr(obj, part, None)
        if obj is None:
            return None
    return obj


def eksi_anchor(kind, name=None, pk=None, label=None):
    built = BUILDERS[kind](name, pk)
    if not built:
        return EMPTY
    url, text = built
    return format_html(
        '<a class="ee-ext" href="{}" target="_blank" rel="noopener noreferrer">{}</a>',
        url, label or text,
    )


def eksi_link(kind, *, name=None, pk=None, description=None, ordering=None, label=None):
    """Build an admin display callable that renders one Ekşi Sözlük deep link.

    ``name`` and ``pk`` are dotted attribute paths resolved against the row, so the
    same factory serves a plain field ("eksisozluk_name") and one reached through a
    relation ("eksi_engel_user.eksisozluk_name").
    """
    def display(self, obj):
        return eksi_anchor(kind, _resolve(obj, name), _resolve(obj, pk), label)

    display.short_description = description or kind
    if ordering:
        display.admin_order_field = ordering
    return display
