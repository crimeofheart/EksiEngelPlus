"""The one write path into the ledger.

Sync pushes and /api/action/ run reports both end here, so the two cannot
disagree about what an event does to relation state or to the daily
aggregates. Everything is set-based: a 10,000-target run report is one INSERT
per table, not 10,000 round trips inside a request.

Every multi-row upsert sorts its rows by key first. Two transactions that lock
overlapping rows then take the locks in the same order and cannot deadlock.
The tally rows -- the only hot ones, a handful per day shared by every writer
-- are written last, so their locks are held for the shortest time.
"""

from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional
from uuid import UUID

from django.db import connection, transaction

from . import constants as c

# Postgres caps a statement at 65,535 bind parameters. 12 columns per event row
# keeps the largest table's chunks well under it.
CHUNK = 1000


@dataclass(frozen=True)
class NewEvent:
    event_uuid: UUID
    account_id: UUID
    actor_eksi_id: int
    target_eksi_id: int
    kind: int
    direction: int
    result: int
    occurred_at: datetime
    device_id: Optional[int] = None
    task_id: Optional[UUID] = None
    action_id: Optional[int] = None


@dataclass
class Collapsed:
    """What one batch does to one relation-state row."""

    state_event: Optional[tuple] = None  # (occurred_at, id, direction)
    first_added_at: Optional[datetime] = None
    last_added_at: Optional[datetime] = None
    add_count: int = 0
    legacy_only: bool = True
    touched: bool = False


@dataclass
class IngestResult:
    inserted: int = 0
    accepted: list = field(default_factory=list)


def _chunks(rows, size=CHUNK):
    for i in range(0, len(rows), size):
        yield rows[i : i + size]


def _values(rows, extra=""):
    """A multi-row VALUES list for a plain INSERT, whose target columns type the params.

    `extra` is appended to every row as literal SQL, e.g. ", now()".
    """
    width = len(rows[0])
    row = "(" + ", ".join(["%s"] * width) + extra + ")"
    return ", ".join([row] * len(rows)), [v for r in rows for v in r]


def _insert_events(cursor, events):
    """Insert what is new; return {event_uuid: id} for the rows actually inserted."""
    inserted = {}
    rows = [
        (
            e.event_uuid,
            e.account_id,
            e.device_id,
            e.task_id,
            e.action_id,
            e.actor_eksi_id,
            e.target_eksi_id,
            e.kind,
            e.direction,
            e.result,
            e.occurred_at,
        )
        for e in events
    ]
    for chunk in _chunks(rows):
        values, params = _values(chunk, extra=", now()")
        cursor.execute(
            "INSERT INTO history_event (event_uuid, account_id, device_id, task_id,"
            " action_id, actor_eksi_id, target_eksi_id, kind, direction, result,"
            f" occurred_at, received_at) VALUES {values}"
            " ON CONFLICT (event_uuid) DO NOTHING RETURNING event_uuid, id",
            params,
        )
        inserted.update({uuid: pk for uuid, pk in cursor.fetchall()})
    return inserted


def collapse(events_with_ids):
    """Fold a batch of (event, id) into one change per relation-state key.

    Needed because one INSERT ... ON CONFLICT DO UPDATE may not touch the same
    row twice. FAIL events say nothing about state and are left out entirely:
    a key whose only events failed gets no row.
    """
    out = {}
    for e, pk in events_with_ids:
        if e.result not in c.STATE_RESULTS:
            continue
        key = (e.account_id, e.actor_eksi_id, e.target_eksi_id, e.kind)
        s = out.setdefault(key, Collapsed())
        s.touched = True
        candidate = (e.occurred_at, pk, e.direction)
        if s.state_event is None or candidate[:2] > s.state_event[:2]:
            s.state_event = candidate
        if e.direction == c.ADD:
            s.add_count += 1
            if s.first_added_at is None or e.occurred_at < s.first_added_at:
                s.first_added_at = e.occurred_at
            if s.last_added_at is None or e.occurred_at > s.last_added_at:
                s.last_added_at = e.occurred_at
        if e.result != c.LEGACY:
            s.legacy_only = False
    return out


# A change applies when it is at least as new as the row's last one. Ties go to
# the incoming change, which was inserted later -- the same order the rebuild
# breaks ties in (occurred_at, then id).
_APPLIES = (
    "(rs.last_changed_at IS NULL OR EXCLUDED.last_changed_at >= rs.last_changed_at)"
)

