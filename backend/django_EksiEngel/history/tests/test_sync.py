import uuid
from datetime import timedelta
from unittest import mock

from django.db import connection
from django.test.utils import CaptureQueriesContext
from django.utils import timezone
from rest_framework.test import APIClient

from history import constants as c
from history import sync
from history.management.commands.history_rebuild import differences
from history.models import (
    Account,
    DailyActor,
    DailyTally,
    Device,
    Event,
    PairingCode,
    RelationState,
    Task,
)

from .helpers import LedgerTestCase, account, event
from history.ingest import ingest

API_KEY = "testkey"
NOW_MS = lambda: int(timezone.now().timestamp() * 1000)  # noqa: E731


class Client:
    """One enrolled device speaking the sync protocol."""

    def __init__(self, test, client="EXTENSION", join_key=None, label="laptop"):
        self.http = APIClient()
        body = {"client": client, "label": label, "version": "0.6.0"}
        if join_key:
            body["join_key"] = join_key
        response = self.http.post(
            "/api/sync/enroll/", body, format="json", HTTP_X_API_KEY=API_KEY
        )
        test.assertEqual(response.status_code, 201, response.content)
        self.token = response.json()["device_token"]
        self.join_key = response.json()["join_key"] or join_key
        self.http.credentials(HTTP_AUTHORIZATION=f"Device {self.token}")

    @property
    def device(self):
        return sync.device_for_token(self.token)

    def push(self, tasks=(), events=(), **extra):
        return self.http.post(
            "/api/sync/push/",
            {"tasks": list(tasks), "events": list(events), **extra},
            format="json",
        )

    def pull(self, cursor=0, limit=500):
        return self.http.get(f"/api/sync/pull/?cursor={cursor}&limit={limit}")

    def post(self, path, body=None):
        return self.http.post(path, body or {}, format="json")


def task(task_id=None, status="QUEUED", epoch=0, **kw):
    return {
        "id": str(task_id or uuid.uuid4()),
        "epoch": epoch,
        "actor_eksi_id": 1,
        "ban_source": 4,
        "ban_mode": 1,
        "status": status,
        "description": "Listeden Engelle",
        **kw,
    }


def wire_event(target, task_id=None, **kw):
    return {
        "id": str(uuid.uuid4()),
        "task_id": task_id,
        "kind": "BLOCK",
        "direction": "ADD",
        "result": "OK",
        "actor_eksi_id": 1,
        "actor_nick": "me",
        "target_eksi_id": target,
        "target_nick": f"t{target}",
        "occurred_at": NOW_MS(),
        **kw,
    }


class EnrollTests(LedgerTestCase):
    def test_enroll_requires_the_api_key(self):
        response = APIClient().post(
            "/api/sync/enroll/", {"client": "EXTENSION"}, format="json"
        )
        self.assertIn(response.status_code, (401, 403))

    def test_token_never_stored_in_plaintext(self):
        laptop = Client(self)
        self.assertFalse(Device.objects.filter(token_hash=laptop.token).exists())
        for column in ("token_hash", "label"):
            self.assertNotIn(
                laptop.token, list(Device.objects.values_list(column, flat=True))
            )

    def test_unknown_token_is_refused(self):
        http = APIClient()
        http.credentials(HTTP_AUTHORIZATION="Device nope")
        self.assertEqual(http.get("/api/sync/pull/").status_code, 401)

    def test_second_install_on_synced_profile_joins_without_code(self):
        first = Client(self)
        second = Client(self, join_key=first.join_key, label="desktop")
        self.assertEqual(first.device.account_id, second.device.account_id)
        self.assertNotEqual(first.token, second.token)
        self.assertEqual(Device.objects.count(), 2)


