from collections import Counter

from django.core.management.base import BaseCommand
from django.db import transaction

from api.models import Action

from ...legacy import ingest_action


class Command(BaseCommand):
    help = (
        "Convert stored /api/action/ run reports into LEGACY ledger events. "
        "Idempotent and resumable: event ids derive from the report, so re-running "
        "adds nothing, and --start-pk picks up where an interrupted run stopped."
    )

    def add_arguments(self, parser):
        parser.add_argument("--start-pk", type=int, default=0)
        parser.add_argument("--batch", type=int, default=200)

    def handle(self, *args, start_pk, batch, **options):
        created, reports = 0, 0
        skipped = Counter()
        last_pk = start_pk - 1
        while True:
            actions = list(
                Action.objects.filter(pk__gt=last_pk)
                .select_related("eksi_engel_user", "action_config")
                .prefetch_related("author_list")
                .order_by("pk")[:batch]
            )
            if not actions:
                break
            for action in actions:
                # One transaction per report: an interruption loses at most one.
                with transaction.atomic():
                    inserted, skip = ingest_action(action, list(action.author_list.all()))
                created += inserted
                skipped.update(skip)
                reports += 1
            last_pk = actions[-1].pk
            self.stdout.write(f"... through action {last_pk}: {created} events created")
        self.stdout.write(self.style.SUCCESS(f"{reports} reports, {created} events created"))
        for (source, mode), targets in sorted(skipped.items()):
            self.stdout.write(
                f"skipped: ban_source={source} ban_mode={mode}: {targets} targets (no mapping)"
            )
