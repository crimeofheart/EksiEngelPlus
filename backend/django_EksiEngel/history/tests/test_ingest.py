import random
import time

from django.db import connection

from history import constants as c
from history import stats
from history.ingest import ingest
from history.management.commands.history_rebuild import differences
from history.models import ActorRelation, DailyActor, DailyTally, Event, NickSeen, RelationState

from .helpers import T0, LedgerTestCase, account, at, block, event, unblock


def state(acct, actor, target, kind=c.BLOCK):
    return RelationState.objects.get(
        account=acct, actor_eksi_id=actor, target_eksi_id=target, kind=kind
    )


class IdempotentIngestTests(LedgerTestCase):
    def setUp(self):
        super().setUp()
        self.acct = account()

    def test_retry_after_lost_response(self):
        batch = [event(self.acct, 1, t) for t in (10, 11, 12)]
        first = ingest(batch)
        second = ingest(batch)
        self.assertEqual(Event.objects.count(), 3)
        self.assertEqual((first.inserted, second.inserted), (3, 0))
        self.assertEqual(set(first.accepted), set(second.accepted))
        self.assertEqual(len(second.accepted), 3)

    def test_partial_batch(self):
        stored = [event(self.acct, 1, t) for t in (10, 11)]
        ingest(stored)
        fresh = [event(self.acct, 1, t) for t in (12, 13, 14)]
        result = ingest(stored + fresh)
        self.assertEqual(result.inserted, 3)
        self.assertEqual(len(result.accepted), 5)
        self.assertEqual(Event.objects.count(), 5)

    def test_duplicate_within_one_batch_is_stored_once(self):
        e = event(self.acct, 1, 10)
        result = ingest([e, e])
        self.assertEqual(result.inserted, 1)
        self.assertEqual(state(self.acct, 1, 10).add_count, 1)


class RelationStateTests(LedgerTestCase):
    def setUp(self):
        super().setUp()
        self.acct = account()

    def test_block_then_unblock(self):
        block(self.acct, 1, 123, when=1)
        unblock(self.acct, 1, 123, when=2)
        row = state(self.acct, 1, 123)
        self.assertFalse(row.active)
        self.assertEqual(row.last_added_at, at(1))
        self.assertEqual(stats.top(c.BLOCK, "current", limit=10), [])

    def test_out_of_order_delivery(self):
        unblock(self.acct, 1, 123, when=2)
        block(self.acct, 1, 123, when=1)
        row = state(self.acct, 1, 123)
        self.assertFalse(row.active)
        self.assertEqual(row.last_added_at, at(1))
        self.assertEqual(row.last_changed_at, at(2))

    def test_out_of_order_within_one_batch(self):
        ingest(
            [
                event(self.acct, 1, 123, direction=c.REMOVE, when=2),
                event(self.acct, 1, 123, when=1),
            ]
        )
        self.assertFalse(state(self.acct, 1, 123).active)

    def test_failure_does_not_flip(self):
        block(self.acct, 1, 123, when=1)
        ingest([event(self.acct, 1, 123, direction=c.REMOVE, result=c.FAIL, when=2)])
        self.assertTrue(state(self.acct, 1, 123).active)

    def test_failure_alone_creates_no_row(self):
        ingest([event(self.acct, 1, 123, result=c.FAIL)])
        self.assertFalse(RelationState.objects.exists())
        self.assertEqual(Event.objects.count(), 1)

    def test_skipped_records_known_state(self):
        ingest([event(self.acct, 1, 123, result=c.SKIPPED)])
        self.assertTrue(state(self.acct, 1, 123).active)

    def test_legacy_only_until_a_real_event(self):
        ingest([event(self.acct, 1, 123, result=c.LEGACY, when=1)])
        self.assertTrue(state(self.acct, 1, 123).legacy_only)
        block(self.acct, 1, 123, when=2)
        row = state(self.acct, 1, 123)
        self.assertFalse(row.legacy_only)
        self.assertEqual(row.add_count, 2)
        self.assertEqual((row.first_added_at, row.last_added_at), (at(1), at(2)))


class NickTests(LedgerTestCase):
    def test_nick_change_shows_latest_and_counts_both(self):
        a, b = account(), account()
        ingest([event(a, 1, 123, when=1)], nicks=[(123, "eski-nick", at(1))])
        ingest([event(b, 2, 123, when=5)], nicks=[(123, "yeni-nick", at(5))])
        rows = stats.top(c.BLOCK, "current", limit=10)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["nick"], "yeni-nick")
        self.assertEqual(rows[0]["actors"], 2)
        self.assertEqual(NickSeen.objects.filter(eksi_id=123).count(), 2)


class DailyAggregateTests(LedgerTestCase):
    def setUp(self):
        super().setUp()
        self.acct = account()

    def test_day_boundary_is_istanbul(self):
        late = T0.replace(hour=23, minute=30)  # 02:30 next day in Istanbul
        block(self.acct, 1, 10, when=late)
        self.assertEqual(DailyTally.objects.get().day.isoformat(), "2026-10-02")
        self.assertEqual(DailyActor.objects.get().day.isoformat(), "2026-10-02")

    def test_duplicate_push_counts_once(self):
        batch = [event(self.acct, 1, t) for t in range(5)]
        ingest(batch)
        ingest(batch)
        self.assertEqual(DailyTally.objects.get().n, 5)

    def test_active_actors_per_day(self):
        other = account()
        block(self.acct, 7, 10)
        block(other, 7, 11)
        block(other, 8, 12)
        series = stats.timeseries(c.BLOCK, days=4000)
        self.assertEqual(series[0]["actors"], 2)
        self.assertEqual(series[0]["total"], 3)


class RebuildEquivalenceTests(LedgerTestCase):
    """Incremental ingest must land exactly where a rebuild from events does."""

    def test_random_orders_and_duplicates(self):
        for seed in range(6):
            with self.subTest(seed=seed):
                RelationState.objects.all().delete()
                ActorRelation.objects.all().delete()
                DailyTally.objects.all().delete()
                DailyActor.objects.all().delete()
                Event.objects.all().delete()
                rng = random.Random(seed)
                accounts = [account() for _ in range(3)]
                events = [
                    event(
                        rng.choice(accounts),
                        rng.randint(1, 4),
                        rng.randint(100, 106),
                        kind=rng.choice([c.BLOCK, c.MUTE]),
                        direction=rng.choice([c.ADD, c.ADD, c.REMOVE]),
                        result=rng.choice([c.OK, c.OK, c.FAIL, c.SKIPPED, c.LEGACY]),
                        when=rng.randint(0, 60 * 24 * 3),
                    )
                    for _ in range(300)
                ]
                pool = events + rng.sample(events, 60)
                rng.shuffle(pool)
                while pool:
                    size = rng.randint(1, 40)
                    ingest(pool[:size])
                    pool = pool[size:]
                with connection.cursor() as cursor:
                    diff = differences(cursor)
                self.assertEqual(diff, {t: (0, 0) for t in diff})


class LargeBatchTests(LedgerTestCase):
    def test_ten_thousand_events_in_one_call(self):
        acct = account()
        batch = [event(acct, 1, t) for t in range(1, 10_001)]
        started = time.monotonic()
        result = ingest(batch)
        elapsed = time.monotonic() - started
        self.assertEqual(result.inserted, 10_000)
        self.assertEqual(RelationState.objects.filter(active=True).count(), 10_000)
        self.assertLess(elapsed, 2.0)
