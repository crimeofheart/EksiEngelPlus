import time
from datetime import timedelta

from django.contrib.auth.models import User
from django.db import connection
from django.test.utils import CaptureQueriesContext
from django.utils import timezone
from rest_framework.test import APIClient

from api.models import EksiSozlukUser
from django_EksiEngel.admin import build_dashboard_context
from history import constants as c
from history import stats, sync
from history.ingest import ingest
from history.models import AdminAccess

from .helpers import LedgerTestCase, account, event


def now_minus(days):
    return timezone.now() - timedelta(days=days)


class AdminClientMixin:
    def setUp(self):
        super().setUp()
        self.admin = User.objects.create_superuser("admin", "a@example.com", "pw")
        self.client = APIClient()
        self.client.force_authenticate(self.admin)


class AccessTests(LedgerTestCase):
    def test_device_token_is_not_an_admin(self):
        device, token, _ = sync.enroll("EXTENSION")
        http = APIClient()
        http.credentials(HTTP_AUTHORIZATION=f"Device {token}")
        for path in (
            "/api/stats/top/?kind=BLOCK",
            "/api/stats/users/1/",
            "/api/most_banned/",
            "/api/user_stat/",
        ):
            with self.subTest(path=path):
                self.assertIn(http.get(path).status_code, (401, 403))

    def test_api_key_is_not_an_admin(self):
        response = APIClient().get(
            "/api/stats/top/?kind=BLOCK", HTTP_X_API_KEY="testkey"
        )
        self.assertIn(response.status_code, (401, 403))


class RankingTests(AdminClientMixin, LedgerTestCase):
    def top(self, **params):
        query = "&".join(f"{k}={v}" for k, v in {"kind": "BLOCK", **params}.items())
        response = self.client.get(f"/api/stats/top/?{query}")
        self.assertEqual(response.status_code, 200, response.content)
        return {r["target_eksi_id"]: r["actors"] for r in response.json()["results"]}

    def test_unblock_lowers_the_current_rank(self):
        accounts = [account() for _ in range(10)]
        ingest([event(a, i + 1, 123, when=1) for i, a in enumerate(accounts)])
        ingest(
            [
                event(a, i + 1, 123, direction=c.REMOVE, when=2)
                for i, a in enumerate(accounts[:4])
            ]
        )
        self.assertEqual(self.top(scope="current"), {123: 6})
        self.assertEqual(self.top(scope="all"), {123: 10})

    def test_repeat_blocks_count_once(self):
        a = account()
        ingest([event(a, 1, 123, when=i) for i in range(5)])
        for scope in ("current", "all", "window"):
            with self.subTest(scope=scope):
                self.assertEqual(self.top(scope=scope, days=100000), {123: 1})

    def test_window(self):
        a, b = account(), account()
        ingest(
            [event(a, 1, 123, when=now_minus(40)), event(a, 1, 123, when=now_minus(5))]
        )
        ingest([event(b, 2, 123, when=now_minus(40))])
        self.assertEqual(self.top(scope="window", days=30), {123: 1})
        self.assertEqual(self.top(scope="window", days=60), {123: 2})

    def test_include_legacy(self):
        ingest([event(account(), 1, 5, result=c.LEGACY)])
        ingest([event(account(), 2, 6)])
        self.assertEqual(self.top(), {5: 1, 6: 1})
        self.assertEqual(self.top(include_legacy="false"), {6: 1})

    def test_bad_params(self):
        for query in ("kind=NOPE", "kind=BLOCK&scope=ever", "kind=BLOCK&limit=x", ""):
            with self.subTest(query=query):
                self.assertEqual(
                    self.client.get(f"/api/stats/top/?{query}").status_code, 400
                )

    def test_dashboard_and_json_agree(self):
        accounts = [account() for _ in range(25)]
        ingest(
            [
                event(a, i + 1, 100 + (i * j) % 30, when=j)
                for i, a in enumerate(accounts)
                for j in range(4)
            ]
        )
        context = build_dashboard_context()
        panel = next(
            p for p in context["breakdowns"] if p["title"].startswith("Most blocked")
        )
        dashboard = [(row["label"], row["count"]) for row in panel["rows"]]
        response = self.client.get(
            "/api/stats/top/?kind=BLOCK&scope=current&limit=20"
        ).json()
        api = [
            (r["nick"] or str(r["target_eksi_id"]), r["actors"])
            for r in response["results"]
        ]
        self.assertEqual(dashboard, api)


