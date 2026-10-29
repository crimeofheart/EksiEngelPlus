"""Asking Ekşi Sözlük's public pages whether an Ekşi id is a real account.

The server never sees an Ekşi session, so this cannot prove that whoever reported
under a nick owns it. What it can prove from logged-out pages: the profile exists
(/biri/<nick> answers 200, a made-up nick 404), and the nick and the numeric id
belong together -- /son-entryleri lists the account's entries, each tagged with
data-author-id. An account with no entries can only be shown to exist.
"""

import re
import urllib.error
import urllib.request
from urllib.parse import quote

from .models import AccountCheck

BASE_URL = "https://eksisozluk.com"
USER_AGENT = "Mozilla/5.0 (compatible; EksiEngelPlus account check; +https://eksiengelplus.duzgun.org)"
AUTHOR_ID = re.compile(r'data-author-id="(\d+)"')
TIMEOUT_SECONDS = 15


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


def check(nick, eksi_id, get=None):
    """(status, found_id) for one claimed account; see AccountCheck for the statuses."""
    get = get or fetch
    status, _ = get(f"{BASE_URL}/biri/{slug(nick)}", {})
    if status == 404:
        return AccountCheck.MISSING, None
    if status != 200:
        return AccountCheck.ERROR, None
    status, body = get(
        f"{BASE_URL}/son-entryleri?nick={slug(nick)}",
        {"X-Requested-With": "XMLHttpRequest"},
    )
    if status != 200:
        # The profile is real; only the id could not be read this time.
        return AccountCheck.EXISTS, None
    ids = {int(found) for found in AUTHOR_ID.findall(body)}
    if not ids:
        return AccountCheck.EXISTS, None
    if eksi_id in ids:
        return AccountCheck.OK, eksi_id
    return AccountCheck.MISMATCH, min(ids)
