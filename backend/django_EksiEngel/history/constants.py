"""Wire names and stored integers for the history ledger.

The integers are what the tables hold; the names are what clients send and the
API returns. Kinds reuse api.TargetType's primary keys and directions reuse
api.BanMode's, so a run report's target_type and ban_mode map across unchanged.
"""

# Relation kinds -- api.TargetType pks (0007_seed_lookup_data).
BLOCK = 1
TITLE_BLOCK = 2
MUTE = 3
FOLLOW = 4
KINDS = {"BLOCK": BLOCK, "TITLE_BLOCK": TITLE_BLOCK, "MUTE": MUTE, "FOLLOW": FOLLOW}

# Directions -- api.BanMode pks: BAN adds a relation, UNDOBAN removes it.
ADD = 1
REMOVE = 2
DIRECTIONS = {"ADD": ADD, "REMOVE": REMOVE}

# Results. LEGACY is never sent by a client: it marks an event derived from an
# /api/action/ run report, whose per-target outcome was never recorded.
OK = 1
FAIL = 2
SKIPPED = 3
LEGACY = 4
RESULTS = {"OK": OK, "FAIL": FAIL, "SKIPPED": SKIPPED, "LEGACY": LEGACY}
CLIENT_RESULTS = {k: v for k, v in RESULTS.items() if v != LEGACY}

# Results that say what state the relation is in. FAIL says nothing.
STATE_RESULTS = (OK, SKIPPED, LEGACY)

# Task statuses, and how far along each is. A status only ever moves to a
# higher rank, and every terminal status shares the top rank so none replaces
# another.
QUEUED = 1
RUNNING = 2
DONE = 3
FAILED = 4
CANCELLED = 5
STOPPED = 6
STATUSES = {
    "QUEUED": QUEUED, "RUNNING": RUNNING, "DONE": DONE,
    "FAILED": FAILED, "CANCELLED": CANCELLED, "STOPPED": STOPPED,
}
STATUS_RANK = {QUEUED: 0, RUNNING: 1, DONE: 2, FAILED: 2, CANCELLED: 2, STOPPED: 2}
TERMINAL = frozenset(s for s, rank in STATUS_RANK.items() if rank == 2)

ACCOUNT_DEVICE = 1
ACCOUNT_LEGACY = 2

# The calendar the daily aggregates count in. Ekşi Sözlük's users are in Turkey,
# so "a day" means an Istanbul day, not a UTC one.
STATS_TIME_ZONE = "Europe/Istanbul"


def name_of(mapping, value):
    for name, v in mapping.items():
        if v == value:
            return name
    return None
