from datetime import datetime, timedelta, timezone as dt_timezone

from django.utils import timezone
from rest_framework import serializers

from api.models import CLIENT_CHOICES

from . import constants as c

# Nothing the ledger holds happened before EksiEngel existed.
EARLIEST = datetime(2015, 1, 1, tzinfo=dt_timezone.utc)
# A device clock this far ahead is wrong, not early; its events are stamped now.
MAX_CLOCK_LEAD = timedelta(days=1)

MAX_TASKS = 100
MAX_EVENTS = 500
MAX_NICKS = 1000


class EpochMillis(serializers.IntegerField):
    """UTC milliseconds from a device clock, as an aware datetime.

    A timestamp outside what is possible -- before EksiEngel existed, or more
    than a day ahead -- is stamped with the time it arrived instead of refused.
    Refusing it would refuse the whole batch, every time it is retried, and one
    device with a wrong clock would never get anything into the ledger again.
    """

    def to_internal_value(self, data):
        millis = super().to_internal_value(data)
        now = timezone.now()
        try:
            moment = datetime.fromtimestamp(millis / 1000, tz=dt_timezone.utc)
        except (OverflowError, OSError, ValueError):
            return now
        if moment < EARLIEST or moment > now + MAX_CLOCK_LEAD:
            return now
        return moment


class NameChoice(serializers.ChoiceField):
    """Accepts a wire name, yields the stored integer."""

    def __init__(self, mapping, **kwargs):
        self.mapping = mapping
        super().__init__(choices=list(mapping), **kwargs)

    def to_internal_value(self, data):
        return self.mapping[super().to_internal_value(data)]


class EksiId(serializers.IntegerField):
    def __init__(self, **kwargs):
        super().__init__(min_value=1, max_value=2**31 - 1, **kwargs)


class Nick(serializers.CharField):
    def __init__(self, **kwargs):
        kwargs.setdefault("required", False)
        kwargs.setdefault("allow_null", True)
        kwargs.setdefault("allow_blank", True)
        super().__init__(max_length=96, **kwargs)


class EnrollSerializer(serializers.Serializer):
    client = serializers.ChoiceField(choices=[k for k, _ in CLIENT_CHOICES])
    label = serializers.CharField(
        max_length=64, required=False, allow_blank=True, default=""
    )
    version = serializers.CharField(
        max_length=16, required=False, allow_blank=True, default=""
    )
    join_key = serializers.CharField(
        max_length=128, required=False, allow_null=True, allow_blank=True
    )


class TaskUpsertSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    epoch = serializers.IntegerField(min_value=0, default=0)
    actor_eksi_id = EksiId(required=False, allow_null=True)
    ban_source = serializers.IntegerField(min_value=1, max_value=1000)
    ban_mode = serializers.IntegerField(min_value=1, max_value=2)
    params = serializers.DictField(required=False, default=dict)
    description = serializers.CharField(
        max_length=200, required=False, allow_blank=True, default=""
    )
    status = NameChoice(c.STATUSES)
    planned = serializers.IntegerField(min_value=0, default=0)
    performed = serializers.IntegerField(min_value=0, default=0)
    successful = serializers.IntegerField(min_value=0, default=0)
    created_at = EpochMillis(required=False)


class EventSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    task_id = serializers.UUIDField(required=False, allow_null=True)
    kind = NameChoice(c.KINDS)
    direction = NameChoice(c.DIRECTIONS)
    # LEGACY is the server's own; a client sending it is refused.
    result = NameChoice(c.CLIENT_RESULTS)
    actor_eksi_id = EksiId()
    actor_nick = Nick()
    target_eksi_id = EksiId()
    target_nick = Nick()
    occurred_at = EpochMillis()


class NickSerializer(serializers.Serializer):
    eksi_id = EksiId()
    nick = serializers.CharField(max_length=96)


class PushSerializer(serializers.Serializer):
    tasks = serializers.ListField(
        child=TaskUpsertSerializer(), max_length=MAX_TASKS, required=False, default=list
    )
    events = serializers.ListField(
        child=EventSerializer(), max_length=MAX_EVENTS, required=False, default=list
    )
    nicks = serializers.ListField(
        child=NickSerializer(), max_length=MAX_NICKS, required=False, default=list
    )
    # Events the device's outbox dropped on overflow since its last push.
    gap = serializers.IntegerField(min_value=0, required=False, default=0)


class PairClaimSerializer(serializers.Serializer):
    code = serializers.CharField(max_length=16)
