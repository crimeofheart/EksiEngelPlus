import logging

from django.utils import timezone
from rest_framework import status, views
from rest_framework.authentication import BasicAuthentication
from rest_framework.permissions import AllowAny, IsAdminUser
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle

from api.authentication import SharedAPIKeyAuthentication
from api.views import CsrfExemptSessionAuthentication

from . import constants as c
from . import accountcheck, stats, sync
from .authentication import DeviceTokenAuthentication, IsDevice
from .ingest import NewEvent, ingest
from .models import Account, AdminAccess, Event
from .serializers import (
    MAX_CHECKS,
    CheckReportSerializer,
    EnrollSerializer,
    PairClaimSerializer,
    PushSerializer,
)
from .stats import _istanbul_today as istanbul_today
from .throttling import IPScopedRateThrottle

logger = logging.getLogger(__name__)


class DeviceView(views.APIView):
    authentication_classes = (DeviceTokenAuthentication,)
    permission_classes = (IsDevice,)
    throttle_classes = (ScopedRateThrottle,)
    throttle_scope = "sync_read"

    @property
    def device(self):
        return self.request.auth


class EnrollView(views.APIView):
    authentication_classes = (SharedAPIKeyAuthentication,)
    permission_classes = (AllowAny,)
    throttle_classes = (IPScopedRateThrottle,)
    throttle_scope = "sync_enroll"

    def post(self, request):
        data = EnrollSerializer(data=request.data)
        data.is_valid(raise_exception=True)
        device, token, join_key = sync.enroll(
            client=data.validated_data["client"],
            label=data.validated_data["label"],
            version=data.validated_data["version"],
            join_key=data.validated_data.get("join_key"),
        )
        return Response(
            {"device_token": token, "join_key": join_key},
            status=status.HTTP_201_CREATED,
        )


class PushView(DeviceView):
    throttle_scope = "sync_push"

    def post(self, request):
        data = PushSerializer(data=request.data)
        data.is_valid(raise_exception=True)
        payload = data.validated_data
        device = self.device
        task_results = sync.upsert_tasks(device, payload["tasks"])
        now = timezone.now()
        events, nicks = [], [(n["eksi_id"], n["nick"], now) for n in payload["nicks"]]
        for e in payload["events"]:
            events.append(
                NewEvent(
                    event_uuid=e["id"],
                    account_id=device.account_id,
                    device_id=device.pk,
                    task_id=e.get("task_id"),
                    actor_eksi_id=e["actor_eksi_id"],
                    target_eksi_id=e["target_eksi_id"],
                    kind=e["kind"],
                    direction=e["direction"],
                    result=e["result"],
                    occurred_at=e["occurred_at"],
                )
            )
            if e.get("actor_nick"):
                nicks.append((e["actor_eksi_id"], e["actor_nick"], e["occurred_at"]))
            if e.get("target_nick"):
                nicks.append((e["target_eksi_id"], e["target_nick"], e["occurred_at"]))
        # Past the daily cap the rest wait in the device's outbox for tomorrow:
        # they are not accepted, so the device keeps them.
        # Events already stored are always acknowledged -- a resend after a lost
        # response would otherwise be retried forever -- and only new ones count.
        today = istanbul_today()
        stored = set(
            Event.objects.filter(event_uuid__in=[e.event_uuid for e in events]).values_list(
                "event_uuid", flat=True
            )
        )
        fresh = [e for e in events if e.event_uuid not in stored]
        allowed = sync.event_allowance(device, len(fresh), today)
        capped = allowed < len(fresh)
        result = ingest([e for e in events if e.event_uuid in stored] + fresh[:allowed], nicks)
        sync.count_events(device, result.inserted, today)
        sync.record_gap(device, payload["gap"])
        return Response(
            {
                "tasks": task_results,
                "events_accepted": [str(u) for u in result.accepted],
                "capped": capped,
                "cursor": Account.objects.values_list("change_seq", flat=True).get(
                    pk=device.account_id
                ),
            }
        )


def _int_param(request, name, default, minimum=0):
    raw = request.query_params.get(name)
    if raw in (None, ""):
        return default
    try:
        value = int(raw)
    except ValueError:
        raise ParamError(name)
    if value < minimum:
        raise ParamError(name)
    return value


class ParamError(Exception):
    pass


def _bad(name):
    return Response({"error": f"invalid {name}"}, status=status.HTTP_400_BAD_REQUEST)


class PullView(DeviceView):
    def get(self, request):
        try:
            cursor = _int_param(request, "cursor", 0)
            limit = min(_int_param(request, "limit", 500, minimum=1), 500)
        except ParamError as e:
            return _bad(e.args[0])
        return Response(sync.pull(self.device, cursor, limit))


class TaskCancelView(DeviceView):
    def post(self, request, task_id):
        task = sync.request_cancel(self.device, task_id)
        if task is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        return Response(sync.serialise_task(task, self.device))