class OldRouteTests(AdminClientMixin, LedgerTestCase):
    def test_shape_unchanged(self):
        ingest([event(account(), 1, 5)], nicks=[(5, "bes", timezone.now())])
        for path, field in (
            ("/api/most_banned/", "banned_by_count"),
            ("/api/most_banned_unique/", "banned_by_unique_count"),
        ):
            with self.subTest(path=path):
                rows = self.client.get(path).json()
                self.assertEqual(
                    rows, [{"eksisozluk_name": "bes", "eksisozluk_id": 5, field: 1}]
                )

    def test_user_stat_runs_a_fixed_number_of_queries(self):
        a = account()
        for i in range(1, 31):
            EksiSozlukUser.objects.create(
                eksisozluk_name=f"u{i}", eksisozluk_id=i, is_eksiengel_user=False
            )
        ingest([event(a, 1, t) for t in range(2, 31)])

        def count(page_size):
            with CaptureQueriesContext(connection) as queries:
                response = self.client.get(f"/api/user_stat/?page_size={page_size}")
            self.assertEqual(response.status_code, 200)
            return len(queries), response.json()

        few, _ = count(5)
        many, body = count(30)
        self.assertEqual(few, many)
        row = next(r for r in body["results"] if r["eksisozluk_id"] == 1)
        self.assertEqual((row["ban_count"], row["ban_unique_count"]), (29, 29))
        row = next(r for r in body["results"] if r["eksisozluk_id"] == 2)
        self.assertEqual(
            (row["banned_by_count"], row["banned_by_unique_count"]), (1, 1)
        )


