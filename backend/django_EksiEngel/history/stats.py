"""Every number the admin sees about the ledger, computed in one place.

The DRF views, the old most_banned routes and the admin dashboard all call these
functions, so no two of them can disagree. Global stats read RelationState and
the daily aggregates only -- never Event -- so their cost follows the number of
distinct relations and days, not the size of the history. Event is read only
through its account, actor and target indexes, for one person at a time.
"""

from datetime import timedelta

from django.core.cache import cache
from django.db.models import Count, Q, Sum
from django.utils import timezone

from . import constants as c
from .models import (
    Account,
    DailyActor,
    DailyTally,
    Device,
    Event,
    NickSeen,
    RelationState,
    Task,
)

CACHE_SECONDS = 600
MAX_LIMIT = 500


def _istanbul_today():
    from zoneinfo import ZoneInfo

    return timezone.now().astimezone(ZoneInfo(c.STATS_TIME_ZONE)).date()


def latest_nicks(eksi_ids):
    """{eksi_id: most recently seen nick}."""
    ids = list({i for i in eksi_ids if i})
    if not ids:
        return {}
    rows = (
        NickSeen.objects.filter(eksi_id__in=ids)
        .order_by("eksi_id", "-last_seen_at")
        .distinct("eksi_id")
        .values_list("eksi_id", "nick")
    )
    return dict(rows)


def _cached(key, compute):
    value = cache.get(key)
    if value is None:
        value = compute()
        cache.set(key, value, CACHE_SECONDS)
    return value


def scope_filter(scope, days=30):
    if scope == "current":
        return Q(active=True)
    if scope == "all":
        return Q(first_added_at__isnull=False)
    if scope == "window":
        return Q(last_added_at__gte=timezone.now() - timedelta(days=days))
    raise ValueError(scope)


def top(kind, scope="current", days=30, limit=50, include_legacy=True):
    """Targets ranked by how many distinct actors hold `kind` against them."""
    limit = max(1, min(int(limit), MAX_LIMIT))
    key = f"history:top:{kind}:{scope}:{days if scope == 'window' else ''}:{limit}:{include_legacy}"

    def compute():
        rows = RelationState.objects.filter(scope_filter(scope, days), kind=kind)
        if not include_legacy:
            rows = rows.filter(legacy_only=False)
        ranked = rows
        rows = list(
            rows.values("target_eksi_id")
            .annotate(actors=Count("actor_eksi_id", distinct=True))
            .order_by("-actors", "target_eksi_id")[:limit]
        )
        # Accounts only for the winners: counting them for every target would
        # take the ranking off the covering index.
        accounts = dict(
            ranked.filter(target_eksi_id__in=[r["target_eksi_id"] for r in rows])
            .values("target_eksi_id")
            .annotate(n=Count("account", distinct=True))
            .values_list("target_eksi_id", "n")
        )
        for r in rows:
            r["accounts"] = accounts.get(r["target_eksi_id"], 0)
        nicks = latest_nicks(r["target_eksi_id"] for r in rows)
        return [
            {
                "target_eksi_id": r["target_eksi_id"],
                "nick": nicks.get(r["target_eksi_id"]),
                "actors": r["actors"],
                "accounts": r["accounts"],
            }
            for r in rows
        ]

    return _cached(key, compute)


def timeseries(kind=None, days=90):
    """Per Istanbul day: event counts by direction and result, and distinct actors."""
    days = max(1, min(int(days), 3650))
    key = f"history:timeseries:{kind}:{days}"

    def compute():
        start = _istanbul_today() - timedelta(days=days - 1)
        tallies = DailyTally.objects.filter(day__gte=start)
        actors = DailyActor.objects.filter(day__gte=start)
        if kind:
            tallies = tallies.filter(kind=kind)
            actors = actors.filter(kind=kind)
        by_day = {}
        for t in tallies.values("day", "direction", "result").annotate(n=Sum("n")):
            entry = by_day.setdefault(t["day"], {"events": {}, "total": 0, "actors": 0})
            direction = c.name_of(c.DIRECTIONS, t["direction"])
            result = c.name_of(c.RESULTS, t["result"])
            entry["events"].setdefault(direction, {})[result] = t["n"]
            entry["total"] += t["n"]
        for a in actors.values("day").annotate(n=Count("actor_eksi_id", distinct=True)):
            by_day.setdefault(a["day"], {"events": {}, "total": 0, "actors": 0})[
                "actors"
            ] = a["n"]
        return [{"day": day.isoformat(), **by_day[day]} for day in sorted(by_day)]

    return _cached(key, compute)


