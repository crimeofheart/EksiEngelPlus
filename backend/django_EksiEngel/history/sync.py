"""Accounts, devices and the task queue they share.

The account row lock is the one serialisation point. Every task write takes it,
bumps account.change_seq and stamps the task with the new value, so commit order
and cursor order are the same thing: a device that has pulled up to N can never
later miss a change numbered below N. A global sequence or a clock would let a
slow transaction commit a lower number after a faster one was already read.
"""

import hashlib
import secrets
from datetime import timedelta

from django.db import connection, transaction
from django.db.models import F
from django.utils import timezone

from api.models import CLIENT_EXTENSION

from . import constants as c
from .ingest import DAY_SQL, RELATION_COLUMNS, RELATION_UPSERT_TAIL, refresh_actor_relations
from .models import (
    Account,
    DailyActor,
    Device,
    Event,
    JoinKey,
    PairingCode,
    PairingRequest,
    RelationState,
    Task,
)

PAIRING_TTL = timedelta(minutes=10)
# An owner unseen this long has gone away, and its RUNNING task may be taken over.
OWNER_STALE_AFTER = timedelta(minutes=15)
# Crockford base32: no I, L, O or U, so a code read aloud cannot be misheard.
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def hash_secret(secret):
    # Tokens are 256 random bits, so a fast hash is enough: there is no
    # dictionary to slow down, only a database dump to make useless.
    return hashlib.sha256(secret.encode()).hexdigest()


def new_secret():
    return secrets.token_urlsafe(32)


def normalise_code(code):
    code = (code or "").upper().replace("-", "").replace(" ", "")
    return code.translate(str.maketrans({"O": "0", "I": "1", "L": "1"}))


def issue_join_key(account_id):
    """(row, key): a new join key for one browser profile. Only the hash is kept."""
    key = new_secret()
    return JoinKey.objects.create(account_id=account_id, key_hash=hash_secret(key)), key


def enroll(client, label="", version="", join_key=None):
    """Create a device, in the join key's account if one is given and valid.

    Returns (device, token, join_key). The token is shown once and only its hash
    is kept. join_key is returned only when a new account was made, since the
    caller already holds it otherwise.
    """
    key_row = None
    if join_key:
        key_row = (
            JoinKey.objects.select_related("account")
            .filter(key_hash=hash_secret(join_key), account__kind=c.ACCOUNT_DEVICE)
            .first()
        )
    new_join_key = None
    if key_row is None:
        key_row, new_join_key = issue_join_key(Account.objects.create().pk)
    token = new_secret()
    device = Device.objects.create(
        account=key_row.account,
        join_key=key_row,
        token_hash=hash_secret(token),
        client=client,
        label=label[:64],
        version=version[:16],
    )
    return device, token, new_join_key


def device_for_token(token):
    return (
        Device.objects.select_related("account")
        .filter(token_hash=hash_secret(token), revoked_at__isnull=True)
        .first()
    )


def touch(device):
    """Bump last_seen_at, at most once a minute -- it is read in whole minutes."""
    now = timezone.now()
    if now - device.last_seen_at >= timedelta(minutes=1):
        Device.objects.filter(pk=device.pk).update(last_seen_at=now)
        device.last_seen_at = now


def _lock_account(account_id):
    return Account.objects.select_for_update().get(pk=account_id)


def _stamp(account, task):
    account.change_seq += 1
    task.change_seq = account.change_seq


# --- tasks -----------------------------------------------------------------------


def merge_status(current, incoming):
    """Forward only; a terminal status never changes, not even to another terminal."""
    if current in c.TERMINAL:
        return current
    if c.STATUS_RANK[incoming] > c.STATUS_RANK[current]:
        return incoming
    return current


