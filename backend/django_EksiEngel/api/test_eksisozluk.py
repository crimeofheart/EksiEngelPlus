from django.contrib.auth.models import User
from django.test import TestCase

from client_data_collector.models import ClickType, ClientAnalytic
from django.utils import timezone

from .eksisozluk import BASE_URL, entry_url, slugify_title, title_url, user_url
from .models import Action, BanMode, BanSource, EksiSozlukEntry, EksiSozlukTitle, EksiSozlukUser, LogLevel


class UrlShapeTests(TestCase):
    """The links are the whole feature; a wrong shape is a dead end, not a warning."""

    def test_title_slug_folds_turkish_letters_to_ascii(self):
        # Ekşi Sözlük folds rather than percent-encodes, so ı/ş/ğ must not survive.
        self.assertEqual(slugify_title("Ekşi Şeyler ğüöçı"), "eksi-seyler-guoci")

    def test_title_url_carries_the_id_after_a_double_dash(self):
        self.assertEqual(title_url("pena", 31782), f"{BASE_URL}/pena--31782")

    def test_title_without_an_id_falls_back_to_search(self):
        self.assertEqual(title_url("pena", None), f"{BASE_URL}/?q=pena")

    def test_title_of_only_punctuation_still_resolves_by_id(self):
        # An empty slug would produce ".../--123", which the site still routes by id.
        self.assertEqual(title_url("!!!", 123), f"{BASE_URL}/--123")

    def test_user_url_uses_dashes_and_escapes_the_rest(self):
        self.assertEqual(user_url("kaptan pijama"), f"{BASE_URL}/biri/kaptan-pijama")
        self.assertEqual(user_url("ekşi"), f"{BASE_URL}/biri/ek%C5%9Fi")

    def test_entry_url(self):
        self.assertEqual(entry_url(9), f"{BASE_URL}/entry/9")


class AdminLinkColumnTests(TestCase):
    """Each changelist that shows a scraped name must offer the way back to the site."""

    @classmethod
    def setUpTestData(cls):
        cls.admin = User.objects.create_superuser("linker", "l@example.com", "pw")
        cls.user = EksiSozlukUser.objects.create(
            eksisozluk_name="testuser", eksisozluk_id=42, is_eksiengel_user=True)
        cls.title = EksiSozlukTitle.objects.create(eksisozluk_name="ekşi başlık", eksisozluk_id=7)
        cls.entry = EksiSozlukEntry.objects.create(eksisozluk_title=cls.title, eksisozluk_id=99)
        cls.action = Action.objects.create(
            eksi_engel_user=cls.user, version="0.1", user_agent="ua",
            ban_source=BanSource.objects.get(ban_source="FAV"),
            ban_mode=BanMode.objects.get(ban_mode="BAN"),
            author_list_size=0, planned_action=1, performed_action=1, successful_action=1,
            is_early_stopped=False, log_level=LogLevel.objects.get(log_level="INFO"),
            fav_title=cls.title, fav_entry=cls.entry, fav_author=cls.user,
        )

    def setUp(self):
        self.client.force_login(self.admin)

    def test_user_changelist_links_to_the_profile(self):
        body = self.client.get("/admin/api/eksisozlukuser/").content.decode()
        self.assertIn(f"{BASE_URL}/biri/testuser", body)

    def test_title_changelist_links_to_the_title(self):
        body = self.client.get("/admin/api/eksisozluktitle/").content.decode()
        self.assertIn(f"{BASE_URL}/eksi-baslik--7", body)

    def test_entry_changelist_links_to_both_entry_and_title(self):
        body = self.client.get("/admin/api/eksisozlukentry/").content.decode()
        self.assertIn(f"{BASE_URL}/entry/99", body)
        self.assertIn(f"{BASE_URL}/eksi-baslik--7", body)

    def test_action_changelist_links_to_the_user_who_ran_it(self):
        body = self.client.get("/admin/api/action/").content.decode()
        self.assertIn(f"{BASE_URL}/biri/testuser", body)

    def test_action_detail_links_every_fav_target(self):
        body = self.client.get(f"/admin/api/action/{self.action.pk}/change/").content.decode()
        self.assertIn(f"{BASE_URL}/entry/99", body)
        self.assertIn(f"{BASE_URL}/eksi-baslik--7", body)
        self.assertIn(f"{BASE_URL}/biri/testuser", body)

    def test_missing_fav_target_renders_a_dash_not_a_broken_link(self):
        bare = Action.objects.create(
            eksi_engel_user=self.user, version="0.1", user_agent="ua",
            ban_source=BanSource.objects.get(ban_source="SINGLE"),
            ban_mode=BanMode.objects.get(ban_mode="BAN"),
            author_list_size=0, planned_action=1, performed_action=1, successful_action=1,
            is_early_stopped=False, log_level=LogLevel.objects.get(log_level="INFO"),
        )
        body = self.client.get(f"/admin/api/action/{bare.pk}/change/").content.decode()
        self.assertNotIn(f"{BASE_URL}/entry/None", body)
        self.assertNotIn("/biri/None", body)

    def test_dashboard_links_its_most_active_users(self):
        body = self.client.get("/admin/").content.decode()
        self.assertIn("Most active users", body)
        self.assertIn(f"{BASE_URL}/biri/testuser", body)


