# Ekşi Sözlük fixture corpus

Captured HTML for regression-testing the selectors in
`openspec/changes/android-spike/specs/eksisozluk-client-contract/spec.md`.

Two clients implement that contract — the shipped extension
(`frontend/app/assets/js/scrapingHandler.js`, JSDOM/DOMParser) and the planned
Android app (Jsoup). This corpus is the shared oracle for both. A selector test
failing against a **fresh** capture is the signal that the site changed.

## What this is not

A permanent truth. These are point-in-time snapshots of a third-party site that
can change without notice. Treat a diff against fresh captures as information,
not as a test failure to be silenced.

## Capture

| | |
| --- | --- |
| Date | 2026-08-05 |
| Base | `https://eksisozluk.com` |
| Session | **logged out** — see the gap below |
| Method | `capture.sh`, `curl`, 1 s between requests |
| Headers | `Content-Type: application/x-www-form-urlencoded; charset=UTF-8` and `x-requested-with: XMLHttpRequest`, matching `relationHandler.js:142-145` |

Targets were resolved from the homepage at capture time rather than hardcoded,
so the corpus never depended on a specific entry surviving. This run used title
`/mohamed-salah-ghaly--3459509`, profile `/biri/goker`, entry `/entry/29256704`.

### User agents

| Slug | String |
| --- | --- |
| `desktop` | `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36` |
| `android_chrome` | `Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36` |
| `webview` | `Mozilla/5.0 (Linux; Android 15; Pixel 8 Build/AP4A.250105.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/139.0.0.0 Mobile Safari/537.36` |

### Files

`logged-out/<page>.<ua>.html`, for pages `home`, `profile`, `entry`, `title`.

## Content notice

These are public pages and contain user-generated content — public nicknames and
entry text written by third parties. Nothing here is behind a login and no
personal data beyond public authorship is present. The logged-in captures still
to be taken MUST use a throwaway account's own lists rather than a real user's.

## Coverage gap

Only public pages are covered. These endpoints need a session and are **not yet
captured**:

- `GET /` logged in — the `.mobile-notification-icons .mobile-only a[title]` nick
- `GET /biri/{nick}` logged in — `#who`, which does not exist logged out at all
- `GET /entry/favorileyenler?entryId=`
- `GET /entry/caylakfavorites?entryId=`
- `GET /relation-list?relationType={m|i|u}&pageIndex=`
- `GET /follower?nick=&pageIndex=` and `/following?nick=&pageIndex=`

Until those are captured, any selector reported as "absent everywhere" below is
ambiguous between auth-gated and removed.
