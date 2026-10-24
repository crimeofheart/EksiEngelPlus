from django.core.management.base import BaseCommand
from django.db import connection, transaction

from ... import constants as c
from ...ingest import DAY_SQL

STATE = ", ".join(str(r) for r in c.STATE_RESULTS)

# Each derived table, recomputed from history_event alone. The incremental path in
# history.ingest must always land on exactly these rows; --verify checks that.
REBUILT = {
    "history_relationstate": (
        "account_id, actor_eksi_id, target_eksi_id, kind, active, last_changed_at,"
        " last_event_id, first_added_at, last_added_at, add_count, legacy_only",
        f"""
        WITH ev AS (SELECT * FROM history_event WHERE result IN ({STATE})),
        latest AS (
            SELECT DISTINCT ON (account_id, actor_eksi_id, target_eksi_id, kind)
                account_id, actor_eksi_id, target_eksi_id, kind,
                direction = {c.ADD} AS active, occurred_at AS last_changed_at,
                id AS last_event_id
            FROM ev
            ORDER BY account_id, actor_eksi_id, target_eksi_id, kind,
                     occurred_at DESC, id DESC
        ),
        agg AS (
            SELECT account_id, actor_eksi_id, target_eksi_id, kind,
                MIN(occurred_at) FILTER (WHERE direction = {c.ADD}) AS first_added_at,
                MAX(occurred_at) FILTER (WHERE direction = {c.ADD}) AS last_added_at,
                COUNT(*) FILTER (WHERE direction = {c.ADD}) AS add_count,
                bool_and(result = {c.LEGACY}) AS legacy_only
            FROM ev GROUP BY 1, 2, 3, 4
        )
        SELECT l.account_id, l.actor_eksi_id, l.target_eksi_id, l.kind, l.active,
            l.last_changed_at, l.last_event_id, a.first_added_at, a.last_added_at,
            a.add_count, a.legacy_only
        FROM latest l JOIN agg a USING (account_id, actor_eksi_id, target_eksi_id, kind)
        """,
    ),
    "history_dailytally": (
        "day, kind, direction, result, n",
        f"SELECT {DAY_SQL}, kind, direction, result, count(*) FROM history_event"
        " GROUP BY 1, 2, 3, 4",
    ),
    "history_dailyactor": (
        "day, kind, account_id, actor_eksi_id",
        f"SELECT DISTINCT {DAY_SQL}, kind, account_id, actor_eksi_id FROM history_event",
    ),
}


def differences(cursor):
    """{table: (rows only in the stored table, rows only in the rebuild)}."""
    out = {}
    for table, (columns, query) in REBUILT.items():
        cursor.execute(
            f"SELECT count(*) FROM (SELECT {columns} FROM {table} EXCEPT ({query})) s"
        )
        extra = cursor.fetchone()[0]
        cursor.execute(
            f"SELECT count(*) FROM (({query}) EXCEPT SELECT {columns} FROM {table}) s"
        )
        missing = cursor.fetchone()[0]
        out[table] = (extra, missing)
    return out


class Command(BaseCommand):
    help = (
        "Recompute relation state and the daily aggregates from the event table. "
        "With --verify, only report how the stored rows differ from a rebuild."
    )

    def add_arguments(self, parser):
        parser.add_argument("--verify", action="store_true")

    def handle(self, *args, verify, **options):
        with connection.cursor() as cursor:
            if verify:
                diff = differences(cursor)
                for table, (extra, missing) in diff.items():
                    self.stdout.write(f"{table}: {extra} stale, {missing} missing")
                if any(sum(d) for d in diff.values()):
                    self.stdout.write(self.style.ERROR("derived tables differ from a rebuild"))
                    raise SystemExit(1)
                self.stdout.write(self.style.SUCCESS("derived tables match a rebuild"))
                return
            with transaction.atomic():
                for table, (columns, query) in REBUILT.items():
                    # Takes the table's write lock: ingest waits for the rebuild.
                    cursor.execute(f"LOCK TABLE {table} IN EXCLUSIVE MODE")
                    cursor.execute(f"DELETE FROM {table}")
                    cursor.execute(f"INSERT INTO {table} ({columns}) {query}")
                    self.stdout.write(f"{table}: {cursor.rowcount} rows")
        self.stdout.write(self.style.SUCCESS("rebuilt"))