def upsert_tasks(device, tasks):
    """Apply a device's task upserts. Returns one result dict per task.

    A result is "ok", "conflict" (stale epoch, or not this device's task, so it
    must stop) or "not_found" (the id belongs to another account; answered the
    same as a missing id, so ids cannot be probed).
    """
    if not tasks:
        return []
    results = []
    with transaction.atomic():
        account = _lock_account(device.account_id)
        existing = {
            t.pk: t
            for t in Task.objects.select_for_update().filter(
                pk__in=[t["id"] for t in tasks]
            )
        }
        for data in sorted(tasks, key=lambda t: str(t["id"])):
            task = existing.get(data["id"])
            if task is not None and task.account_id != account.pk:
                results.append({"id": str(data["id"]), "result": "not_found"})
                continue
            if task is None:
                task = Task(
                    id=data["id"],
                    account=account,
                    owner_device=device,
                    actor_eksi_id=data.get("actor_eksi_id"),
                    ban_source=data["ban_source"],
                    ban_mode=data["ban_mode"],
                    params=data.get("params") or {},
                    description=(data.get("description") or "")[:200],
                    status=data["status"],
                    planned=data.get("planned", 0),
                    performed=data.get("performed", 0),
                    successful=data.get("successful", 0),
                    created_at=data.get("created_at") or timezone.now(),
                )
                _stamp(account, task)
                task.save(force_insert=True)
                existing[task.pk] = task
                results.append(_result(task, "ok"))
                continue
            if data.get("epoch", 0) < task.epoch or task.owner_device_id != device.pk:
                results.append(_result(task, "conflict"))
                continue
            before = (task.status, task.planned, task.performed, task.successful)
            task.status = merge_status(task.status, data["status"])
            task.planned = max(task.planned, data.get("planned", 0))
            task.performed = max(task.performed, data.get("performed", 0))
            task.successful = max(task.successful, data.get("successful", 0))
            if (task.status, task.planned, task.performed, task.successful) != before:
                task.updated_at = timezone.now()
                _stamp(account, task)
                task.save()
            results.append(_result(task, "ok"))
        account.save(update_fields=["change_seq"])
    return results


def _result(task, result):
    return {
        "id": str(task.pk),
        "result": result,
        "epoch": task.epoch,
        "status": c.name_of(c.STATUSES, task.status),
        "cancel_requested": task.cancel_requested,
    }


def pull(device, cursor, limit=500):
    tasks = list(
        Task.objects.select_related("owner_device")
        .filter(account_id=device.account_id, change_seq__gt=cursor)
        .order_by("change_seq")[: limit + 1]
    )
    has_more = len(tasks) > limit
    tasks = tasks[:limit]
    return {
        "tasks": [serialise_task(t, device) for t in tasks],
        "next_cursor": tasks[-1].change_seq if tasks else cursor,
        "has_more": has_more,
    }


def serialise_task(task, device):
    owner = task.owner_device
    return {
        "id": str(task.pk),
        "epoch": task.epoch,
        "is_mine": owner is not None and owner.pk == device.pk,
        "owner": (
            {
                "label": owner.label,
                "client": owner.client,
                "last_seen_at": owner.last_seen_at.isoformat(),
            }
            if owner
            else None
        ),
        "actor_eksi_id": task.actor_eksi_id,
        "ban_source": task.ban_source,
        "ban_mode": task.ban_mode,
        "params": task.params,
        "description": task.description,
        "status": c.name_of(c.STATUSES, task.status),
        "planned": task.planned,
        "performed": task.performed,
        "successful": task.successful,
        "cancel_requested": task.cancel_requested,
        "created_at": task.created_at.isoformat(),
        "updated_at": task.updated_at.isoformat(),
    }


def _own_task_locked(device, task_id):
    """The task, locked, if it belongs to the device's account; else None."""
    account = _lock_account(device.account_id)
    task = (
        Task.objects.select_for_update(of=("self",))
        .select_related("owner_device")
        .filter(pk=task_id, account=account)
        .first()
    )
    return account, task


