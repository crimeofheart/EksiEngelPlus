from django.conf import settings
from django.contrib.auth.models import User
from django.test import TestCase

from client_data_collector.models import ClientAnalytic
from django_EksiEngel.admin import build_dashboard_context

from .models import Action, BanMode, BanSource, EksiSozlukUser, LogLevel


class DashboardTests(TestCase):
    """The dashboard reports on telemetry, so its arithmetic is the thing worth pinning."""

    @classmethod
    def setUpTestData(cls):
        cls.admin = User.objects.create_superuser("tester", "t@example.com", "pw")
        cls.ban = BanMode.objects.get(ban_mode="BAN")
        cls.source = BanSource.objects.get(ban_source="SINGLE")
        cls.log_level = LogLevel.objects.get(log_level="INFO")

        cls.actor = EksiSozlukUser.objects.create(
            eksisozluk_name="actor", eksisozluk_id=1, is_eksiengel_user=True)
        cls.target = EksiSozlukUser.objects.create(
            eksisozluk_name="target", eksisozluk_id=2, is_eksiengel_user=False)

        # clean: every step landed
        cls.clean = cls._action(cls, planned=5, performed=5, successful=5, early=False)
        # failed: ran to completion but two steps did not land
        cls.failed = cls._action(cls, planned=5, performed=5, successful=3, early=False)
        # early stopped: user pulled the plug, not a failure
        cls.early = cls._action(cls, planned=5, performed=2, successful=2, early=True)

    def _action(self, planned, performed, successful, early):
        action = Action.objects.create(
            eksi_engel_user=self.actor, version="0.1", user_agent="ua",
            ban_source=self.source, ban_mode=self.ban, author_list_size=1,
            planned_action=planned, performed_action=performed,
            successful_action=successful, is_early_stopped=early,
            log_level=self.log_level,
        )
        action.author_list.add(self.target)
        return action

    def test_outcome_counts_partition_every_action(self):
        ctx = build_dashboard_context()
        tiles = {t["label"]: t["value"] for t in ctx["tiles"]}
        self.assertEqual(tiles["Actions"], 3)
        self.assertEqual(tiles["Failed actions"], 1)
        self.assertEqual(tiles["Early stopped"], 1)

    def test_rates_use_performed_and_planned_as_denominators(self):
        tiles = {t["label"]: t["value"] for t in build_dashboard_context()["tiles"]}
        # successful 5+3+2=10 of performed 5+5+2=12
        self.assertEqual(tiles["Success rate"], "83.3%")
        # performed 12 of planned 5+5+5=15
        self.assertEqual(tiles["Completion rate"], "80.0%")

    def test_user_tile_counts_extension_users_not_block_targets(self):
        tiles = {t["label"]: t["value"] for t in build_dashboard_context()["tiles"]}
        self.assertEqual(tiles["Extension users"], 1)  # not 2

    def test_dashboard_renders_for_staff(self):
        self.client.force_login(self.admin)
        response = self.client.get("/admin/")
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, "Success rate")

    def test_index_hides_duplicated_lookup_tables(self):
        self.client.force_login(self.admin)
        body = self.client.get("/admin/").content.decode()
        self.assertNotIn("Ban sources", body)
        self.assertNotIn("Log levels", body)
        self.assertIn("Actions", body)


class ActionAdminTests(TestCase):
    fixtures = []

    @classmethod
    def setUpTestData(cls):
        cls.admin = User.objects.create_superuser("tester2", "t2@example.com", "pw")

    def test_outcome_filter_splits_actions_without_overlap(self):
        self.client.force_login(self.admin)
        for value in ("failed", "clean", "early_stopped"):
            response = self.client.get(f"/admin/api/action/?outcome={value}")
            self.assertEqual(response.status_code, 200, value)

    def test_action_is_read_only(self):
        self.client.force_login(self.admin)
        self.assertEqual(self.client.get("/admin/api/action/add/").status_code, 403)


class ExtensionWritePathTests(TestCase):
    """These URLs are baked into shipped extension builds and must keep working."""

    def test_analytics_post_still_accepted(self):
        response = self.client.post(
            "/admin/api/client_data/analytics",
            data={"click_type": "EXTENSION_ICON"},
            content_type="application/json",
            HTTP_X_API_KEY=settings.SHARED_API_KEY,
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(ClientAnalytic.objects.count(), 1)

    def test_where_is_eksisozluk_still_served(self):
        self.assertEqual(self.client.get("/api/where_is_eksisozluk/").status_code, 200)

    def test_analytics_get_is_unreachable_from_a_browser(self):
        # SharedAPIKeyAuthentication comes first and raises when X-API-Key is absent, so
        # DRF never reaches session auth: a logged-in staff user browsing here has always
        # got a 403, which is why the page this GET used to render was never viewable.
        admin = User.objects.create_superuser("tester3", "t3@example.com", "pw")
        self.client.force_login(admin)
        response = self.client.get("/admin/api/client_data/analytics")
        self.assertEqual(response.status_code, 403)