# --- one person ------------------------------------------------------------------


def _role_field(role):
    if role == "actor":
        return "actor_eksi_id"
    if role == "target":
        return "target_eksi_id"
    raise ValueError(role)


def user_summary(eksi_id):
    nicks = list(
        NickSeen.objects.filter(eksi_id=eksi_id)
        .order_by("-last_seen_at")
        .values("nick", "first_seen_at", "last_seen_at")
    )
    as_actor = Event.objects.filter(actor_eksi_id=eksi_id)
    first = as_actor.order_by("id").values_list("occurred_at", flat=True).first()
    last = as_actor.order_by("-id").values_list("occurred_at", flat=True).first()
    accounts = set(
        RelationState.objects.filter(actor_eksi_id=eksi_id)
        .values_list("account", flat=True)
        .distinct()
    ) | set(
        Task.objects.filter(actor_eksi_id=eksi_id).values_list("account", flat=True)
    )
    devices = list(
        Device.objects.filter(account__in=accounts).values(
            "client", "label", "last_seen_at"
        )
    )
    kinds = {}
    for name, kind in c.KINDS.items():
        kinds[name] = {
            "active_as_actor": RelationState.objects.filter(
                actor_eksi_id=eksi_id, kind=kind, active=True
            ).count(),
            "actors_targeting_now": RelationState.objects.filter(
                target_eksi_id=eksi_id, kind=kind, active=True
            )
            .values("actor_eksi_id")
            .distinct()
            .count(),
        }
    totals = {}
    for role in ("actor", "target"):
        rows = (
            Event.objects.filter(**{_role_field(role): eksi_id})
            .values("kind", "direction", "result")
            .annotate(n=Count("id"))
        )
        out = totals.setdefault(role, {})
        for r in rows:
            k = c.name_of(c.KINDS, r["kind"])
            d = c.name_of(c.DIRECTIONS, r["direction"])
            res = c.name_of(c.RESULTS, r["result"])
            out.setdefault(k, {}).setdefault(d, {})[res] = r["n"]
    return {
        "eksi_id": eksi_id,
        "nicks": nicks,
        "first_activity": first,
        "last_activity": last,
        "accounts": len(accounts),
        "devices": devices,
        "clients": sorted({d["client"] for d in devices}),
        "kinds": kinds,
        "event_totals": totals,
    }


def user_events(eksi_id, role="actor", kind=None, before=None, limit=100):
    """The ledger in one role, newest first, keyset-paginated by event id."""
    limit = max(1, min(int(limit), MAX_LIMIT))
    rows = Event.objects.filter(**{_role_field(role): eksi_id})
    if kind:
        rows = rows.filter(kind=kind)
    if before:
        rows = rows.filter(id__lt=before)
    rows = list(
        rows.order_by("-id").values(
            "id",
            "event_uuid",
            "actor_eksi_id",
            "target_eksi_id",
            "kind",
            "direction",
            "result",
            "occurred_at",
            "received_at",
            "task_id",
            "action_id",
        )[:limit]
    )
    nicks = latest_nicks(
        [r["actor_eksi_id"] for r in rows] + [r["target_eksi_id"] for r in rows]
    )
    items = [
        {
            **r,
            "actor_nick": nicks.get(r["actor_eksi_id"]),
            "target_nick": nicks.get(r["target_eksi_id"]),
            "kind": c.name_of(c.KINDS, r["kind"]),
            "direction": c.name_of(c.DIRECTIONS, r["direction"]),
            "result": c.name_of(c.RESULTS, r["result"]),
        }
        for r in rows
    ]
    return {
        "results": items,
        "next_before": rows[-1]["id"] if len(rows) == limit else None,
    }


