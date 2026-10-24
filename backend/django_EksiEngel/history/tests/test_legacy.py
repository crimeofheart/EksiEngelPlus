import io
import json
import time
import uuid

from django.contrib.auth.models import User
from django.core.management import call_command
from django.db.models import Count, Q
from rest_framework.test import APIClient

from api.models import (
    Action,
    ActionConfig,
    BanMode,
    BanSource,
    EksiSozlukUser,
    LogLevel,
    TargetType,
)
from history import constants as c
from history.legacy import relations_of
from history.models import Account, Event, RelationState

from .helpers import LedgerTestCase

API_KEY = "testkey"


def report(actor=(1, "actor_1"), targets=(), ban_source=4, ban_mode=1, **extra):
    action = {
        "eksi_engel_user": {"eksisozluk_name": actor[1], "eksisozluk_id": actor[0]},
        "author_list": [{"eksisozluk_id": i, "eksisozluk_name": n} for i, n in targets],
        "version": "0.6.0",
        "user_agent": "test",
        "ban_source": ban_source,
        "ban_mode": ban_mode,
        "author_list_size": len(targets),
        "planned_action": len(targets),
        "performed_action": len(targets),
        "successful_action": len(targets),
        "is_early_stopped": False,
        "log_level": 1,
        "log": None,
    }
    action.update(extra.pop("action", {}))
    return {
        "action": action,
        "action_config": {"enable_mute": False, "enable_title_ban": False, **extra},
    }


class RunReportTests(LedgerTestCase):
    def setUp(self):
        super().setUp()
        self.client = APIClient()

    def post(self, body):
        return self.client.post(
            "/api/action/",
            json.dumps(body),
            content_type="application/json",
            HTTP_X_API_KEY=API_KEY,
        )

    def test_old_client_keeps_feeding_rankings(self):
        targets = [(i, f"t{i}") for i in range(1, 51)]
        response = self.post(report(targets=targets))
        self.assertEqual(response.status_code, 201, response.content)
        self.assertEqual(
            Event.objects.filter(result=c.LEGACY, kind=c.BLOCK).count(), 50
        )
        self.assertEqual(RelationState.objects.filter(active=True).count(), 50)
        self.assertEqual(
            Account.objects.get(kind=c.ACCOUNT_LEGACY).legacy_actor_eksi_id, 1
        )

    def test_empty_author_list_writes_nothing(self):
        # A client with history sync on: its targets came through sync instead.
        task_id = str(uuid.uuid4())
        response = self.post(
            report(targets=(), action={"task_id": task_id, "author_list_size": 300})
        )
        self.assertEqual(response.status_code, 201, response.content)
        self.assertFalse(Event.objects.exists())
        self.assertEqual(str(Action.objects.get().task_id), task_id)

    def test_report_without_task_id(self):
        self.assertEqual(self.post(report(targets=[(5, "x")])).status_code, 201)
        self.assertIsNone(Action.objects.get().task_id)

    def test_list_undo_lifts_block_and_mute(self):
        self.post(report(targets=[(5, "x")], ban_mode=2, enable_mute=True))
        kinds = set(Event.objects.values_list("kind", "direction"))
        self.assertEqual(kinds, {(c.BLOCK, c.REMOVE), (c.MUTE, c.REMOVE)})

    def test_named_list_action(self):
        self.post(report(targets=[(5, "x")], action={"bulk_action": "TAKIP_ET"}))
        self.assertEqual(
            list(Event.objects.values_list("kind", "direction")), [(c.FOLLOW, c.ADD)]
        )

    def test_ten_thousand_target_report(self):
        targets = [(i, f"t{i}") for i in range(1, 10_001)]
        started = time.monotonic()
        response = self.post(report(targets=targets))
        elapsed = time.monotonic() - started
        self.assertEqual(response.status_code, 201)
        self.assertEqual(Event.objects.count(), 10_000)
        # Most of this is the pre-existing per-author M2M loop in
        # WriteActionViewSerializer; the ledger write itself is one statement per table.
        self.assertLess(elapsed, 60)
        started = time.monotonic()
        from history.legacy import ingest_action

        action = Action.objects.get()
        Event.objects.all().delete()
        ingest_action(action)
        self.assertLess(time.monotonic() - started, 2.0)


class MappingTests(LedgerTestCase):
    @classmethod
    def setUpTestData(cls):
        cls.user = EksiSozlukUser.objects.create(
            eksisozluk_name="a", eksisozluk_id=1, is_eksiengel_user=True
        )

    def action(self, source, mode=1, target_type=None, bulk_action=None, config=None):
        action = Action.objects.create(
            eksi_engel_user=self.user,
            version="x",
            user_agent="x",
            ban_source=BanSource.objects.get(pk=source),
            ban_mode=BanMode.objects.get(pk=mode),
            author_list_size=0,
            planned_action=0,
            performed_action=0,
            successful_action=0,
            is_early_stopped=False,
            log_level=LogLevel.objects.get(pk=1),
            target_type=TargetType.objects.get(pk=target_type) if target_type else None,
            bulk_action=bulk_action,
        )
        if config is not None:
            ActionConfig.objects.create(action=action, **config)
            action.refresh_from_db()
        return action

    def test_single_follows_target_type(self):
        self.assertEqual(relations_of(self.action(1, 1, 3)), [(c.MUTE, c.ADD)])
        self.assertEqual(
            relations_of(self.action(1, 2, 2)), [(c.TITLE_BLOCK, c.REMOVE)]
        )
        self.assertIsNone(relations_of(self.action(1, 1, None)))

    def test_audience_restriction_reads_config(self):
        cfg = {"enable_mute": True, "enable_title_ban": True}
        self.assertEqual(
            relations_of(self.action(2, 1, config=cfg)),
            [(c.MUTE, c.ADD), (c.TITLE_BLOCK, c.ADD)],
        )
        self.assertEqual(
            relations_of(self.action(3, 1, 4, config=cfg)), [(c.FOLLOW, c.ADD)]
        )

    def test_unknown_config_is_skipped_not_guessed(self):
        self.assertIsNone(relations_of(self.action(2, 1)))

    def test_followees_and_refresh(self):
        self.assertEqual(relations_of(self.action(15, 2)), [(c.FOLLOW, c.REMOVE)])
        self.assertIsNone(relations_of(self.action(11, 1)))

    def test_android_target_type_is_taken_as_said(self):
        action = self.action(2, 1, 3, config={"enable_mute": False, "enable_title_ban": True})
        action.client = "ANDROID"
        self.assertEqual(relations_of(action), [(c.MUTE, c.ADD)])

    def test_combined_named_action(self):
        self.assertEqual(
            relations_of(self.action(12, 1, bulk_action="ENGEL_KALDIR_VE_TAKIP_ET")),
            [(c.BLOCK, c.REMOVE), (c.FOLLOW, c.ADD)],
        )


