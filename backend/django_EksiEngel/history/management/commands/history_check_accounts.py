import time

from django.core.management.base import BaseCommand
from django.utils import timezone

from ...accountcheck import Refused, candidates, check, due, record
from ...stats import latest_nicks


class Command(BaseCommand):
    help = (
        "Check that reporting Ekşi ids, and the targets near the top of the rankings, "
        "are real accounts whose nick and id match. Ids found missing or mismatched "
        "are left out of the rankings. Run hourly by eksiengel-history-check.timer."
    )

    def add_arguments(self, parser):
        parser.add_argument("--limit", type=int, default=100)
        parser.add_argument(
            "--delay", type=float, default=2.0, help="seconds between accounts"
        )

    def handle(self, *args, limit, delay, **options):
        now = timezone.now()
        ids = due(candidates(), now)[:limit]
        nicks = latest_nicks(ids)
        counts = {}
        refused = None
        for i, eksi_id in enumerate(ids):
            nick = nicks.get(eksi_id)
            if not nick:
                continue
            if i:
                time.sleep(delay)
            try:
                status, found_id = check(nick, eksi_id)
            except Refused as error:
                # Asking on would only be refused again. Nothing is recorded, so
                # this id and the rest stay due for the next run.
                refused = error
                break
            record(eksi_id, nick, status, found_id)
            counts[status] = counts.get(status, 0) + 1
        summary = ", ".join(f"{k.lower()} {v}" for k, v in sorted(counts.items())) or (
            "none" if refused else "nothing due"
        )
        if refused:
            summary += f"; stopped, Ekşi refused with {refused}"
        self.stdout.write(f"checked {sum(counts.values())}: {summary}")