def request_cancel(device, task_id):
    """Ask the owner to stop. Returns the task, or None when it is not this account's."""
    with transaction.atomic():
        account, task = _own_task_locked(device, task_id)
        if task is None:
            return None
        if task.status not in c.TERMINAL and not task.cancel_requested:
            task.cancel_requested = True
            task.updated_at = timezone.now()
            _stamp(account, task)
            task.save()
            account.save(update_fields=["change_seq"])
        return task


class TakeoverRefused(Exception):
    pass


def take_over(device, task_id, now=None):
    """Make `device` the owner of a waiting or abandoned task.

    The task starts over as a new incarnation: QUEUED, zero counts, epoch + 1.
    The previous owner's next write carries the old epoch and is refused, which
    is what stops a laptop that wakes up mid-run. The new owner re-runs with
    enableOnlyRequiredActions, so whatever was already done comes back SKIPPED.
    """
    now = now or timezone.now()
    with transaction.atomic():
        account, task = _own_task_locked(device, task_id)
        if task is None:
            return None
        if task.status in c.TERMINAL:
            raise TakeoverRefused("finished")
        owner = task.owner_device
        if owner is not None and owner.pk == device.pk:
            return task
        owner_gone = (
            owner is None
            or owner.revoked_at is not None
            or now - owner.last_seen_at >= OWNER_STALE_AFTER
        )
        if task.status == c.RUNNING and not owner_gone:
            raise TakeoverRefused("running")
        task.owner_device = device
        task.epoch += 1
        task.status = c.QUEUED
        task.planned = task.performed = task.successful = 0
        task.cancel_requested = False
        task.updated_at = now
        _stamp(account, task)
        task.save()
        account.save(update_fields=["change_seq"])
        return task


# --- pairing ---------------------------------------------------------------------


def start_pairing(device, now=None):
    now = now or timezone.now()
    code = "".join(secrets.choice(CROCKFORD) for _ in range(8))
    PairingCode.objects.create(
        code_hash=hash_secret(code),
        account_id=device.account_id,
        expires_at=now + PAIRING_TTL,
    )
    return f"{code[:4]}-{code[4:]}", PAIRING_TTL


class PairingFailed(Exception):
    pass


def claim_pairing(device, code, now=None):
    """Ask to join the code's account. Returns the pending PairingRequest.

    Nothing moves yet: a device of that account has to approve (decide_pairing).
    The code is spent on the first claim, so a code read off a screen by someone
    else can be refused, but cannot be tried again.
    """
    now = now or timezone.now()
    with transaction.atomic():
        pairing = (
            PairingCode.objects.select_for_update()
            .filter(code_hash=hash_secret(normalise_code(code)))
            .first()
        )
        if pairing is None or pairing.used_at is not None or pairing.expires_at <= now:
            raise PairingFailed("invalid")
        if pairing.account_id == device.account_id:
            raise PairingFailed("same_account")
        pairing.used_at = now
        pairing.save(update_fields=["used_at"])
        return PairingRequest.objects.create(code=pairing, claimer=device, created_at=now)


def _request_expired(request, now):
    return request.status == PairingRequest.PENDING and now - request.created_at >= PAIRING_TTL


def pending_pairings(device, now=None):
    """Requests waiting for a device of this account to approve or refuse."""
    now = now or timezone.now()
    return [
        r
        for r in PairingRequest.objects.select_related("claimer")
        .filter(code__account_id=device.account_id, status=PairingRequest.PENDING)
        .order_by("created_at")
        if not _request_expired(r, now)
    ]


