from django.utils import timezone

from history.ingest import ingest
from history.models import AccountCheck, Device

from .helpers import LedgerTestCase, account, event
from .test_sync import Client


class CheckerTests(LedgerTestCase):
    """The admin's browser checks accounts when Ekşi turns the server away."""

    def setUp(self):
        super().setUp()
        # Actor 42 reports a block of 5; both are due for a check.
        ingest(
            [event(account(), 42, 5)],
            nicks=[(42, "ben", timezone.now()), (5, "o", timezone.now())],
        )
        self.browser = Client(self)

    def make_checker(self):
        Device.objects.filter(pk=self.browser.device.pk).update(is_checker=True)

    def report(self, *results):
        return self.browser.post("/api/sync/checks/", {"results": list(results)})

    def test_only_a_checker_gets_in(self):
        self.assertFalse(self.browser.pull().json()["checker"])
        self.assertEqual(self.browser.http.get("/api/sync/checks/").status_code, 403)
        self.assertEqual(self.report().status_code, 403)
        self.make_checker()
        self.assertTrue(self.browser.pull().json()["checker"])

    def test_due_accounts_come_with_their_nicks(self):
        self.make_checker()
        body = self.browser.http.get("/api/sync/checks/").json()
        self.assertEqual(
            body["accounts"],
            [{"eksi_id": 42, "nick": "ben"}, {"eksi_id": 5, "nick": "o"}],
        )
        self.assertEqual(body["delay_ms"], 2000)

    def test_answers_are_classified_and_attributed(self):
        self.make_checker()
        response = self.report(
            {
                "eksi_id": 42,
                "nick": "ben",
                "profile": 200,
                "entries": 200,
                "author_ids": [42],
            },
            {"eksi_id": 5, "nick": "o", "profile": 404},
            # Never handed out: not this checker's business.
            {"eksi_id": 999, "nick": "x", "profile": 404},
        )
        self.assertEqual(response.json(), {"recorded": 2})
        ok = AccountCheck.objects.get(eksi_id=42)
        self.assertEqual((ok.status, ok.found_id), (AccountCheck.OK, 42))
        self.assertEqual(ok.checked_by_id, self.browser.device.pk)
        self.assertEqual(
            AccountCheck.objects.get(eksi_id=5).status, AccountCheck.MISSING
        )
        self.assertFalse(AccountCheck.objects.filter(eksi_id=999).exists())
        self.assertEqual(
            self.browser.http.get("/api/sync/checks/").json()["accounts"], []
        )

    def test_a_refused_answer_leaves_the_account_due(self):
        self.make_checker()
        response = self.report({"eksi_id": 42, "nick": "ben", "profile": 403})
        self.assertEqual(response.json(), {"recorded": 0})
        self.assertFalse(AccountCheck.objects.exists())

    def test_a_batch_is_bounded(self):
        self.make_checker()
        results = [{"eksi_id": 42, "nick": "ben", "profile": 404}] * 21
        self.assertEqual(self.report(*results).status_code, 400)
