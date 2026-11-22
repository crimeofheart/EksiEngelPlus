"""Serves the signed update policy and the APK it names (openspec change
app-update-gate).

Both files live in RELEASE_DIR, outside the checkout, and only
tools/set-min-version.sh writes them. The host deploys with `git pull --ff-only`;
a file the tool wrote inside the checkout would make that pull fail, and a
deploy must never be able to roll the policy back.

Nothing here decides anything. The policy is signed with the maintainer's key and
both clients verify it, so this view is only a transport: a missing file is a 404,
which both clients read as "no policy" and block nothing.
"""

import re
from pathlib import Path

from django.conf import settings
from django.http import FileResponse, Http404, HttpResponse, HttpResponseRedirect
from django.views.decorators.http import require_safe

# The only names tools/set-min-version.sh uploads. Matching the whole name keeps
# the route from ever resolving anything else under RELEASE_DIR.
APK_NAME = re.compile(r"eksiengelplus-(\d+)\.(\d+)\.(\d+)\.apk")


def _release_dir() -> Path:
    return Path(settings.RELEASE_DIR)


@require_safe
def policy(request):
    try:
        body = (_release_dir() / "policy" / "app-v1.json").read_bytes()
    except OSError:
        raise Http404("no update policy published")
    response = HttpResponse(body, content_type="application/json")
    # Must reach a client the moment it is published: that is the whole lever.
    response["Cache-Control"] = "no-cache"
    # The extension's background fetches it without a host permission. The file
    # is public and signed, so any origin may read it.
    response["Access-Control-Allow-Origin"] = "*"
    return response


@require_safe
def download(request, name):
    if not APK_NAME.fullmatch(name):
        raise Http404
    path = _release_dir() / "download" / name
    if not path.is_file():
        raise Http404
    return FileResponse(
        path.open("rb"),
        as_attachment=True,
        filename=name,
        content_type="application/vnd.android.package-archive",
    )


@require_safe
def latest_apk(request):
    """The newest APK uploaded, under one address that never changes.

    The landing page and the Ekşi entry link here rather than to a version: the
    APK is no longer attached to GitHub Releases, and a versioned link would go
    stale at every release. A redirect, not the file, so the browser saves it
    under its real name.
    """
    try:
        names = [entry.name for entry in (_release_dir() / "download").iterdir()]
    except OSError:
        raise Http404
    versions = [
        (tuple(int(part) for part in match.groups()), name)
        for name in names
        if (match := APK_NAME.fullmatch(name))
    ]
    if not versions:
        raise Http404("no APK published")
    response = HttpResponseRedirect(f"/download/{max(versions)[1]}")
    response["Cache-Control"] = "no-cache"
    return response
