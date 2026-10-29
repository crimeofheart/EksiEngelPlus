import time
from datetime import timedelta

from django.core.management.base import BaseCommand
from django.db.models import Count
from django.utils import timezone

from ... import constants as c
from ...accountcheck import check
from ...models import AccountCheck, ActorRelation, Task
from ...stats import latest_nicks

# A settled answer is asked again after this long: accounts get deleted, and a
# nick can be released and taken by someone else.
RECHECK_AFTER = timedelta(days=30)
# An unanswered question is asked again sooner.
RETRY_ERROR_AFTER = timedelta(hours=1)
# Targets matter once they could reach a ranking.
TOP_TARGETS = 200


def candidates():
    """Every reporting actor, then the targets near the top of the rankings."""
    actors = set(
        ActorRelation.objects.values_list("actor_eksi_id", flat=True).distinct()
    )
    actors |= set(
        Task.objects.exclude(actor_eksi_id=None).values_list("actor_eksi_id", flat=True)
    )
    targets = []
    for kind in (c.BLOCK, c.MUTE):
        targets += list(
            ActorRelation.objects.filter(kind=kind, first_added_at__isnull=False)
            .values("target_eksi_id")
            .annotate(n=Count("id"))
            .order_by("-n")
            .values_list("target_eksi_id", flat=True)[:TOP_TARGETS]
        )
    ordered = sorted(actors) + [t for t in targets if t not in actors]
    return list(dict.fromkeys(ordered))


def due(ids, now):
    existing = {a.eksi_id: a for a in AccountCheck.objects.filter(eksi_id__in=ids)}
    out = []
    for eksi_id in ids:
        known = existing.get(eksi_id)
        if known is None:
            out.append(eksi_id)
        elif known.status == AccountCheck.ERROR:
            if now - known.checked_at >= RETRY_ERROR_AFTER:
                out.append(eksi_id)
        elif now - known.checked_at >= RECHECK_AFTER:
            out.append(eksi_id)
    return out


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
        for i, eksi_id in enumerate(ids):
            nick = nicks.get(eksi_id)
            if not nick:
                continue
            if i:
                time.sleep(delay)
            status, found_id = check(nick, eksi_id)
            AccountCheck.objects.update_or_create(
                eksi_id=eksi_id,
                defaults={
                    "nick": nick,
                    "status": status,
                    "found_id": found_id,
                    "checked_at": timezone.now(),
                },
            )
            counts[status] = counts.get(status, 0) + 1
        summary = (
            ", ".join(f"{k.lower()} {v}" for k, v in sorted(counts.items()))
            or "nothing due"
        )
        self.stdout.write(f"checked {sum(counts.values())}: {summary}")
