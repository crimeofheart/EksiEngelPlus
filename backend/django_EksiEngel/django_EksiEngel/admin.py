from datetime import timedelta

from django.contrib.admin import AdminSite
from django.db.models import Count, Q, Sum
from django.db.models.functions import TruncDay
from django.urls import reverse
from django.utils import timezone

from api.eksisozluk import user_url
from api.models import FAILED_ACTION, Action, EksiSozlukUser
from client_data_collector.models import ClientAnalytic

# Reference tables seeded by migrations and effectively never edited. Both api and
# client_data_collector define their own copy of most of them, so leaving them on the
# index shows two of every lookup. They stay registered and reachable by direct URL.
HIDDEN_LOOKUPS = {
    ("api", "bansource"),
    ("api", "banmode"),
    ("api", "targettype"),
    ("api", "clicksource"),
    ("api", "loglevel"),
    ("api", "clicktype"),
    ("api", "timespecifier"),
    ("client_data_collector", "bansource"),
    ("client_data_collector", "banmode"),
    ("client_data_collector", "targettype"),
    ("client_data_collector", "clicksource"),
    ("client_data_collector", "loglevel"),
    ("client_data_collector", "clicktype"),
}

# Django orders apps and models alphabetically, which buries Action -- the table everything
# else hangs off -- under "Action configs". Order by what you reach for most instead.
APP_ORDER = ["api", "client_data_collector", "auth"]
MODEL_ORDER = {
    "api": ["action", "eksisozlukuser", "eksisozluktitle", "eksisozlukentry", "actionconfig"],
    "client_data_collector": ["clientanalytic"],
    "auth": ["user", "group"],
}

ACTIVE_WINDOW_DAYS = 30
TIMESERIES_DAYS = 90


def _pct(part, whole):
    if not whole:
        return None
    return round(100.0 * part / whole, 1)


def _tone(rate, good, fair):
    # Thresholds come from docs/TELEMETRY_GUIDE.md: >90% excellent, 70-90% good, <70% investigate.
    if rate is None:
        return "muted"
    if rate >= good:
        return "good"
    if rate >= fair:
        return "fair"
    return "bad"


def _changelist(model, **params):
    url = reverse(f"admin:{model}_changelist")
    if params:
        url += "?" + "&".join(f"{k}={v}" for k, v in params.items())
    return url


def _bars(rows, label_key, count_key="n", href=None):
    """Turn a values()/annotate() breakdown into rows carrying a 0-100 bar width.

    `href` is an optional callable taking the raw row; when given, the template renders
    the label as a link out to Ekşi Sözlük instead of plain text.
    """
    rows = [r for r in rows if r[label_key] is not None]
    top = max((r[count_key] for r in rows), default=0)
    return [
        {
            "label": r[label_key],
            "count": r[count_key],
            "width": round(100.0 * r[count_key] / top, 1) if top else 0,
            "href": href(r) if href else None,
        }
        for r in rows
    ]