class TaskTakeoverView(DeviceView):
    def post(self, request, task_id):
        try:
            task = sync.take_over(self.device, task_id)
        except sync.TakeoverRefused as refused:
            return Response({"error": str(refused)}, status=status.HTTP_409_CONFLICT)
        if task is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        return Response(sync.serialise_task(task, self.device))


class PairStartView(DeviceView):
    def post(self, request):
        code, ttl = sync.start_pairing(self.device)
        return Response(
            {"code": code, "expires_in": int(ttl.total_seconds())},
            status=status.HTTP_201_CREATED,
        )


class PairClaimView(DeviceView):
    throttle_classes = (IPScopedRateThrottle,)
    throttle_scope = "sync_pair"

    def post(self, request):
        data = PairClaimSerializer(data=request.data)
        data.is_valid(raise_exception=True)
        try:
            request_ = sync.claim_pairing(self.device, data.validated_data["code"])
        except sync.PairingFailed as failed:
            return Response({"error": str(failed)}, status=status.HTTP_400_BAD_REQUEST)
        # Accepted, not done: a device of that account has to approve it.
        return Response(
            {"request_id": request_.pk, "status": request_.status},
            status=status.HTTP_202_ACCEPTED,
        )


class PairStatusView(DeviceView):
    """The claimer polls this; the join key comes once, on the first poll after approval."""

    def get(self, request, request_id):
        state, join_key = sync.pairing_status(self.device, request_id)
        if state is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        return Response({"status": state, "join_key": join_key})


class PairRequestsView(DeviceView):
    """Devices waiting to join this account, for the code's owner to approve or refuse."""

    def get(self, request):
        return Response(
            [
                {
                    "id": r.pk,
                    "label": r.claimer.label,
                    "client": r.claimer.client,
                    "created_at": r.created_at.isoformat(),
                }
                for r in sync.pending_pairings(self.device)
            ]
        )


class PairDecideView(DeviceView):
    approve = True

    def post(self, request, request_id):
        try:
            decided = sync.decide_pairing(self.device, request_id, self.approve)
        except sync.PairingFailed as failed:
            return Response({"error": str(failed)}, status=status.HTTP_409_CONFLICT)
        if decided is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        return Response({"status": decided.status})


class PairDenyView(PairDecideView):
    approve = False


class DevicesView(DeviceView):
    """The account's linked devices, so the user can see and remove them."""

    def get(self, request):
        return Response(
            [
                {
                    "id": d.pk,
                    "label": d.label,
                    "client": d.client,
                    "created_at": d.created_at.isoformat(),
                    "last_seen_at": d.last_seen_at.isoformat(),
                    "is_current": d.pk == self.device.pk,
                }
                for d in sync.account_devices(self.device)
            ]
        )


class DeviceRemoveView(DeviceView):
    def delete(self, request, device_id):
        removed, join_key = sync.remove_device(self.device, device_id)
        if not removed:
            return Response(status=status.HTTP_404_NOT_FOUND)
        # Set only when this device's profile needs a new key; keep the old one otherwise.
        return Response({"join_key": join_key})


class OwnHistoryView(DeviceView):
    """The calling account's own events. No client shows it yet."""

    def get(self, request):
        try:
            before = _int_param(request, "before", None, minimum=1)
            limit = min(_int_param(request, "limit", 100, minimum=1), 500)
        except ParamError as e:
            return _bad(e.args[0])
        rows = Event.objects.filter(account_id=self.device.account_id)
        if before:
            rows = rows.filter(id__lt=before)
        rows = list(
            rows.order_by("-id").values(
                "id",
                "event_uuid",
                "task_id",
                "actor_eksi_id",
                "target_eksi_id",
                "kind",
                "direction",
                "result",
                "occurred_at",
            )[:limit]
        )
        nicks = stats.latest_nicks(r["target_eksi_id"] for r in rows)
        results = [
            {
                **r,
                "target_nick": nicks.get(r["target_eksi_id"]),
                "kind": c.name_of(c.KINDS, r["kind"]),
                "direction": c.name_of(c.DIRECTIONS, r["direction"]),
                "result": c.name_of(c.RESULTS, r["result"]),
            }
            for r in rows
        ]
        return Response(
            {
                "results": results,
                "next_before": rows[-1]["id"] if len(rows) == limit else None,
            }
        )