class PushPullTests(LedgerTestCase):
    def setUp(self):
        super().setUp()
        self.laptop = Client(self)
        self.desktop = Client(self, join_key=self.laptop.join_key, label="desktop")

    def test_tasks_reach_the_other_device(self):
        t = task(status="RUNNING", planned=10, performed=3)
        response = self.laptop.push([t], [wire_event(5, t["id"])])
        self.assertEqual(response.status_code, 200, response.content)
        body = response.json()
        self.assertEqual(body["tasks"][0]["result"], "ok")
        self.assertEqual(len(body["events_accepted"]), 1)
        pulled = self.desktop.pull().json()
        self.assertEqual([x["id"] for x in pulled["tasks"]], [t["id"]])
        self.assertFalse(pulled["tasks"][0]["is_mine"])
        self.assertEqual(pulled["tasks"][0]["owner"]["label"], "laptop")
        self.assertTrue(self.laptop.pull().json()["tasks"][0]["is_mine"])

    def test_cursor_pages_through_everything(self):
        for _ in range(3):
            self.laptop.push([task() for _ in range(5)])
        seen, cursor, pages = [], 0, 0
        while True:
            body = self.desktop.pull(cursor, limit=4).json()
            seen += [t["id"] for t in body["tasks"]]
            cursor, pages = body["next_cursor"], pages + 1
            if not body["has_more"]:
                break
        self.assertEqual(len(set(seen)), 15)
        self.assertEqual(pages, 4)
        self.assertEqual(self.desktop.pull(cursor).json()["tasks"], [])

    def test_device_clock_does_not_decide_what_is_new(self):
        day_behind = NOW_MS() - 86_400_000
        self.laptop.push([task(created_at=day_behind)])
        self.assertEqual(len(self.desktop.pull().json()["tasks"]), 1)

    def test_stale_upsert_and_counters(self):
        t = task(status="RUNNING", performed=40)
        self.laptop.push([t])
        self.laptop.push([{**t, "status": "DONE", "performed": 40}])
        self.laptop.push([{**t, "status": "RUNNING", "performed": 35}])
        stored = Task.objects.get()
        self.assertEqual((stored.status, stored.performed), (c.DONE, 40))

    def test_terminal_status_never_changes(self):
        t = task(status="DONE")
        self.laptop.push([t])
        self.laptop.push([{**t, "status": "FAILED"}])
        self.assertEqual(Task.objects.get().status, c.DONE)

    def test_non_owner_cannot_write(self):
        t = task()
        self.laptop.push([t])
        result = self.desktop.push([{**t, "status": "RUNNING"}]).json()["tasks"][0]
        self.assertEqual(result["result"], "conflict")
        self.assertEqual(Task.objects.get().status, c.QUEUED)

    def test_legacy_result_is_refused(self):
        self.assertEqual(
            self.laptop.push([], [wire_event(5, result="LEGACY")]).status_code, 400
        )

    def test_batch_limits(self):
        events = [wire_event(i) for i in range(1, 502)]
        self.assertEqual(self.laptop.push([], events).status_code, 400)

    def test_future_clock_is_clamped(self):
        far = NOW_MS() + 10 * 86_400_000
        self.laptop.push([], [wire_event(5, occurred_at=far)])
        self.assertLess(
            Event.objects.get().occurred_at, timezone.now() + timedelta(minutes=1)
        )

    def test_impossible_past_clock_is_stamped_not_refused(self):
        t = task(created_at=10)
        response = self.laptop.push([t], [wire_event(5, occurred_at=10)])
        self.assertEqual(response.status_code, 200, response.content)
        self.assertGreater(Event.objects.get().occurred_at.year, 2020)
        self.assertGreater(Task.objects.get().created_at.year, 2020)

    def test_gap_marker_is_recorded(self):
        self.laptop.push([], [], gap=7)
        self.assertEqual(self.laptop.device.dropped_events, 7)

    def test_nicks_are_recorded(self):
        self.laptop.push([], [], nicks=[{"eksi_id": 9, "nick": "dokuz"}])
        from history.stats import latest_nicks

        self.assertEqual(latest_nicks([9]), {9: "dokuz"})

    def test_task_writes_lock_the_account_row(self):
        with CaptureQueriesContext(connection) as queries:
            self.laptop.push([task()])
        locked = [q["sql"] for q in queries if "FOR UPDATE" in q["sql"]]
        self.assertTrue(any('FROM "history_account"' in sql for sql in locked), locked)

    def test_own_history_is_the_account_only(self):
        self.laptop.push([], [wire_event(5)])
        stranger = Client(self, label="stranger")
        stranger.push([], [wire_event(6)])
        mine = self.desktop.http.get("/api/sync/history/").json()["results"]
        self.assertEqual([e["target_eksi_id"] for e in mine], [5])
        self.assertEqual(mine[0]["target_nick"], "t5")