def stored_report(actor, targets, mode=1, source=4, when=None):
    """An Action as it sits in the table today, written without the ledger."""
    # Same name and id, same row -- as WriteEksiEngelUserSerializer finds it.
    user, _ = EksiSozlukUser.objects.get_or_create(
        eksisozluk_name=actor[1],
        eksisozluk_id=actor[0],
        defaults={"is_eksiengel_user": True},
    )
    action = Action.objects.create(
        eksi_engel_user=user,
        version="x",
        user_agent="x",
        ban_source=BanSource.objects.get(pk=source),
        ban_mode=BanMode.objects.get(pk=mode),
        author_list_size=len(targets),
        planned_action=0,
        performed_action=0,
        successful_action=0,
        is_early_stopped=False,
        log_level=LogLevel.objects.get(pk=1),
    )
    if when is not None:
        Action.objects.filter(pk=action.pk).update(date=when)
    ActionConfig.objects.create(
        action=action, enable_mute=False, enable_title_ban=False
    )
    for i, name in targets:
        author, _ = EksiSozlukUser.objects.get_or_create(
            eksisozluk_id=i, eksisozluk_name=name, defaults={"is_eksiengel_user": False}
        )
        action.author_list.add(author)
    return action


class BackfillTests(LedgerTestCase):
    def backfill(self):
        out = io.StringIO()
        call_command("history_backfill_legacy", stdout=out)
        return out.getvalue()

    def test_rerun_creates_nothing(self):
        stored_report((1, "a"), [(10, "x"), (11, "y")])
        self.assertIn("2 events created", self.backfill())
        self.assertIn("0 events created", self.backfill())
        self.assertEqual(Event.objects.count(), 2)

    def test_undo_ban_deactivates(self):
        from datetime import datetime, timezone as tz

        stored_report(
            (1, "a"), [(123, "x")], mode=1, when=datetime(2025, 1, 1, tzinfo=tz.utc)
        )
        stored_report(
            (1, "a"), [(123, "x")], mode=2, when=datetime(2025, 2, 1, tzinfo=tz.utc)
        )
        self.backfill()
        row = RelationState.objects.get(actor_eksi_id=1, target_eksi_id=123)
        self.assertFalse(row.active)
        self.assertTrue(row.legacy_only)

    def test_live_path_and_backfill_overlap(self):
        APIClient().post(
            "/api/action/",
            json.dumps(report(targets=[(10, "x"), (11, "y")])),
            content_type="application/json",
            HTTP_X_API_KEY=API_KEY,
        )
        self.assertEqual(Event.objects.count(), 2)
        self.backfill()
        self.assertEqual(Event.objects.count(), 2)

    def test_skipped_sources_are_reported(self):
        stored_report((1, "a"), [(10, "x")], source=11)
        self.assertIn("skipped: ban_source=11 ban_mode=1: 1 targets", self.backfill())

    def test_same_answer_after_migration(self):
        stored_report((1, "a"), [(10, "x"), (11, "y")])
        stored_report((2, "b"), [(10, "x")])
        stored_report((2, "b"), [(10, "x")])  # repeat by one actor: still one
        stored_report((3, "c"), [(12, "z")])
        old = {
            u.eksisozluk_id: u.n
            for u in EksiSozlukUser.objects.annotate(
                n=Count(
                    "author_list_in_action__eksi_engel_user",
                    distinct=True,
                    filter=Q(author_list_in_action__ban_mode__ban_mode="BAN"),
                )
            )
            if u.n
        }
        self.backfill()
        admin = User.objects.create_superuser("admin", "a@example.com", "pw")
        client = APIClient()
        client.force_authenticate(admin)
        new = {
            r["eksisozluk_id"]: r["banned_by_unique_count"]
            for r in client.get("/api/most_banned_unique/").json()
        }
        self.assertEqual(new, old)
        self.assertEqual(new, {10: 2, 11: 1, 12: 1})

    def test_duplicate_user_rows_merge(self):
        stored_report((1, "a"), [(10, "old-nick")])
        stored_report((2, "b"), [(10, "new-nick")])
        self.backfill()
        admin = User.objects.create_superuser("admin", "a@example.com", "pw")
        client = APIClient()
        client.force_authenticate(admin)
        rows = client.get("/api/most_banned_unique/").json()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["banned_by_unique_count"], 2)