def decide_pairing(device, request_id, approve, now=None):
    """Approve or refuse a request for this device's account.

    Approving moves the claimer's account into this one -- its devices, tasks,
    history and join keys, so the key the claimer's browser profile already holds
    now enrols into this account. Returns the request, or None when it is not
    this account's.
    """
    now = now or timezone.now()
    with transaction.atomic():
        request = (
            PairingRequest.objects.select_for_update()
            .select_related("code", "claimer")
            .filter(pk=request_id, code__account_id=device.account_id)
            .first()
        )
        if request is None:
            return None
        if request.status != PairingRequest.PENDING or _request_expired(request, now):
            raise PairingFailed("not_pending")
        if not approve:
            request.status = PairingRequest.DENIED
            request.decided_at = now
            request.save(update_fields=["status", "decided_at"])
            return request
        claimer = request.claimer
        if claimer.account_id != device.account_id:
            merge_accounts(source_id=claimer.account_id, target_id=device.account_id)
        claimer.refresh_from_db()
        if claimer.join_key_id is None and claimer.client == CLIENT_EXTENSION:
            # Its profile's key was revoked with a removed device: it needs a new one.
            key_row, request.join_key = issue_join_key(device.account_id)
            Device.objects.filter(pk=claimer.pk).update(join_key=key_row)
        request.status = PairingRequest.APPROVED
        request.decided_at = now
        request.save(update_fields=["status", "decided_at", "join_key"])
        return request


def pairing_status(device, request_id, now=None):
    """(status, join_key) of the claimer's own request; the key is handed over once."""
    now = now or timezone.now()
    with transaction.atomic():
        request = (
            PairingRequest.objects.select_for_update()
            .filter(pk=request_id, claimer=device)
            .first()
        )
        if request is None:
            return None, None
        if _request_expired(request, now):
            return "EXPIRED", None
        join_key = request.join_key or None
        if join_key:
            request.join_key = ""
            request.save(update_fields=["join_key"])
        return request.status, join_key


def account_devices(device):
    return list(
        Device.objects.filter(account_id=device.account_id, revoked_at__isnull=True)
        .order_by("created_at")
    )


def remove_device(device, device_id, now=None):
    """Revoke one of this account's devices. Returns (removed, new join key or None).

    The removed device's join key is revoked too: its profile's synced storage
    still holds it, and would otherwise enrol straight back in. When that is also
    the profile the removing device sits on, the remover gets a new key, which is
    the one returned.
    """
    now = now or timezone.now()
    with transaction.atomic():
        account = _lock_account(device.account_id)
        removed = (
            Device.objects.select_for_update()
            .filter(pk=device_id, account=account, revoked_at__isnull=True)
            .first()
        )
        if removed is None:
            return False, None
        Device.objects.filter(pk=removed.pk).update(revoked_at=now)
        if removed.join_key_id is None:
            return True, None
        JoinKey.objects.filter(pk=removed.join_key_id).delete()
        if removed.pk == device.pk or device.join_key_id != removed.join_key_id:
            return True, None
        key_row, join_key = issue_join_key(account.pk)
        Device.objects.filter(pk=device.pk).update(join_key=key_row)
        return True, join_key


# A device cannot settle more than this many targets a day: Ekşi allows about
# twelve actions a minute, which is under 17,300 a day. Past it, events wait in the
# device's outbox for the next day instead of filling the database.
DAILY_EVENT_CAP = 20_000


def event_allowance(device, wanted, today):
    """How many of `wanted` new events the device may still send today."""
    fresh = Device.objects.values_list("events_day", "events_today").get(pk=device.pk)
    used = fresh[1] if fresh[0] == today else 0
    return max(0, min(wanted, DAILY_EVENT_CAP - used))


def count_events(device, inserted, today):
    if inserted <= 0:
        return
    updated = Device.objects.filter(pk=device.pk, events_day=today).update(
        events_today=F("events_today") + inserted
    )
    if not updated:
        Device.objects.filter(pk=device.pk).update(events_day=today, events_today=inserted)