RELATION_UPSERT_TAIL = f"""
ON CONFLICT (account_id, actor_eksi_id, target_eksi_id, kind) DO UPDATE SET
    active = CASE WHEN {_APPLIES} THEN EXCLUDED.active ELSE rs.active END,
    last_event_id = CASE WHEN {_APPLIES} THEN EXCLUDED.last_event_id
                         ELSE rs.last_event_id END,
    last_changed_at = GREATEST(rs.last_changed_at, EXCLUDED.last_changed_at),
    first_added_at = LEAST(rs.first_added_at, EXCLUDED.first_added_at),
    last_added_at = GREATEST(rs.last_added_at, EXCLUDED.last_added_at),
    add_count = rs.add_count + EXCLUDED.add_count,
    legacy_only = rs.legacy_only AND EXCLUDED.legacy_only
"""

RELATION_COLUMNS = (
    "account_id, actor_eksi_id, target_eksi_id, kind, active, last_changed_at,"
    " last_event_id, first_added_at, last_added_at, add_count, legacy_only"
)


def _upsert_relations(cursor, collapsed):
    rows = []
    for key in sorted(collapsed, key=lambda k: (str(k[0]), k[1], k[2], k[3])):
        s = collapsed[key]
        occurred_at, pk, direction = s.state_event
        rows.append(
            (
                *key,
                direction == c.ADD,
                occurred_at,
                pk,
                s.first_added_at,
                s.last_added_at,
                s.add_count,
                s.legacy_only,
            )
        )
    for chunk in _chunks(rows):
        values, params = _values(chunk)
        cursor.execute(
            f"INSERT INTO history_relationstate AS rs ({RELATION_COLUMNS})"
            f" VALUES {values} {RELATION_UPSERT_TAIL}",
            params,
        )


# Istanbul days in SQL, never in Python, so ingest, deletion and the rebuild all
# draw the day boundary with the same time zone database.
DAY_SQL = f"(occurred_at AT TIME ZONE '{c.STATS_TIME_ZONE}')::date"


def _upsert_daily(cursor, ids):
    cursor.execute(
        "INSERT INTO history_dailyactor (day, kind, account_id, actor_eksi_id)"
        f" SELECT DISTINCT {DAY_SQL}, kind, account_id, actor_eksi_id"
        " FROM history_event WHERE id = ANY(%s) ORDER BY 1, 2, 3, 4"
        " ON CONFLICT (day, kind, account_id, actor_eksi_id) DO NOTHING",
        [ids],
    )
    cursor.execute(
        "INSERT INTO history_dailytally AS t (day, kind, direction, result, n)"
        f" SELECT {DAY_SQL}, kind, direction, result, count(*)"
        " FROM history_event WHERE id = ANY(%s)"
        " GROUP BY 1, 2, 3, 4 ORDER BY 1, 2, 3, 4"
        " ON CONFLICT (day, kind, direction, result) DO UPDATE SET n = t.n + EXCLUDED.n",
        [ids],
    )


def record_nicks(cursor, nicks):
    """Upsert (eksi_id, nick, seen_at) sightings, keeping first and last seen."""
    merged = {}
    for eksi_id, nick, seen_at in nicks:
        if not eksi_id or not nick:
            continue
        key = (int(eksi_id), nick[:96])
        first, last = merged.get(key, (seen_at, seen_at))
        merged[key] = (min(first, seen_at), max(last, seen_at))
    rows = [(k[0], k[1], f, l) for k, (f, l) in sorted(merged.items())]
    for chunk in _chunks(rows):
        values, params = _values(chunk)
        cursor.execute(
            "INSERT INTO history_nickseen AS n (eksi_id, nick, first_seen_at, last_seen_at)"
            f" VALUES {values} ON CONFLICT (eksi_id, nick) DO UPDATE SET"
            " first_seen_at = LEAST(n.first_seen_at, EXCLUDED.first_seen_at),"
            " last_seen_at = GREATEST(n.last_seen_at, EXCLUDED.last_seen_at)",
            params,
        )


def ingest(events, nicks=()):
    """Store events once each and fold the new ones into state and aggregates.

    `accepted` lists every event_uuid in the input, stored before or now: a
    client that retries a batch whose response it lost must hear "done" for
    all of it, or it would retry forever.
    """
    unique = list({e.event_uuid: e for e in events}.values())
    result = IngestResult(accepted=[e.event_uuid for e in unique])
    with transaction.atomic(), connection.cursor() as cursor:
        if nicks:
            record_nicks(cursor, nicks)
        if not unique:
            return result
        inserted = _insert_events(cursor, unique)
        result.inserted = len(inserted)
        if not inserted:
            return result
        fresh = [
            (e, inserted[e.event_uuid]) for e in unique if e.event_uuid in inserted
        ]
        collapsed = collapse(fresh)
        if collapsed:
            _upsert_relations(cursor, collapsed)
        _upsert_daily(cursor, sorted(inserted.values()))
    return result
