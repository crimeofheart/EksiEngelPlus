import uuid

from django.conf import settings
from django.db import models
from django.utils import timezone

from api.models import CLIENT_CHOICES

from . import constants as c

KIND_CHOICES = [(v, k) for k, v in c.KINDS.items()]
DIRECTION_CHOICES = [(v, k) for k, v in c.DIRECTIONS.items()]
RESULT_CHOICES = [(v, k) for k, v in c.RESULTS.items()]
STATUS_CHOICES = [(v, k) for k, v in c.STATUSES.items()]


class Account(models.Model):
    """A person's group of devices, or the stand-in for one actor's run reports.

    Anonymous on purpose: the server never sees an Ekşi session, so it cannot
    know whose nick a device really belongs to. A device reads what its own
    account wrote and nothing else; the nick on an event is a claim, not a key.
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    kind = models.SmallIntegerField(
        choices=[(c.ACCOUNT_DEVICE, "devices"), (c.ACCOUNT_LEGACY, "run reports")],
        default=c.ACCOUNT_DEVICE,
    )
    # Run reports carry no device, so their events hang off one account per actor.
    legacy_actor_eksi_id = models.IntegerField(unique=True, null=True, blank=True)
    # SHA-256 of the key a browser profile's synced storage carries, letting a
    # second install of the extension join without a pairing code.
    join_key_hash = models.CharField(max_length=64, unique=True, null=True, blank=True)
    # Bumped under this row's lock on every task write; the pull cursor.
    change_seq = models.BigIntegerField(default=0)
    created_at = models.DateTimeField(default=timezone.now)

    def __str__(self):
        if self.kind == c.ACCOUNT_LEGACY:
            return f"run reports of {self.legacy_actor_eksi_id}"
        return f"account {self.id}"


class Device(models.Model):
    account = models.ForeignKey(
        Account, on_delete=models.CASCADE, related_name="devices"
    )
    token_hash = models.CharField(max_length=64, unique=True)
    client = models.CharField(max_length=16, choices=CLIENT_CHOICES)
    label = models.CharField(max_length=64, blank=True, default="")
    version = models.CharField(max_length=16, blank=True, default="")
    created_at = models.DateTimeField(default=timezone.now)
    last_seen_at = models.DateTimeField(default=timezone.now)
    revoked_at = models.DateTimeField(null=True, blank=True)
    # Events the device's outbox had to drop on overflow, as reported by it.
    dropped_events = models.BigIntegerField(default=0)

    def __str__(self):
        return f"{self.label or self.client} ({self.account_id})"


class PairingCode(models.Model):
    code_hash = models.CharField(max_length=64, unique=True)
    account = models.ForeignKey(
        Account, on_delete=models.CASCADE, related_name="pairing_codes"
    )
    expires_at = models.DateTimeField()
    used_at = models.DateTimeField(null=True, blank=True)


class Task(models.Model):
    # The id the enqueuing client generated, so a retried upsert is the same row.
    id = models.UUIDField(primary_key=True, editable=False)
    # Indexed by (account, change_seq) below; a single-column index would be dead weight.
    account = models.ForeignKey(
        Account, on_delete=models.CASCADE, related_name="tasks", db_index=False
    )
    owner_device = models.ForeignKey(
        Device,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="owned_tasks",
    )
    # Fencing token: a takeover bumps it, and writes carrying an older one are refused.
    epoch = models.IntegerField(default=0)
    actor_eksi_id = models.IntegerField(null=True, blank=True)
    ban_source = models.SmallIntegerField()
    ban_mode = models.SmallIntegerField()
    params = models.JSONField(default=dict, blank=True)
    description = models.CharField(max_length=200, blank=True, default="")
    status = models.SmallIntegerField(choices=STATUS_CHOICES, default=c.QUEUED)
    planned = models.IntegerField(default=0)
    performed = models.IntegerField(default=0)
    successful = models.IntegerField(default=0)
    cancel_requested = models.BooleanField(default=False)
    created_at = models.DateTimeField(default=timezone.now)
    updated_at = models.DateTimeField(default=timezone.now)
    change_seq = models.BigIntegerField(default=0)

    class Meta:
        indexes = [
            models.Index(
                fields=["account", "change_seq"], name="history_task_account_seq"
            ),
            models.Index(
                fields=["actor_eksi_id", "-created_at"], name="history_task_actor"
            ),
        ]

    def __str__(self):
        return f"{self.id} {c.name_of(c.STATUSES, self.status)}"


class Event(models.Model):
    """One final outcome for one target and one relation kind. Never updated."""

    id = models.BigAutoField(primary_key=True)
    event_uuid = models.UUIDField(unique=True)
    # Indexed by (account, -id) below.
    account = models.ForeignKey(
        Account, on_delete=models.CASCADE, related_name="events", db_index=False
    )
    # Nothing filters on these three, and an index costs ~30 bytes on every row.
    device = models.ForeignKey(
        Device,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        db_index=False,
        related_name="+",
    )
    # Plain UUID, not a foreign key: an event may arrive before its task does.
    task_id = models.UUIDField(null=True, blank=True)
    action = models.ForeignKey(
        "api.Action",
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        db_index=False,
        related_name="+",
    )
    actor_eksi_id = models.IntegerField()
    target_eksi_id = models.IntegerField()
    kind = models.SmallIntegerField(choices=KIND_CHOICES)
    direction = models.SmallIntegerField(choices=DIRECTION_CHOICES)
    result = models.SmallIntegerField(choices=RESULT_CHOICES)
    occurred_at = models.DateTimeField()
    received_at = models.DateTimeField(default=timezone.now)

    class Meta:
        indexes = [
            models.Index(fields=["account", "-id"], name="history_event_account"),
            models.Index(fields=["actor_eksi_id", "-id"], name="history_event_actor"),
            models.Index(fields=["target_eksi_id", "-id"], name="history_event_target"),
        ]

    def __str__(self):
        return (
            f"{self.actor_eksi_id} {c.name_of(c.DIRECTIONS, self.direction)} "
            f"{c.name_of(c.KINDS, self.kind)} {self.target_eksi_id} "
            f"{c.name_of(c.RESULTS, self.result)}"
        )


class RelationState(models.Model):
    """Whether an actor holds a relation to a target now, and since when it was added.

    Written only by history.ingest, in the transaction that stores the events it
    is derived from; history_rebuild recomputes it from Event to check that.
    """

    id = models.BigAutoField(primary_key=True)
    # The unique key below leads with account, so it doubles as this column's index.
    account = models.ForeignKey(
        Account, on_delete=models.CASCADE, related_name="+", db_index=False
    )
    actor_eksi_id = models.IntegerField()
    target_eksi_id = models.IntegerField()
    kind = models.SmallIntegerField(choices=KIND_CHOICES)
    active = models.BooleanField(default=False)
    last_changed_at = models.DateTimeField(null=True, blank=True)
    last_event_id = models.BigIntegerField(null=True, blank=True)
    first_added_at = models.DateTimeField(null=True, blank=True)
    last_added_at = models.DateTimeField(null=True, blank=True)
    add_count = models.IntegerField(default=0)
    legacy_only = models.BooleanField(default=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["account", "actor_eksi_id", "target_eksi_id", "kind"],
                name="history_relation_key",
            ),
        ]
        indexes = [
            # Every ranking is "distinct actors per target" over one kind. Ordered
            # by (target, actor) and carrying the columns the scopes filter on,
            # this serves them all as an index-only scan with no sort: about 0.45s
            # for 3M relations, against 6-15s through the heap.
            models.Index(
                fields=["kind", "target_eksi_id", "actor_eksi_id"],
                include=["active", "legacy_only", "add_count", "first_added_at"],
                name="history_rel_rank",
            ),
            models.Index(
                fields=["kind", "last_added_at"], name="history_rel_last_added"
            ),
            models.Index(fields=["actor_eksi_id", "kind"], name="history_rel_actor"),
            models.Index(fields=["target_eksi_id", "kind"], name="history_rel_target"),
        ]


class DailyTally(models.Model):
    day = models.DateField()
    kind = models.SmallIntegerField(choices=KIND_CHOICES)
    direction = models.SmallIntegerField(choices=DIRECTION_CHOICES)
    result = models.SmallIntegerField(choices=RESULT_CHOICES)
    n = models.BigIntegerField(default=0)

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["day", "kind", "direction", "result"], name="history_tally_key"
            ),
        ]


class DailyActor(models.Model):
    """Who acted on a day. Distinct counts do not add up across days, so they are kept as rows."""

    day = models.DateField()
    kind = models.SmallIntegerField(choices=KIND_CHOICES)
    account = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="+")
    actor_eksi_id = models.IntegerField()

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["day", "kind", "account", "actor_eksi_id"],
                name="history_daily_actor_key",
            ),
        ]


class NickSeen(models.Model):
    eksi_id = models.IntegerField()
    nick = models.CharField(max_length=96)
    first_seen_at = models.DateTimeField()
    last_seen_at = models.DateTimeField()

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["eksi_id", "nick"], name="history_nick_key"
            ),
        ]
        indexes = [
            models.Index(
                fields=["eksi_id", "-last_seen_at"], name="history_nick_latest"
            )
        ]

    def __str__(self):
        return f"{self.eksi_id} {self.nick}"


class AdminAccess(models.Model):
    """One admin read of one person's history. Append-only."""

    admin_user = models.ForeignKey(
        settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, related_name="+"
    )
    # Kept apart from the foreign key so the trail survives the admin's account.
    admin_username = models.CharField(max_length=150)
    eksi_id = models.IntegerField()
    path = models.CharField(max_length=512)
    at = models.DateTimeField(default=timezone.now)

    class Meta:
        verbose_name = "admin access"
        verbose_name_plural = "admin access log"
        ordering = ("-at",)