class OwnershipTests(LedgerTestCase):
    def setUp(self):
        super().setUp()
        self.laptop = Client(self)
        self.phone = Client(
            self, client="ANDROID", join_key=self.laptop.join_key, label="phone"
        )

    def test_cancel_from_the_phone(self):
        t = task(status="RUNNING")
        self.laptop.push([t])
        response = self.phone.post(f"/api/sync/tasks/{t['id']}/cancel/")
        self.assertEqual(response.status_code, 200)
        pulled = self.laptop.pull().json()["tasks"][0]
        self.assertTrue(pulled["cancel_requested"])
        self.laptop.push([{**t, "status": "CANCELLED"}])
        self.assertEqual(Task.objects.get().status, c.CANCELLED)

    def test_takeover_refused_while_owner_is_fresh(self):
        t = task(status="RUNNING")
        self.laptop.push([t])
        response = self.phone.post(f"/api/sync/tasks/{t['id']}/takeover/")
        self.assertEqual(response.status_code, 409)
        self.assertEqual(Task.objects.get().owner_device_id, self.laptop.device.pk)

    def test_takeover_after_owner_went_quiet(self):
        t = task(status="RUNNING", performed=12)
        self.laptop.push([t])
        Device.objects.filter(pk=self.laptop.device.pk).update(
            last_seen_at=timezone.now() - timedelta(minutes=20)
        )
        response = self.phone.post(f"/api/sync/tasks/{t['id']}/takeover/")
        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(response.json()["epoch"], 1)
        stored = Task.objects.get()
        self.assertEqual(stored.owner_device_id, self.phone.device.pk)
        self.assertEqual((stored.status, stored.performed), (c.QUEUED, 0))
        # The laptop wakes up and reports progress under the old epoch.
        result = self.laptop.push([{**t, "performed": 13}]).json()["tasks"][0]
        self.assertEqual(result["result"], "conflict")
        self.assertEqual(Task.objects.get().performed, 0)

    def test_takeover_of_a_queued_task(self):
        t = task()
        self.laptop.push([t])
        self.assertEqual(
            self.phone.post(f"/api/sync/tasks/{t['id']}/takeover/").status_code, 200
        )

    def test_cross_account_is_not_found(self):
        t = task()
        self.laptop.push([t])
        stranger = Client(self, label="stranger")
        self.assertEqual(
            stranger.post(f"/api/sync/tasks/{t['id']}/cancel/").status_code, 404
        )
        self.assertEqual(
            stranger.post(f"/api/sync/tasks/{t['id']}/takeover/").status_code, 404
        )
        # Reusing another account's task id does not touch it either.
        result = stranger.push([{**t, "status": "DONE"}]).json()["tasks"][0]
        self.assertEqual(result["result"], "not_found")
        self.assertEqual(Task.objects.get().status, c.QUEUED)