class PerUserTests(AdminClientMixin, LedgerTestCase):
    def test_who_did_x_block(self):
        a = account()
        ingest([event(a, 42, t, when=t) for t in (1, 2, 3)])
        ingest([event(a, 42, 3, direction=c.REMOVE, when=9)])
        response = self.client.get(
            "/api/stats/users/42/relations/?role=actor&kind=BLOCK&active=true"
        )
        self.assertEqual(response.status_code, 200)
        rows = response.json()["results"]
        self.assertEqual(sorted(r["eksi_id"] for r in rows), [1, 2])
        self.assertTrue(all(r["first_added_at"] for r in rows))

    def test_who_blocks_x(self):
        ingest([event(account(), actor, 42) for actor in (1, 2)])
        rows = self.client.get("/api/stats/users/42/relations/?role=target&active=true")
        self.assertEqual(sorted(r["eksi_id"] for r in rows.json()["results"]), [1, 2])

    def test_lookup_leaves_a_trace(self):
        self.client.get("/api/stats/users/42/")
        self.client.get("/api/stats/users/42/events/?role=target")
        log = list(AdminAccess.objects.values_list("admin_username", "eksi_id"))
        self.assertEqual(log, [("admin", 42), ("admin", 42)])

    def test_refused_requests_leave_no_trace(self):
        APIClient().get("/api/stats/users/42/")
        self.assertFalse(AdminAccess.objects.exists())

    def test_summary(self):
        a = account()
        ingest(
            [event(a, 42, 5), event(a, 42, 6, kind=c.MUTE), event(a, 7, 42)],
            nicks=[(42, "kirkiki", timezone.now())],
        )
        body = self.client.get("/api/stats/users/42/").json()
        self.assertEqual(body["nicks"][0]["nick"], "kirkiki")
        self.assertEqual(body["kinds"]["BLOCK"]["active_as_actor"], 1)
        self.assertEqual(body["kinds"]["MUTE"]["active_as_actor"], 1)
        self.assertEqual(body["kinds"]["BLOCK"]["actors_targeting_now"], 1)
        self.assertEqual(body["event_totals"]["actor"]["BLOCK"]["ADD"]["OK"], 1)

    def test_deep_history_pages_by_id(self):
        a = account()
        ingest([event(a, 42, t, when=t % 1000) for t in range(1, 40_001)])
        before, seen, slowest = None, 0, 0.0
        for _ in range(3):
            path = "/api/stats/users/42/events/?role=actor&limit=500"
            if before:
                path += f"&before={before}"
            started = time.monotonic()
            body = self.client.get(path).json()
            slowest = max(slowest, time.monotonic() - started)
            seen += len(body["results"])
            before = body["next_before"]
        self.assertEqual(seen, 1500)
        self.assertLess(slowest, 1.0)

    def test_tasks_link_their_run_report(self):
        from api.models import Action, BanMode, BanSource, LogLevel

        device, _, _ = sync.enroll("EXTENSION")
        task_id = "6e0f3c2a-1111-4c55-9a52-0f3e2a4a1c7e"
        sync.upsert_tasks(
            device,
            [
                {
                    "id": task_id,
                    "actor_eksi_id": 42,
                    "ban_source": 4,
                    "ban_mode": 1,
                    "status": c.DONE,
                }
            ],
        )
        user = EksiSozlukUser.objects.create(
            eksisozluk_name="x", eksisozluk_id=42, is_eksiengel_user=True
        )
        report = Action.objects.create(
            eksi_engel_user=user,
            version="x",
            user_agent="x",
            ban_source=BanSource.objects.get(pk=4),
            ban_mode=BanMode.objects.get(pk=1),
            author_list_size=0,
            planned_action=0,
            performed_action=0,
            successful_action=0,
            is_early_stopped=False,
            log_level=LogLevel.objects.get(pk=1),
            task_id=task_id,
        )
        rows = self.client.get("/api/stats/users/42/tasks/").json()
        self.assertEqual(rows[0]["action_id"], report.pk)


class TimeseriesTests(AdminClientMixin, LedgerTestCase):
    def test_days_and_actors(self):
        a, b = account(), account()
        ingest(
            [
                event(a, 1, 5, when=now_minus(1)),
                event(b, 1, 6, when=now_minus(1)),
                event(b, 2, 7, when=now_minus(1), result=c.FAIL),
            ]
        )
        body = self.client.get("/api/stats/timeseries/?kind=BLOCK&days=7").json()
        self.assertEqual(len(body["results"]), 1)
        day = body["results"][0]
        self.assertEqual(day["actors"], 2)
        self.assertEqual(day["events"]["ADD"], {"OK": 2, "FAIL": 1})


class QueryPlanTests(LedgerTestCase):
    """Global stats must never touch history_event, however big it gets."""

    def test_no_global_stat_reads_events(self):
        a = account()
        ingest(
            [
                event(a, actor, actor * 7 % 500 + 1, when=actor % 90)
                for actor in range(1, 3001)
            ]
        )
        calls = [
            lambda: stats.top(c.BLOCK, "current"),
            lambda: stats.top(c.BLOCK, "all"),
            lambda: stats.top(c.BLOCK, "window", days=30),
            lambda: stats.top(c.BLOCK, "all", include_legacy=False),
            lambda: stats.timeseries(c.BLOCK, 90),
            lambda: stats.timeseries(None, 90),
            lambda: stats.legacy_ranking(True),
            lambda: stats.legacy_ranking(False),
        ]
        for call in calls:
            with CaptureQueriesContext(connection) as queries:
                call()
            for q in queries:
                with self.subTest(sql=q["sql"][:80]):
                    self.assertNotIn("history_event", q["sql"])
                    with connection.cursor() as cursor:
                        cursor.execute("EXPLAIN " + q["sql"])
                        plan = "\n".join(row[0] for row in cursor.fetchall())
                    self.assertNotIn("history_event", plan)
            from django.core.cache import cache

            cache.clear()
