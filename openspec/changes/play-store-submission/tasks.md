## 1. Closed test track (starts the 14-day clock)

- [ ] 1.1 Create or confirm the Play Console app entry: default language Turkish (tr-TR), app (not game), free, `org.duzgun.eksiengelplus`
- [ ] 1.2 Upload `frontend/publish/dist/eksiengelplus-0.6.5.aab` to a closed testing track and confirm the app is enrolled in Play App Signing with the pinned upload certificate (`0F:8C:86:D6…`)
- [ ] 1.3 Record in `android/play/README.md`: track name, AAB file name, enrolment date, tester count and the date the 14 days complete
- [ ] 1.4 Invite the 12 testers by e-mail list, have each open the opt-in link, and check the Console tester count reaches 12
- [ ] 1.5 `cd frontend/app && npm run check && npm run package`

## 2. Listing assets

- [ ] 2.1 Create a disposable Ekşi Sözlük account for the submission and note it in the kit (never a personal account)
- [ ] 2.2 Capture at least two 9:16 screenshots with `adb exec-out screencap -p` — entry menu with "engelle", "işlem durumu" with a running operation, "toplu işlemler", settings with the date rules
- [ ] 2.3 Save them under `android/play/screenshots/` and check every nickname belongs to the test account or a public title author list
- [ ] 2.4 Record the foreground-service video: start a bulk operation, leave the app, show the progress notification advancing (`adb shell screenrecord`), upload unlisted to YouTube, put the URL in `android/play/README.md`
- [ ] 2.5 Fill the listing from the kit: name, category Social, contact, website, privacy URL, tr-TR and en-US short and full descriptions, `icon-512.png`, `feature-graphic.png`, the screenshots
- [ ] 2.6 `cd frontend/app && npm run check && npm run package`

## 3. App content declarations

- [ ] 3.1 Paste App access: the test account's username and password plus the numbered sign-in-and-block path
- [ ] 3.2 Fill Data safety exactly as `android/play/README.md` tabulates it, cross-checking `backend/django_EksiEngel/api/templates/privacy/index.html` ("Sunucumuza gönderilenler") and `openspec/specs/android-persistence/spec.md`
- [ ] 3.3 Declare the foreground service as **Data sync** with the kit's description and the video URL
- [ ] 3.4 Answer Ads (no), Target audience (18+), Content rating (Social / communication, user interaction yes), News (no), Government (no), Financial features (none), Health (not a health app)
- [ ] 3.5 Write the three review-risk answers (WebView purpose, name/impersonation, automation pacing) into the submission's review notes
- [ ] 3.6 `cd frontend/app && npm run check && npm run package`

## 4. Backend tests that a store release makes public

- [x] 4.1 Add `backend/django_EksiEngel/django_EksiEngel/settings_test.py` supplying every env key `settings.py` requires and defaulting the database to the throwaway Postgres credentials
- [x] 4.2 Add `backend/requirements-dev.txt` pinning the test dependencies, `psycopg2-binary` in place of `psycopg2`
- [x] 4.3 Run `python manage.py test --settings=django_EksiEngel.settings_test` locally against Postgres and get all 14 test modules green (167 tests)
- [x] 4.4 Add a `backend` job to `.github/workflows/check.yml` with a `postgres:16` service
- [x] 4.5 Confirm the `changes` filter selects the backend job for a commit that touches only `docs/changelog.json` or `android/version.json`
- [x] 4.6 `cd frontend/app && npm run check && npm run package`

## 5. Production and the play channel

- [ ] 5.1 Apply for production once 12 testers have been opted in for 14 consecutive days
- [ ] 5.2 Promote the release, roll out, and wait for Play to serve it to everyone
- [ ] 5.3 Run `tools/set-min-version.sh --min-play 0.6.5` only after 5.2 completes, and read the served policy back
- [ ] 5.4 Add `docs/eksisozluk/announcements/<version>.txt` and update `first-entry.txt` once the version is live, then post only after the store shows it
- [ ] 5.5 Archive this change with `openspec archive play-store-submission`
- [ ] 5.6 `cd frontend/app && npm run check && npm run package`
