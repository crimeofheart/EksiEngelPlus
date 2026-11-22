"""The update policy and its APK are served from RELEASE_DIR and nothing else."""

import tempfile
from pathlib import Path

from django.test import SimpleTestCase, override_settings


class UpdatePolicyTests(SimpleTestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "policy").mkdir()
        (self.root / "download").mkdir()
        override = override_settings(RELEASE_DIR=self.tmp.name)
        override.enable()
        self.addCleanup(override.disable)

    def test_no_policy_is_404(self):
        self.assertEqual(self.client.get("/policy/app-v1.json").status_code, 404)

    def test_policy_is_served_uncached_to_any_origin(self):
        body = b'{"alg":"ES256","payload":"e30=","sig":"AA=="}\n'
        (self.root / "policy" / "app-v1.json").write_bytes(body)
        response = self.client.get("/policy/app-v1.json")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, body)
        self.assertEqual(response["Content-Type"], "application/json")
        self.assertEqual(response["Cache-Control"], "no-cache")
        self.assertEqual(response["Access-Control-Allow-Origin"], "*")

    def test_policy_refuses_writes(self):
        (self.root / "policy" / "app-v1.json").write_text("{}")
        self.assertEqual(self.client.post("/policy/app-v1.json").status_code, 405)

    def test_apk_is_served(self):
        (self.root / "download" / "eksiengelplus-0.6.5.apk").write_bytes(
            b"PK\x03\x04apk"
        )
        response = self.client.get("/download/eksiengelplus-0.6.5.apk")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(b"".join(response.streaming_content), b"PK\x03\x04apk")
        self.assertEqual(
            response["Content-Type"], "application/vnd.android.package-archive"
        )

    def test_missing_apk_is_404(self):
        self.assertEqual(
            self.client.get("/download/eksiengelplus-0.6.5.apk").status_code, 404
        )

    def test_only_release_apk_names_resolve(self):
        (self.root / "download" / "notes.txt").write_text("x")
        (self.root / "policy" / "app-v1.json").write_text("{}")
        for name in (
            "notes.txt",
            "eksiengelplus-0.6.5-unsigned.apk",
            "..%2Fpolicy%2Fapp-v1.json",
        ):
            with self.subTest(name=name):
                self.assertEqual(self.client.get(f"/download/{name}").status_code, 404)

    def test_stable_link_redirects_to_the_newest_apk(self):
        for version in ("0.6.4", "0.10.0", "0.9.12"):
            (self.root / "download" / f"eksiengelplus-{version}.apk").write_bytes(b"x")
        (self.root / "download" / "notes.txt").write_text("x")
        response = self.client.get("/download/eksiengelplus.apk")
        self.assertEqual(response.status_code, 302)
        self.assertEqual(response["Location"], "/download/eksiengelplus-0.10.0.apk")

    def test_stable_link_without_any_apk_is_404(self):
        self.assertEqual(self.client.get("/download/eksiengelplus.apk").status_code, 404)
