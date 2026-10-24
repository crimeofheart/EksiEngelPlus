"""Turning /api/action/ run reports into ledger events.

A run report says who ran what and which targets it covered, but not how each
target came out, so its events carry result LEGACY. The same function serves the
live path (every new report, in the request's transaction) and the one-time
backfill over the reports already stored. Event ids are derived from the report,
so the two paths can overlap and either can be re-run without adding anything.
"""

import uuid
from collections import Counter

from django.db import IntegrityError, transaction

from api.models import CLIENT_ANDROID

from . import constants as c
from .ingest import NewEvent, ingest
from .models import Account

# Fixed forever: changing it would re-derive every legacy event under a new id.
LEGACY_NAMESPACE = uuid.UUID("6f1d1b5e-6b8e-4c55-9a52-0f3e2a4a1c7e")

# api.BanSource pks (0007, 0008, 0011).
SINGLE, FAV, FOLLOW_SOURCE, LIST, UNDOBANALL, TITLE = 1, 2, 3, 4, 5, 6
FOLLOWEES = 15

# api.TargetType pk -> relation kind. They are the same numbers by design.
TARGET_TYPE_KIND = {1: c.BLOCK, 2: c.TITLE_BLOCK, 3: c.MUTE, 4: c.FOLLOW}

# The named actions of the author list and the date-based bulk run
# (programController.js, the bulkAction switch; background.js, the LIST branch).
# The extension sends the bulk run's as bulk_action, and from this change the
# author list's too. A follow also lifts a block or mute it finds first, but the
# report does not say whether it found one, so only the follow is recorded.
NAMED_ACTIONS = {
    "ENGELLE": [(c.BLOCK, c.ADD)],
    "SESSIZE_AL": [(c.MUTE, c.ADD)],
    "ENGEL_KALDIR": [(c.BLOCK, c.REMOVE)],
    "SESSIZDEN_CIKAR": [(c.MUTE, c.REMOVE)],
    "TAKIP_ET": [(c.FOLLOW, c.ADD)],
    "TAKIPTEN_CIKAR": [(c.FOLLOW, c.REMOVE)],
    "ENGEL_KALDIR_VE_TAKIP_ET": [(c.BLOCK, c.REMOVE), (c.FOLLOW, c.ADD)],
    "SESSIZDEN_CIKAR_VE_TAKIP_ET": [(c.MUTE, c.REMOVE), (c.FOLLOW, c.ADD)],
}


def relations_of(action):
    """The (kind, direction) pairs a run report applied to each of its targets.

    None when the report does not say, which is skipped and counted rather than
    guessed. The restricting branches mirror performOnScrapedUser and the LIST
    branch of background.js processHandler: block unless enableMute, mute if
    enableMute, title-block if enableTitleBan.

    One ambiguity is carried forward deliberately. Author-list reports never said
    which list action ran, so a list follow is indistinguishable from a list
    block and is counted as one -- exactly what most_banned has always done with
    it. Reports from this change on carry the action as bulk_action.
    """
    direction = {1: c.ADD, 2: c.REMOVE}.get(action.ban_mode_id)
    if direction is None:
        return None
    if action.bulk_action in NAMED_ACTIONS:
        return NAMED_ACTIONS[action.bulk_action]
    # The app's target_type is what it did, for every source (ActionReport.kt
    # sends request.targetType). The extension's is not: its audience runs
    # block or mute by the enableMute setting whatever the type says.
    if action.client == CLIENT_ANDROID:
        kind = TARGET_TYPE_KIND.get(action.target_type_id)
        return [(kind, direction)] if kind else None
    source = action.ban_source_id
    if source == SINGLE:
        kind = TARGET_TYPE_KIND.get(action.target_type_id)
        return [(kind, direction)] if kind else None
    if source == FOLLOWEES:
        return [(c.FOLLOW, direction)]
    if source in (FAV, FOLLOW_SOURCE, LIST, TITLE):
        if action.target_type_id == 4:
            return [(c.FOLLOW, direction)]
        config = getattr(action, "action_config", None)
        if config is None:
            return None
        mute = bool(config.enable_mute)
        if source == LIST and direction == c.REMOVE:
            # The LIST undo lifts the block whatever enableMute says.
            pairs = [(c.BLOCK, direction)] + ([(c.MUTE, direction)] if mute else [])
        else:
            pairs = [(c.MUTE if mute else c.BLOCK, direction)]
        if config.enable_title_ban:
            pairs.append((c.TITLE_BLOCK, direction))
        return pairs
    return None


def legacy_account(actor_eksi_id):
    account = Account.objects.filter(legacy_actor_eksi_id=actor_eksi_id).first()
    if account:
        return account
    try:
        with transaction.atomic():
            return Account.objects.create(
                kind=c.ACCOUNT_LEGACY, legacy_actor_eksi_id=actor_eksi_id
            )
    except IntegrityError:
        # Another report for the same actor created it first.
        return Account.objects.get(legacy_actor_eksi_id=actor_eksi_id)


def event_uuid(action_pk, target_eksi_id, kind):
    return uuid.uuid5(LEGACY_NAMESPACE, f"{action_pk}:{target_eksi_id}:{kind}")


def _eksi_id(value):
    try:
        number = int(value)
    except (TypeError, ValueError):
        return None
    return number if number > 0 else None


def events_for(action, authors):
    """(events, nicks, skipped) for one report; `authors` are its author_list users."""
    actor = action.eksi_engel_user
    actor_id = _eksi_id(actor.eksisozluk_id)
    if not authors or actor_id is None:
        return [], [], Counter()
    pairs = relations_of(action)
    if pairs is None:
        return (
            [],
            [],
            Counter({(action.ban_source_id, action.ban_mode_id): len(authors)}),
        )
    account = legacy_account(actor_id)
    events, nicks = [], [(actor_id, actor.eksisozluk_name, action.date)]
    for author in authors:
        target_id = _eksi_id(author.eksisozluk_id)
        if target_id is None:
            continue
        nicks.append((target_id, author.eksisozluk_name, action.date))
        for kind, direction in pairs:
            events.append(
                NewEvent(
                    event_uuid=event_uuid(action.pk, target_id, kind),
                    account_id=account.pk,
                    actor_eksi_id=actor_id,
                    target_eksi_id=target_id,
                    kind=kind,
                    direction=direction,
                    result=c.LEGACY,
                    occurred_at=action.date,
                    task_id=action.task_id,
                    action_id=action.pk,
                )
            )
    return events, nicks, Counter()


def ingest_action(action, authors=None):
    """Write one report's legacy events. Returns (inserted, skipped Counter)."""
    if authors is None:
        authors = list(action.author_list.all())
    events, nicks, skipped = events_for(action, authors)
    if not events:
        return 0, skipped
    return ingest(events, nicks).inserted, skipped