class ChecksView(DeviceView):
    """Account checks asked by the admin's browser, which Ekşi's challenge lets through.

    GET hands out the next due accounts; POST takes back what Ekşi answered, and the
    server classifies it the way its own timer would. Only a device the admin marked
    as checker gets in.
    """

    # The pause the browser keeps between accounts, as the server's timer does.
    DELAY_MS = 2000

    def check_permissions(self, request):
        super().check_permissions(request)
        if not self.device.is_checker:
            self.permission_denied(request, message="not a checker")

    def get(self, request):
        ids = accountcheck.due(accountcheck.candidates(), timezone.now())[:MAX_CHECKS]
        nicks = stats.latest_nicks(ids)
        return Response(
            {
                "accounts": [
                    {"eksi_id": i, "nick": nicks[i]} for i in ids if nicks.get(i)
                ],
                "delay_ms": self.DELAY_MS,
            }
        )

    def post(self, request):
        data = CheckReportSerializer(data=request.data)
        data.is_valid(raise_exception=True)
        wanted = set(accountcheck.candidates())
        recorded = 0
        for r in data.validated_data["results"]:
            if r["eksi_id"] not in wanted:
                continue
            try:
                status_, found_id = accountcheck.classify(
                    r["eksi_id"], r["profile"], r["entries"], set(r["author_ids"])
                )
            except accountcheck.Refused:
                # Not an answer about this account; it stays due.
                continue
            accountcheck.record(r["eksi_id"], r["nick"], status_, found_id, self.device)
            recorded += 1
        if data.validated_data["refused"]:
            # Otherwise a checker Ekşi turns away looks exactly like one that never ran.
            logger.warning(
                "checker device %s: Ekşi refused with HTTP %s after %s answer(s)",
                self.device.pk,
                data.validated_data["refused"],
                len(data.validated_data["results"]),
            )
        return Response({"recorded": recorded})


class AccountView(DeviceView):
    def delete(self, request):
        sync.delete_account(self.device.account_id)
        return Response(status=status.HTTP_204_NO_CONTENT)


# --- admin stats -----------------------------------------------------------------


class StatsView(views.APIView):
    permission_classes = (IsAdminUser,)
    authentication_classes = (CsrfExemptSessionAuthentication, BasicAuthentication)


def _kind_param(request, required=True):
    raw = request.query_params.get("kind")
    if raw in (None, ""):
        if required:
            raise ParamError("kind")
        return None
    if raw.upper() not in c.KINDS:
        raise ParamError("kind")
    return c.KINDS[raw.upper()]


def _bool_param(request, name, default):
    raw = request.query_params.get(name)
    if raw in (None, ""):
        return default
    if raw.lower() in ("1", "true", "yes"):
        return True
    if raw.lower() in ("0", "false", "no"):
        return False
    raise ParamError(name)


class TopView(StatsView):
    def get(self, request):
        try:
            kind = _kind_param(request)
            scope = request.query_params.get("scope", "current")
            if scope not in ("current", "all", "window"):
                raise ParamError("scope")
            days = _int_param(request, "days", 30, minimum=1)
            limit = _int_param(request, "limit", 50, minimum=1)
            include_legacy = _bool_param(request, "include_legacy", True)
        except ParamError as e:
            return _bad(e.args[0])
        return Response(
            {
                "kind": c.name_of(c.KINDS, kind),
                "scope": scope,
                "days": days if scope == "window" else None,
                "results": stats.top(kind, scope, days, limit, include_legacy),
            }
        )


class TimeseriesView(StatsView):
    def get(self, request):
        try:
            kind = _kind_param(request, required=False)
            days = _int_param(request, "days", 90, minimum=1)
        except ParamError as e:
            return _bad(e.args[0])
        return Response({"days": days, "results": stats.timeseries(kind, days)})


class UserStatsView(StatsView):
    """Any read of one person's data. Each one leaves a row in the access log."""

    def initial(self, request, *args, **kwargs):
        super().initial(request, *args, **kwargs)
        AdminAccess.objects.create(
            admin_user=request.user,
            admin_username=request.user.get_username(),
            eksi_id=kwargs["eksi_id"],
            path=request.get_full_path()[:512],
        )


class UserSummaryView(UserStatsView):
    def get(self, request, eksi_id):
        return Response(stats.user_summary(eksi_id))


def _role_param(request):
    role = request.query_params.get("role", "actor")
    if role not in ("actor", "target"):
        raise ParamError("role")
    return role


class UserEventsView(UserStatsView):
    def get(self, request, eksi_id):
        try:
            role = _role_param(request)
            kind = _kind_param(request, required=False)
            before = _int_param(request, "before", None, minimum=1)
            limit = _int_param(request, "limit", 100, minimum=1)
        except ParamError as e:
            return _bad(e.args[0])
        return Response(stats.user_events(eksi_id, role, kind, before, limit))


class UserRelationsView(UserStatsView):
    def get(self, request, eksi_id):
        try:
            role = _role_param(request)
            kind = _kind_param(request, required=False)
            active = _bool_param(request, "active", None)
            before = _int_param(request, "before", None, minimum=1)
            limit = _int_param(request, "limit", 200, minimum=1)
        except ParamError as e:
            return _bad(e.args[0])
        return Response(
            stats.user_relations(eksi_id, role, kind, active, before, limit)
        )


class UserTasksView(UserStatsView):
    def get(self, request, eksi_id):
        try:
            limit = _int_param(request, "limit", 100, minimum=1)
        except ParamError as e:
            return _bad(e.args[0])
        return Response(stats.user_tasks(eksi_id, limit))
