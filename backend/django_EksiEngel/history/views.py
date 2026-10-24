from django.utils import timezone
from rest_framework import status, views
from rest_framework.authentication import BasicAuthentication
from rest_framework.permissions import AllowAny, IsAdminUser
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle

from api.authentication import SharedAPIKeyAuthentication
from api.views import CsrfExemptSessionAuthentication

from . import constants as c
from . import stats, sync
from .authentication import DeviceTokenAuthentication, IsDevice
from .ingest import NewEvent, ingest
from .models import Account, AdminAccess, Event
from .serializers import EnrollSerializer, PairClaimSerializer, PushSerializer
from .throttling import IPScopedRateThrottle


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
        result = ingest(events, nicks)
        sync.record_gap(device, payload["gap"])
        return Response(
            {
                "tasks": task_results,
                "events_accepted": [str(u) for u in result.accepted],
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
            _, join_key = sync.claim_pairing(self.device, data.validated_data["code"])
        except sync.PairingFailed as failed:
            return Response({"error": str(failed)}, status=status.HTTP_400_BAD_REQUEST)
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