class SearchSafetyTests(TestCase):
    """A username typed into a page whose search_fields start with "=id" used to 500."""

    @classmethod
    def setUpTestData(cls):
        cls.admin = User.objects.create_superuser("searcher", "s@example.com", "pw")
        cls.user = EksiSozlukUser.objects.create(
            eksisozluk_name="testuser", eksisozluk_id=42, is_eksiengel_user=True)
        cls.action = Action.objects.create(
            eksi_engel_user=cls.user, version="0.1", user_agent="ua",
            ban_source=BanSource.objects.get(ban_source="SINGLE"),
            ban_mode=BanMode.objects.get(ban_mode="BAN"),
            author_list_size=0, planned_action=1, performed_action=1, successful_action=1,
            is_early_stopped=False, log_level=LogLevel.objects.get(log_level="INFO"),
        )

    def setUp(self):
        self.client.force_login(self.admin)

    def test_text_search_on_a_numeric_search_field_still_finds_the_row(self):
        response = self.client.get("/admin/api/action/?q=testuser")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.context["cl"].result_list), 1)

    def test_numeric_search_still_matches_the_id(self):
        response = self.client.get(f"/admin/api/action/?q={self.action.pk}")
        self.assertEqual(len(response.context["cl"].result_list), 1)

    def test_text_search_survives_on_every_admin_that_mixes_id_and_name(self):
        for url in ("/admin/api/actionconfig/", "/admin/api/eksisozlukuser/",
                    "/admin/api/eksisozluktitle/", "/admin/api/eksisozlukentry/",
                    "/admin/client_data_collector/clientanalytic/"):
            self.assertEqual(self.client.get(f"{url}?q=testuser").status_code, 200, url)


class ClientAnalyticAttributionTests(TestCase):
    """UI events used to be anonymous by omission rather than by design."""

    @classmethod
    def setUpTestData(cls):
        cls.admin = User.objects.create_superuser("uiev", "u@example.com", "pw")

    def _post(self, **payload):
        from django.conf import settings
        return self.client.post(
            "/admin/api/client_data/analytics",
            data={"click_type": "EXTENSION_ICON", **payload},
            content_type="application/json",
            HTTP_X_API_KEY=settings.SHARED_API_KEY,
        )

    def test_identity_is_stored_when_the_extension_sends_it(self):
        self._post(client_name="testuser", client_uid=42, version="0.1.5",
                   user_agent="Mozilla/5.0 Firefox/128.0")
        event = ClientAnalytic.objects.get()
        self.assertEqual(event.client_name, "testuser")
        self.assertEqual(event.client_uid, 42)
        self.assertEqual(event.version, "0.1.5")

    def test_old_builds_store_null_rather_than_a_placeholder(self):
        # Pre-0.1.5 builds send click_type alone; "unknown" would be indistinguishable
        # from a user actually called that.
        self._post()
        event = ClientAnalytic.objects.get()
        self.assertIsNone(event.client_name)
        self.assertIsNone(event.client_uid)
        self.assertIsNone(event.user_agent)
        self.assertIsNone(event.version)

    def test_explicit_placeholders_from_older_clients_are_also_nulled(self):
        self._post(client_name="unknown", client_uid=0, user_agent="unknown")
        event = ClientAnalytic.objects.get()
        self.assertIsNone(event.client_name)
        self.assertIsNone(event.client_uid)

    def test_over_long_values_are_clipped_to_the_column(self):
        self._post(client_name="x" * 200, user_agent="y" * 4000)
        event = ClientAnalytic.objects.get()
        self.assertEqual(len(event.client_name), 96)
        self.assertEqual(len(event.user_agent), 1024)

    def test_admin_links_the_event_to_the_profile(self):
        self._post(client_name="testuser", client_uid=42,
                   user_agent="Mozilla/5.0 Firefox/128.0")
        self.client.force_login(self.admin)
        body = self.client.get("/admin/client_data_collector/clientanalytic/").content.decode()
        self.assertIn(f"{BASE_URL}/biri/testuser", body)
        self.assertIn("Firefox", body)

    def test_dashboard_counts_attributed_events(self):
        self._post(client_name="testuser", client_uid=42)
        self._post()
        self.client.force_login(self.admin)
        body = self.client.get("/admin/").content.decode()
        self.assertIn("1 attributed to a username", body)

    def test_identified_filter_splits_the_two_populations(self):
        self._post(client_name="testuser", client_uid=42)
        self._post()
        self.client.force_login(self.admin)
        base = "/admin/client_data_collector/clientanalytic/"
        known = self.client.get(f"{base}?identified=yes").context["cl"].result_list
        anon = self.client.get(f"{base}?identified=no").context["cl"].result_list
        self.assertEqual(len(known), 1)
        self.assertEqual(len(anon), 1)


class AuditNullsTests(TestCase):
    """The audit is what turns "is this column dead?" into a one-command answer."""

    def test_a_column_nothing_writes_is_reported_as_always_empty(self):
        from .management.commands.audit_nulls import audit

        ClientAnalytic.objects.create(
            date=timezone.now(),
            click_type=ClickType.objects.get_or_create(click_type="EXTENSION_ICON")[0],
        )
        findings = {
            (f["model"], f["field"]): f
            for f in audit(("client_data_collector",))
        }
        key = ("client_data_collector.ClientAnalytic", "client_name")
        self.assertIn(key, findings)
        self.assertEqual(findings[key]["verdict"], "always empty")

    def test_a_populated_column_is_not_reported(self):
        from .management.commands.audit_nulls import audit

        click_type = ClickType.objects.get_or_create(click_type="EXTENSION_ICON")[0]
        for name in ("testuser", "someoneelse"):
            ClientAnalytic.objects.create(
                date=timezone.now(), client_name=name, click_type=click_type)
        reported = {
            f["field"] for f in audit(("client_data_collector",))
            if f["model"] == "client_data_collector.ClientAnalytic"
        }
        self.assertNotIn("client_name", reported)