def merge_accounts(source_id, target_id):
    """Re-parent every row of `source` into `target`, then delete `source`."""
    first, second = sorted([source_id, target_id], key=str)
    list(
        Account.objects.select_for_update()
        .filter(pk__in=[first, second])
        .order_by("pk")
    )
    target = Account.objects.get(pk=target_id)
    Device.objects.filter(account_id=source_id).update(account_id=target_id)
    # Every profile that could enrol into the old account now enrols into this one.
    JoinKey.objects.filter(account_id=source_id).update(account_id=target_id)
    Event.objects.filter(account_id=source_id).update(account_id=target_id)
    for task in Task.objects.filter(account_id=source_id).order_by("change_seq"):
        _stamp(target, task)
        Task.objects.filter(pk=task.pk).update(
            account_id=target_id, change_seq=task.change_seq
        )
    target.save(update_fields=["change_seq"])
    with connection.cursor() as cursor:
        moved_keys = _relation_keys(cursor, source_id)
        # Same merge rules as ingest: a key both accounts hold combines the two rows.
        cursor.execute(
            f"INSERT INTO history_relationstate AS rs ({RELATION_COLUMNS})"
            f" SELECT %s, {RELATION_COLUMNS.split(', ', 1)[1]}"
            " FROM history_relationstate WHERE account_id = %s"
            " ORDER BY actor_eksi_id, target_eksi_id, kind"
            f" {RELATION_UPSERT_TAIL}",
            [target_id, source_id],
        )
        cursor.execute(
            "DELETE FROM history_relationstate WHERE account_id = %s", [source_id]
        )
        # One row fewer per key the two accounts shared: the per-person count changes.
        refresh_actor_relations(cursor, moved_keys)
        cursor.execute(
            "INSERT INTO history_dailyactor (day, kind, account_id, actor_eksi_id)"
            " SELECT day, kind, %s, actor_eksi_id FROM history_dailyactor"
            " WHERE account_id = %s ORDER BY 1, 2, 4"
            " ON CONFLICT (day, kind, account_id, actor_eksi_id) DO NOTHING",
            [target_id, source_id],
        )
        cursor.execute(
            "DELETE FROM history_dailyactor WHERE account_id = %s", [source_id]
        )
    Account.objects.filter(pk=source_id).delete()


# --- deletion --------------------------------------------------------------------


def delete_account(account_id):
    """Erase an account and take its events back out of the daily tally.

    The tally holds no identifiers, but leaving the account's counts in it would
    make the rebuild disagree with what is stored, and "deleted" should mean the
    numbers no longer include you.
    """
    with transaction.atomic():
        _lock_account(account_id)
        with connection.cursor() as cursor:
            cursor.execute(
                "UPDATE history_dailytally AS t SET n = t.n - s.n FROM ("
                f" SELECT {DAY_SQL} AS day, kind, direction, result, count(*) AS n"
                " FROM history_event WHERE account_id = %s GROUP BY 1, 2, 3, 4"
                " ORDER BY 1, 2, 3, 4) AS s"
                " WHERE t.day = s.day AND t.kind = s.kind"
                " AND t.direction = s.direction AND t.result = s.result",
                [account_id],
            )
            cursor.execute("DELETE FROM history_dailytally WHERE n <= 0")
            dropped_keys = _relation_keys(cursor, account_id)
        # Explicit deletes, largest first, so none of them goes through the
        # collector's per-row path.
        Event.objects.filter(account_id=account_id)._raw_delete(using="default")
        DailyActor.objects.filter(account_id=account_id)._raw_delete(using="default")
        RelationState.objects.filter(account_id=account_id)._raw_delete(using="default")
        # What the person holds now comes from their other accounts, if any.
        with connection.cursor() as cursor:
            refresh_actor_relations(cursor, dropped_keys)
        Task.objects.filter(account_id=account_id).delete()
        Account.objects.filter(pk=account_id).delete()


def _relation_keys(cursor, account_id):
    cursor.execute(
        "SELECT actor_eksi_id, target_eksi_id, kind FROM history_relationstate"
        " WHERE account_id = %s",
        [account_id],
    )
    return [tuple(row) for row in cursor.fetchall()]


def record_gap(device, dropped):
    if dropped > 0:
        Device.objects.filter(pk=device.pk).update(
            dropped_events=F("dropped_events") + dropped
        )