def build_dashboard_context():
    now = timezone.now()
    active_cutoff = now - timedelta(days=ACTIVE_WINDOW_DAYS)

    totals = Action.objects.aggregate(
        total=Count("id"),
        planned=Sum("planned_action"),
        performed=Sum("performed_action"),
        successful=Sum("successful_action"),
        early_stopped=Count("id", filter=Q(is_early_stopped=True)),
        failed=Count("id", filter=FAILED_ACTION),
        targets=Sum("author_list_size"),
    )
    recent = Action.objects.filter(date__gte=active_cutoff).count()

    # is_eksiengel_user separates real extension users from the far larger population of
    # scraped block targets. Counting EksiSozlukUser unfiltered would overstate users ~450x.
    users = EksiSozlukUser.objects.filter(is_eksiengel_user=True).aggregate(
        total=Count("id"),
        active=Count("id", filter=Q(last_activity_date__gte=active_cutoff)),
    )
    targets_known = EksiSozlukUser.objects.filter(is_eksiengel_user=False).count()

    # Builds before v0.1.5 posted click_type and nothing else, so a large anonymous share
    # is expected until the fleet turns over. Surfacing it beats hiding the column.
    ui_events = ClientAnalytic.objects.aggregate(
        total=Count("id"),
        identified=Count("id", filter=~Q(client_name=None)),
    )

    success_rate = _pct(totals["successful"] or 0, totals["performed"] or 0)
    completion_rate = _pct(totals["performed"] or 0, totals["planned"] or 0)
    early_rate = _pct(totals["early_stopped"], totals["total"])

    tiles = [
        {
            "label": "Actions",
            "value": totals["total"],
            "sub": f"{recent} in last {ACTIVE_WINDOW_DAYS} days",
            "url": _changelist("api_action"),
            "tone": "muted",
        },
        {
            "label": "Success rate",
            "value": "—" if success_rate is None else f"{success_rate}%",
            "sub": f"{totals['successful'] or 0} of {totals['performed'] or 0} performed",
            "url": _changelist("api_action"),
            "tone": _tone(success_rate, 90, 70),
        },
        {
            "label": "Completion rate",
            "value": "—" if completion_rate is None else f"{completion_rate}%",
            "sub": f"{totals['performed'] or 0} of {totals['planned'] or 0} planned",
            "url": _changelist("api_action"),
            "tone": _tone(completion_rate, 90, 70),
        },
        {
            "label": "Failed actions",
            "value": totals["failed"],
            "sub": "ran to completion, steps missing",
            "url": _changelist("api_action", outcome="failed"),
            "tone": "bad" if totals["failed"] else "good",
        },
        {
            "label": "Early stopped",
            "value": totals["early_stopped"],
            "sub": "—" if early_rate is None else f"{early_rate}% of all actions",
            "url": _changelist("api_action", is_early_stopped__exact=1),
            "tone": "muted",
        },
        {
            "label": "Extension users",
            "value": users["total"],
            "sub": f"{users['active']} active in {ACTIVE_WINDOW_DAYS}d",
            "url": _changelist("api_eksisozlukuser", is_eksiengel_user__exact=1),
            "tone": "muted",
        },
        {
            "label": "Targets touched",
            "value": totals["targets"] or 0,
            "sub": f"{targets_known} distinct authors seen",
            "url": _changelist("api_eksisozlukuser", is_eksiengel_user__exact=0),
            "tone": "muted",
        },
        {
            "label": "UI events",
            "value": ui_events["total"],
            "sub": (
                "none recorded" if not ui_events["total"]
                else f"{ui_events['identified']} attributed to a username"
            ),
            "url": _changelist("client_data_collector_clientanalytic"),
            "tone": "muted",
        },
    ]

    series = list(
        Action.objects.filter(date__gte=now - timedelta(days=TIMESERIES_DAYS))
        .annotate(day=TruncDay("date"))
        .values("day")
        .annotate(
            total=Count("id"),
            performed=Sum("performed_action"),
            successful=Sum("successful_action"),
        )
        .order_by("day")
    )
    chart = [
        {"day": r["day"].strftime("%Y-%m-%d"), "total": r["total"]} for r in series
    ]

    ban_source = _bars(
        Action.objects.values("ban_source__ban_source")
        .annotate(n=Count("id"))
        .order_by("-n"),
        "ban_source__ban_source",
    )
    versions = _bars(
        Action.objects.values("version").annotate(n=Count("id")).order_by("-n"),
        "version",
    )
    clicks = _bars(
        ClientAnalytic.objects.values("click_type__click_type")
        .annotate(n=Count("id"))
        .order_by("-n")[:12],
        "click_type__click_type",
    )
    top_users = _bars(
        Action.objects.values("eksi_engel_user__eksisozluk_name")
        .annotate(n=Count("id"))
        .order_by("-n")[:12],
        "eksi_engel_user__eksisozluk_name",
        href=lambda r: user_url(r["eksi_engel_user__eksisozluk_name"]),
    )

    return {
        "tiles": tiles,
        "chart_data": chart,
        "breakdowns": [
            {"title": "Action source", "rows": ban_source},
            {"title": "Extension version", "rows": versions},
            {"title": "UI events by type", "rows": clicks},
            {"title": "Most active users", "rows": top_users},
        ],
        "timeseries_days": TIMESERIES_DAYS,
        "has_actions": bool(totals["total"]),
    }


class EksiEngelAdminSite(AdminSite):
    site_header = "EksiEngelPlus"
    site_title = "EksiEngelPlus admin"
    index_title = "Dashboard"
    index_template = "admin/dashboard.html"

    def index(self, request, extra_context=None):
        context = dict(extra_context or {})
        context.update(build_dashboard_context())
        return super().index(request, context)

    def get_app_list(self, request, app_label=None):
        app_list = super().get_app_list(request, app_label)
        for app in app_list:
            app["models"] = [
                model
                for model in app["models"]
                if (app["app_label"], model["object_name"].lower()) not in HIDDEN_LOOKUPS
            ]
            order = MODEL_ORDER.get(app["app_label"], [])
            app["models"].sort(
                key=lambda m: (
                    order.index(m["object_name"].lower())
                    if m["object_name"].lower() in order
                    else len(order),
                    m["name"],
                )
            )
        app_list = [app for app in app_list if app["models"]]
        app_list.sort(
            key=lambda a: (
                APP_ORDER.index(a["app_label"]) if a["app_label"] in APP_ORDER else len(APP_ORDER),
                a["name"],
            )
        )
        return app_list
