import io
from unittest import mock

from django.core.management import call_command
from django.db import connection
from django.utils import timezone

from history import constants as c
from history import stats, sync
from history.accountcheck import Refused, check
from history.ingest import ingest
from history.management.commands.history_rebuild import differences
from history.models import AccountCheck, ActorRelation

from .helpers import LedgerTestCase, account, event


def clean(test):
    with connection.cursor() as cursor:
        diff = differences(cursor)
    test.assertEqual(diff, {k: (0, 0) for k in diff})


class PersonStateTests(LedgerTestCase):
    """What one person holds now, whichever of their accounts recorded it."""

    def test_unblock_from_another_account_ends_the_block(self):
        app, extension = account(), account()  # one person, two unlinked accounts
        ingest([event(app, 42, 5, when=1)])
        ingest([event(extension, 42, 5, direction=c.REMOVE, when=2)])
        self.assertEqual(stats.top(c.BLOCK, "current"), [])
        self.assertEqual(stats.top(c.BLOCK, "all")[0]["actors"], 1)
        rows = stats.user_relations(42, "actor", kind=c.BLOCK)["results"]
        self.assertEqual(
            [(r["eksi_id"], r["active"], r["accounts"]) for r in rows], [(5, False, 2)]
        )
        clean(self)

    def test_one_target_in_two_accounts_counts_once(self):
        a, b = account(), account()
        ingest([event(a, 42, 5, when=1)])
        ingest([event(b, 42, 5, when=2)])
        summary = stats.user_summary(42)
        self.assertEqual(summary["kinds"]["BLOCK"]["active_as_actor"], 1)
        self.assertEqual(len(summary["accounts"]), 2)
        self.assertEqual(stats.top(c.BLOCK, "current")[0]["actors"], 1)

    def test_deleting_an_account_falls_back_to_the_others(self):
        app, extension = account(), account()
        ingest([event(app, 42, 5, when=1)])
        ingest([event(extension, 42, 5, direction=c.REMOVE, when=2)])
        sync.delete_account(extension.pk)
        # Only the app's block is left, so that is what the person holds.
        self.assertTrue(ActorRelation.objects.get().active)
        sync.delete_account(app.pk)
        self.assertFalse(ActorRelation.objects.exists())
        clean(self)


def fake(pages):
    def get(url, headers):
        for prefix, response in pages.items():
            if url.startswith(prefix):
                return response
        return 500, ""

    return get


class AccountCheckTests(LedgerTestCase):
    profile = "https://eksisozluk.com/biri/"
    entries = "https://eksisozluk.com/son-entryleri"

    def test_statuses(self):
        entries = '<li data-author="ssg" data-author-id="8097">'
        self.assertEqual(
            check(
                "ssg",
                8097,
                fake({self.profile: (200, ""), self.entries: (200, entries)}),
            ),
            (AccountCheck.OK, 8097),
        )
        self.assertEqual(
            check(
                "ssg", 1, fake({self.profile: (200, ""), self.entries: (200, entries)})
            ),
            (AccountCheck.MISMATCH, 8097),
        )
        self.assertEqual(
            check(
                "ssg", 8097, fake({self.profile: (200, ""), self.entries: (200, "")})
            ),
            (AccountCheck.EXISTS, None),
        )
        self.assertEqual(
            check("yok", 5, fake({self.profile: (404, "")})),
            (AccountCheck.MISSING, None),
        )
        self.assertEqual(
            check("x", 5, fake({self.profile: (0, "")})), (AccountCheck.ERROR, None)
        )
        with self.assertRaises(Refused):
            check("x", 5, fake({self.profile: (403, "")}))

    def test_rejected_ids_leave_the_rankings(self):
        ingest([event(account(), 1, 5), event(account(), 2, 5), event(account(), 3, 6)])
        AccountCheck.objects.create(
            eksi_id=2,
            nick="fake",
            status=AccountCheck.MISSING,
            checked_at=timezone.now(),
        )
        AccountCheck.objects.create(
            eksi_id=6,
            nick="nobody",
            status=AccountCheck.MISMATCH,
            checked_at=timezone.now(),
        )
        AccountCheck.objects.create(
            eksi_id=1,
            nick="real",
            status=AccountCheck.EXISTS,
            checked_at=timezone.now(),
        )
        self.assertEqual(
            [(r["target_eksi_id"], r["actors"]) for r in stats.top(c.BLOCK, "current")],
            [(5, 1)],
        )

    def test_command_checks_actors_and_top_targets_once(self):
        ingest(
            [event(account(), 42, 5)],
            nicks=[(42, "ben", timezone.now()), (5, "o", timezone.now())],
        )
        pages = {self.profile: (200, ""), self.entries: (200, 'data-author-id="42"')}
        out = io.StringIO()
        with mock.patch("history.accountcheck.fetch", fake(pages)):
            call_command("history_check_accounts", delay=0, stdout=out)
            self.assertIn("checked 2", out.getvalue())
            out = io.StringIO()
            call_command("history_check_accounts", delay=0, stdout=out)
            self.assertIn("nothing due", out.getvalue())
        self.assertEqual(AccountCheck.objects.get(eksi_id=42).status, AccountCheck.OK)
        # The target's entries carry another id: its nick does not belong to it.
        self.assertEqual(
            AccountCheck.objects.get(eksi_id=5).status, AccountCheck.MISMATCH
        )

    def test_command_stops_at_the_first_refusal(self):
        ingest(
            [event(account(), 42, 5)],
            nicks=[(42, "ben", timezone.now()), (5, "o", timezone.now())],
        )
        asked = []

        def challenge(url, headers):
            asked.append(url)
            return 403, ""

        out = io.StringIO()
        with mock.patch("history.accountcheck.fetch", challenge):
            call_command("history_check_accounts", delay=0, stdout=out)
        self.assertIn(
            "checked 0: none; stopped, Ekşi refused with HTTP 403", out.getvalue()
        )
        # One request, and nothing recorded: both ids stay due for the next run.
        self.assertEqual(len(asked), 1)
        self.assertFalse(AccountCheck.objects.exists())
