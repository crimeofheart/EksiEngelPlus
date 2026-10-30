"""The landing page's release notes run back to the last published version."""

import json
import tempfile
from pathlib import Path
from unittest import mock

from django.test import SimpleTestCase

from api import release_info


def entry(version, *notes):
    return {
        "name": "",
        "notes": list(notes),
        "pub_date": f"date-{version}",
        "version": version,
    }


# Newest first, with the legacy tail concatenated after the modern list, as
# `npm run changelog` writes it.
CHANGELOG = [
    entry("0.6.2", "[Eklenti] tab route", "[Uygulama] Bu sürümde değişiklik yok."),
    entry("0.6.1", "[Uygulama] pairing approval"),
    entry("0.5.4", "[Eklenti] reliability"),
    entry("0.5.3", "[Eklenti] follow counts"),
    entry("0.5.2", "[Eklenti] never shipped"),
    entry("3.2.0", "legacy"),
]


class ReleaseNotesTests(SimpleTestCase):
    def setUp(self):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.changelog = root / "changelog.json"
        self.changelog.write_text(json.dumps(CHANGELOG), encoding="utf-8")
        self.announcements = root / "announcements"
        self.announcements.mkdir()
        self.enterContext(
            mock.patch.object(release_info, "CHANGELOG_FILE", self.changelog)
        )
        self.enterContext(
            mock.patch.object(release_info, "ANNOUNCEMENTS_DIR", self.announcements)
        )
        release_info._release_notes.cache_clear()

    def announce(self, *versions):
        for version in versions:
            (self.announcements / f"{version}.txt").write_text(
                "entry", encoding="utf-8"
            )

    def notes(self):
        release_info._release_notes.cache_clear()
        return release_info.landing_context()["notes"]

    def test_runs_back_to_the_last_published_version_before_the_newest(self):
        self.announce("0.5.1", "0.5.3", "0.6.2")
        notes = self.notes()
        self.assertEqual(notes["since"], "0.5.3")
        self.assertEqual(
            [r["version"] for r in notes["releases"]], ["0.6.2", "0.6.1", "0.5.4"]
        )
        self.assertEqual(
            notes["releases"][0]["groups"],
            [
                {"label": "Eklenti", "notes": ["tab route"]},
                {"label": "Uygulama", "notes": ["Bu sürümde değişiklik yok."]},
            ],
        )

    def test_the_newest_need_not_be_announced_yet(self):
        self.announce("0.5.3")
        self.assertEqual(
            [r["version"] for r in self.notes()["releases"]],
            ["0.6.2", "0.6.1", "0.5.4"],
        )

    def test_without_an_older_announcement_the_newest_stands_alone(self):
        self.announce("0.6.2", "not-a-version")
        notes = self.notes()
        self.assertIsNone(notes["since"])
        self.assertEqual([r["version"] for r in notes["releases"]], ["0.6.2"])

    def test_one_release_since_the_last_published_reads_as_a_single_release(self):
        self.announce("0.6.1")
        notes = self.notes()
        self.assertIsNone(notes["since"])
        self.assertEqual([r["version"] for r in notes["releases"]], ["0.6.2"])

    def test_never_walks_into_the_legacy_tail(self):
        self.announce("0.1.0")
        versions = [r["version"] for r in self.notes()["releases"]]
        self.assertEqual(versions, ["0.6.2", "0.6.1", "0.5.4", "0.5.3", "0.5.2"])

    def test_a_missing_announcements_directory_shows_the_newest(self):
        self.announcements.rmdir()
        self.assertEqual([r["version"] for r in self.notes()["releases"]], ["0.6.2"])

    def test_a_malformed_changelog_shows_no_notes(self):
        self.changelog.write_text("{", encoding="utf-8")
        with self.assertLogs(release_info.logger, "WARNING"):
            self.assertIsNone(self.notes())


class LandingPageTests(SimpleTestCase):
    def test_the_page_renders_the_notes_since_the_last_published_version(self):
        release_info._release_notes.cache_clear()
        html = self.client.get("/").content.decode()
        self.assertIn('id="surum-notlari"', html)
