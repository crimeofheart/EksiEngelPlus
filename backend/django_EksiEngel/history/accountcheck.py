"""Asking Ekşi Sözlük's public pages whether an Ekşi id is a real account.

The server never sees an Ekşi session, so this cannot prove that whoever reported
under a nick owns it. What it can prove from logged-out pages: the profile exists
(/biri/<nick> answers 200, a made-up nick 404), and the nick and the numeric id
belong together -- /son-entryleri lists the account's entries, each tagged with
data-author-id. An account with no entries can only be shown to exist.

Two askers share this: the server's hourly timer, and a checker device -- the
admin's own browser, which Ekşi's Cloudflare challenge lets through when it turns
the server away. Either way the answer is classified here.
"""

import re
import urllib.error
import urllib.request
from datetime import timedelta
from urllib.parse import quote

from django.db.models import Count
from django.utils import timezone

from . import constants as c
from .models import AccountCheck, ActorRelation, Task

BASE_URL = "https://eksisozluk.com"
USER_AGENT = "Mozilla/5.0 (compatible; EksiEngelPlus account check; +https://eksiengelplus.duzgun.org)"
AUTHOR_ID = re.compile(r'data-author-id="(\d+)"')
TIMEOUT_SECONDS = 15
# Answers that turn the asker away rather than answer about one account: a
# Cloudflare challenge is a 403, rate limits are a 429 or 503.
REFUSED = (403, 429, 503)

# A settled answer is asked again after this long: accounts get deleted, and a
# nick can be released and taken by someone else.
RECHECK_AFTER = timedelta(days=30)
# An unanswered question is asked again sooner.
RETRY_ERROR_AFTER = timedelta(hours=1)
# Targets matter once they could reach a ranking.
TOP_TARGETS = 200


class Refused(Exception):
    """Ekşi turned the request away; the next account would be turned away too."""

    def __init__(self, status):
        super().__init__(f"HTTP {status}")
        self.status = status


def fetch(url, headers):
    """(HTTP status, body text). A network failure is status 0."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **headers})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, ""
    except (urllib.error.URLError, TimeoutError, OSError):
        return 0, ""


def slug(nick):
    return quote(nick.strip().replace(" ", "-"), safe="-")


def classify(eksi_id, profile, entries, author_ids):
    """(status, found_id) from what Ekşi answered about one claimed account.

    `profile` and `entries` are the HTTP statuses of /biri/<nick> and
    /son-entryleri (`entries` is None when it was not asked); `author_ids` are the
    ids its entries carry. Raises Refused when Ekşi turned the profile request away.
    """
    if profile == 404:
        return AccountCheck.MISSING, None
    if profile in REFUSED:
        raise Refused(profile)
    if profile != 200:
        return AccountCheck.ERROR, None
    if entries != 200 or not author_ids:
        # The profile is real; only the id could not be read this time.
        return AccountCheck.EXISTS, None
    if eksi_id in author_ids:
        return AccountCheck.OK, eksi_id
    return AccountCheck.MISMATCH, min(author_ids)


def check(nick, eksi_id, get=None):
    """(status, found_id) for one claimed account, asked from the server.

    Raises Refused when Ekşi turns the profile request away.
    """
    get = get or fetch
    profile, _ = get(f"{BASE_URL}/biri/{slug(nick)}", {})
    if profile != 200:
        return classify(eksi_id, profile, None, ())
    entries, body = get(
        f"{BASE_URL}/son-entryleri?nick={slug(nick)}",
        {"X-Requested-With": "XMLHttpRequest"},
    )
    return classify(
        eksi_id, profile, entries, {int(f) for f in AUTHOR_ID.findall(body)}
    )


def candidates():
    """Every reporting actor, then the targets near the top of the rankings."""
    actors = set(
        ActorRelation.objects.values_list("actor_eksi_id", flat=True).distinct()
    )
    actors |= set(
        Task.objects.exclude(actor_eksi_id=None).values_list("actor_eksi_id", flat=True)
    )
    targets = []
    for kind in (c.BLOCK, c.MUTE):
        targets += list(
            ActorRelation.objects.filter(kind=kind, first_added_at__isnull=False)
            .values("target_eksi_id")
            .annotate(n=Count("id"))
            .order_by("-n")
            .values_list("target_eksi_id", flat=True)[:TOP_TARGETS]
        )
    ordered = sorted(actors) + [t for t in targets if t not in actors]
    return list(dict.fromkeys(ordered))


def due(ids, now):
    existing = {a.eksi_id: a for a in AccountCheck.objects.filter(eksi_id__in=ids)}
    out = []
    for eksi_id in ids:
        known = existing.get(eksi_id)
        if known is None:
            out.append(eksi_id)
        elif known.status == AccountCheck.ERROR:
            if now - known.checked_at >= RETRY_ERROR_AFTER:
                out.append(eksi_id)
        elif now - known.checked_at >= RECHECK_AFTER:
            out.append(eksi_id)
    return out


def record(eksi_id, nick, status, found_id, device=None):
    AccountCheck.objects.update_or_create(
        eksi_id=eksi_id,
        defaults={
            "nick": nick,
            "status": status,
            "found_id": found_id,
            "checked_at": timezone.now(),
            "checked_by": device,
        },
    )