class PairingTests(LedgerTestCase):
    def setUp(self):
        super().setUp()
        self.laptop = Client(self)
        self.phone = Client(self, client="ANDROID", label="phone")

    def code(self):
        response = self.laptop.post("/api/sync/pair/start/")
        self.assertEqual(response.status_code, 201)
        return response.json()["code"]

    def test_phone_joins_laptop(self):
        t = task()
        self.laptop.push([t])
        self.phone.push([], [wire_event(77)])
        old_account = self.phone.device.account_id
        response = self.phone.post("/api/sync/pair/claim/", {"code": self.code()})
        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(self.phone.device.account_id, self.laptop.device.account_id)
        self.assertFalse(Account.objects.filter(pk=old_account).exists())
        self.assertEqual(
            [x["id"] for x in self.phone.pull().json()["tasks"]], [t["id"]]
        )
        # The phone's earlier history moved with it.
        self.assertEqual(Event.objects.get().account_id, self.laptop.device.account_id)
        with connection.cursor() as cursor:
            self.assertEqual(
                differences(cursor), {k: (0, 0) for k in differences(cursor)}
            )

    def test_code_is_case_and_dash_insensitive(self):
        code = self.code().lower().replace("-", "")
        self.assertEqual(
            self.phone.post("/api/sync/pair/claim/", {"code": code}).status_code, 200
        )

    def test_expired_code(self):
        code = self.code()
        PairingCode.objects.update(expires_at=timezone.now() - timedelta(seconds=1))
        response = self.phone.post("/api/sync/pair/claim/", {"code": code})
        self.assertEqual(response.status_code, 400)
        self.assertNotEqual(self.phone.device.account_id, self.laptop.device.account_id)

    def test_code_is_single_use(self):
        code = self.code()
        self.phone.post("/api/sync/pair/claim/", {"code": code})
        third = Client(self, label="third")
        self.assertEqual(
            third.post("/api/sync/pair/claim/", {"code": code}).status_code, 400
        )

    def test_relation_rows_merge(self):
        self.laptop.push([], [wire_event(5, occurred_at=NOW_MS() - 1000)])
        self.phone.push([], [wire_event(5)])
        self.phone.post("/api/sync/pair/claim/", {"code": self.code()})
        row = RelationState.objects.get()
        self.assertEqual(row.add_count, 2)
        self.assertEqual(DailyActor.objects.count(), 1)

    def test_claims_are_throttled_per_address(self):
        guessers = [Client(self, label=f"guesser{i}") for i in range(3)]
        with mock.patch(
            "history.throttling.IPScopedRateThrottle.get_rate", return_value="2/hour"
        ):
            statuses = [
                g.post("/api/sync/pair/claim/", {"code": "AAAA-AAAA"}).status_code
                for g in guessers
            ]
        self.assertEqual(statuses, [400, 400, 429])


class DeletionTests(LedgerTestCase):
    def test_delete_leaves_nothing(self):
        laptop = Client(self)
        laptop.push([task()], [wire_event(i) for i in range(1, 6)])
        other = Client(self, label="other")
        other.push([], [wire_event(9)])
        account_id = laptop.device.account_id
        self.assertEqual(laptop.http.delete("/api/sync/account/").status_code, 204)
        for model in (Device, Task, Event, RelationState, DailyActor):
            self.assertFalse(
                model.objects.filter(account_id=account_id).exists(), model
            )
        self.assertFalse(Account.objects.filter(pk=account_id).exists())
        self.assertEqual(sum(DailyTally.objects.values_list("n", flat=True)), 1)
        with connection.cursor() as cursor:
            diff = differences(cursor)
        self.assertEqual(diff, {k: (0, 0) for k in diff})
        self.assertEqual(laptop.pull().status_code, 401)

    def test_run_report_account_survives(self):
        legacy = Account.objects.create(kind=c.ACCOUNT_LEGACY, legacy_actor_eksi_id=1)
        ingest([event(legacy, 1, 5, result=c.LEGACY)])
        laptop = Client(self)
        laptop.push([], [wire_event(6)])
        laptop.http.delete("/api/sync/account/")
        self.assertEqual(Event.objects.filter(account=legacy).count(), 1)

    def test_tally_day_with_other_accounts_keeps_their_count(self):
        a = account()
        ingest([event(a, 1, 5), event(a, 1, 6)])
        b = account()
        ingest([event(b, 2, 5)])
        sync.delete_account(a.pk)
        self.assertEqual(DailyTally.objects.get().n, 1)


class CorsTests(LedgerTestCase):
    def test_preflight_allows_the_device_header(self):
        response = APIClient().options(
            "/api/sync/push/",
            HTTP_ORIGIN="chrome-extension://abcdef",
            HTTP_ACCESS_CONTROL_REQUEST_METHOD="POST",
            HTTP_ACCESS_CONTROL_REQUEST_HEADERS="authorization, content-type",
        )
        allowed = response.headers.get("access-control-allow-headers", "").lower()
        self.assertIn("authorization", allowed)
        self.assertIn("content-type", allowed)