def user_relations(
    eksi_id, role="actor", kind=None, active=None, before=None, limit=200
):
    """Who they hold a relation to (actor), or who holds one to them (target)."""
    limit = max(1, min(int(limit), MAX_LIMIT))
    field = _role_field(role)
    other = "target_eksi_id" if role == "actor" else "actor_eksi_id"
    rows = RelationState.objects.filter(**{field: eksi_id})
    if kind:
        rows = rows.filter(kind=kind)
    if active is not None:
        rows = rows.filter(active=active)
    if before:
        rows = rows.filter(id__lt=before)
    rows = list(
        rows.order_by("-id").values(
            "id",
            other,
            "kind",
            "active",
            "first_added_at",
            "last_added_at",
            "last_changed_at",
            "add_count",
            "legacy_only",
        )[:limit]
    )
    nicks = latest_nicks(r[other] for r in rows)
    items = [
        {
            **r,
            "eksi_id": r[other],
            "nick": nicks.get(r[other]),
            "kind": c.name_of(c.KINDS, r["kind"]),
        }
        for r in rows
    ]
    return {
        "results": items,
        "next_before": rows[-1]["id"] if len(rows) == limit else None,
    }


def user_tasks(eksi_id, limit=100):
    from api.models import Action

    limit = max(1, min(int(limit), MAX_LIMIT))
    tasks = list(
        Task.objects.filter(actor_eksi_id=eksi_id)
        .select_related("owner_device")
        .order_by("-created_at")[:limit]
    )
    reports = dict(
        Action.objects.filter(task_id__in=[t.pk for t in tasks]).values_list(
            "task_id", "id"
        )
    )
    return [
        {
            "id": str(t.pk),
            "description": t.description,
            "ban_source": t.ban_source,
            "ban_mode": t.ban_mode,
            "status": c.name_of(c.STATUSES, t.status),
            "planned": t.planned,
            "performed": t.performed,
            "successful": t.successful,
            "epoch": t.epoch,
            "owner": t.owner_device.label if t.owner_device else None,
            "created_at": t.created_at,
            "updated_at": t.updated_at,
            "action_id": reports.get(t.pk),
        }
        for t in tasks
    ]


# --- the routes that predate the ledger -------------------------------------------


def legacy_ranking(unique, limit=500):
    """most_banned/ and most_banned_unique/, now from the ledger: kind BLOCK, all time."""
    limit = max(1, min(int(limit), 5000))
    key = f"history:legacy_ranking:{unique}:{limit}"

    def compute():
        rows = RelationState.objects.filter(kind=c.BLOCK, first_added_at__isnull=False)
        if unique:
            rows = rows.values("target_eksi_id").annotate(
                n=Count("actor_eksi_id", distinct=True)
            )
        else:
            rows = rows.values("target_eksi_id").annotate(n=Sum("add_count"))
        rows = list(rows.order_by("-n", "target_eksi_id")[:limit])
        nicks = latest_nicks(r["target_eksi_id"] for r in rows)
        return [
            (r["target_eksi_id"], nicks.get(r["target_eksi_id"]), r["n"]) for r in rows
        ]

    return _cached(key, compute)


def user_stat_fields(eksi_ids):
    """The five user_stat/ counts for a page of ids, in a fixed number of queries."""
    from api.models import Action

    ids = list(set(eksi_ids))
    blocks = RelationState.objects.filter(kind=c.BLOCK, first_added_at__isnull=False)

    def grouped(qs, field, **annotation):
        return {r[field]: r["n"] for r in qs.values(field).annotate(**annotation)}

    return {
        "action_for_ban_count": dict(
            Action.objects.filter(
                eksi_engel_user__eksisozluk_id__in=ids, ban_mode__ban_mode="BAN"
            )
            .values_list("eksi_engel_user__eksisozluk_id")
            .annotate(n=Count("id"))
        ),
        "ban_count": grouped(
            blocks.filter(actor_eksi_id__in=ids), "actor_eksi_id", n=Sum("add_count")
        ),
        "ban_unique_count": grouped(
            blocks.filter(actor_eksi_id__in=ids),
            "actor_eksi_id",
            n=Count("target_eksi_id", distinct=True),
        ),
        "banned_by_count": grouped(
            blocks.filter(target_eksi_id__in=ids), "target_eksi_id", n=Sum("add_count")
        ),
        "banned_by_unique_count": grouped(
            blocks.filter(target_eksi_id__in=ids),
            "target_eksi_id",
            n=Count("actor_eksi_id", distinct=True),
        ),
    }


def account_summary():
    """Counts for the dashboard tiles."""
    return {
        "accounts": Account.objects.filter(kind=c.ACCOUNT_DEVICE).count(),
        "devices": Device.objects.filter(revoked_at__isnull=True).count(),
        "relations_active": RelationState.objects.filter(active=True).count(),
        "events_last_day": DailyTally.objects.filter(day=_istanbul_today()).aggregate(
            n=Sum("n")
        )["n"]
        or 0,
    }
