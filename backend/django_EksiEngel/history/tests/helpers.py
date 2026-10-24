import uuid
from datetime import datetime, timedelta, timezone as dt_timezone

from django.core.cache import cache
from django.test import TestCase

from history import constants as c
from history.ingest import NewEvent, ingest
from history.models import Account

T0 = datetime(2026, 10, 1, 12, 0, tzinfo=dt_timezone.utc)


def at(minutes):
    return T0 + timedelta(minutes=minutes)


def account():
    return Account.objects.create()


def event(acct, actor, target, kind=c.BLOCK, direction=c.ADD, result=c.OK, when=0, **kw):
    return NewEvent(
        event_uuid=kw.pop("event_uuid", None) or uuid.uuid4(),
        account_id=acct.pk if hasattr(acct, "pk") else acct,
        actor_eksi_id=actor,
        target_eksi_id=target,
        kind=kind,
        direction=direction,
        result=result,
        occurred_at=when if isinstance(when, datetime) else at(when),
        **kw,
    )


def block(acct, actor, target, when=0, **kw):
    return ingest([event(acct, actor, target, when=when, **kw)])


def unblock(acct, actor, target, when=0, **kw):
    return ingest([event(acct, actor, target, direction=c.REMOVE, when=when, **kw)])


class LedgerTestCase(TestCase):
    """Stats are cached for ten minutes; a test must not read the previous test's."""

    def setUp(self):
        super().setUp()
        cache.clear()
