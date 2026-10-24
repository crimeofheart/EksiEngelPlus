from django.contrib import admin, messages
from django.urls import reverse
from django.utils.html import format_html

from . import sync
from .models import Account, AdminAccess, Device, Event, Task


class ReadOnlyAdmin(admin.ModelAdmin):
    """The ledger is written by ingest only; the admin is a lens over it."""

    def has_add_permission(self, request, obj=None):
        return False

    def has_change_permission(self, request, obj=None):
        return False


class DeviceInline(admin.TabularInline):
    model = Device
    extra = 0
    can_delete = False
    fields = (
        "client",
        "label",
        "version",
        "created_at",
        "last_seen_at",
        "revoked_at",
        "dropped_events",
    )
    readonly_fields = fields

    def has_add_permission(self, request, obj=None):
        return False

    def has_change_permission(self, request, obj=None):
        return False


@admin.register(Account)
class AccountAdmin(ReadOnlyAdmin):
    inlines = (DeviceInline,)
    list_display = ("id", "kind", "legacy_actor_eksi_id", "created_at", "change_seq")
    list_filter = ("kind",)
    search_fields = ("=legacy_actor_eksi_id",)
    actions = ("erase",)
    readonly_fields = ("id", "kind", "legacy_actor_eksi_id", "created_at", "change_seq")
    exclude = ("join_key_hash",)

    @admin.action(description="Erase selected accounts and everything they sent")
    def erase(self, request, queryset):
        # The same path DELETE /api/sync/account takes, so the tallies are
        # corrected too. Django's own delete would leave them counting the dead.
        ids = list(queryset.values_list("pk", flat=True))
        for pk in ids:
            sync.delete_account(pk)
        self.message_user(request, f"Erased {len(ids)} account(s).", messages.SUCCESS)

    def has_delete_permission(self, request, obj=None):
        # Through `erase` only.
        return False

    def get_actions(self, request):
        actions = super().get_actions(request)
        actions.pop("delete_selected", None)
        if request.user.is_superuser:
            return actions
        actions.pop("erase", None)
        return actions


@admin.register(Task)
class TaskAdmin(ReadOnlyAdmin):
    list_display = (
        "id",
        "description",
        "status",
        "planned",
        "performed",
        "successful",
        "actor_eksi_id",
        "epoch",
        "created_at",
        "updated_at",
    )
    list_filter = ("status",)
    search_fields = ("=actor_eksi_id",)
    raw_id_fields = ("account", "owner_device")

    def has_delete_permission(self, request, obj=None):
        return False


@admin.register(Event)
class EventAdmin(ReadOnlyAdmin):
    list_display = (
        "id",
        "occurred_at",
        "actor_eksi_id",
        "kind",
        "direction",
        "target_eksi_id",
        "result",
        "received_at",
    )
    list_filter = ("kind", "direction", "result")
    search_fields = ("=actor_eksi_id", "=target_eksi_id")
    raw_id_fields = ("account", "device", "action")
    # The table grows without bound; an exact count on every page view would not.
    show_full_result_count = False
    ordering = ("-id",)

    def has_delete_permission(self, request, obj=None):
        return False


@admin.register(AdminAccess)
class AdminAccessAdmin(ReadOnlyAdmin):
    list_display = ("at", "admin_username", "eksi_id", "path")
    list_filter = ("admin_username",)
    search_fields = ("=eksi_id", "admin_username")
    date_hierarchy = "at"

    def has_delete_permission(self, request, obj=None):
        return False


def history_links(eksi_id):
    """Links from an Ekşi user's admin page to their ledger history."""
    if not eksi_id:
        return "—"
    links = [
        ("summary", reverse("stats_user", args=[eksi_id])),
        (
            "blocks now",
            reverse("stats_user_relations", args=[eksi_id])
            + "?role=actor&kind=BLOCK&active=true",
        ),
        (
            "blocked by",
            reverse("stats_user_relations", args=[eksi_id])
            + "?role=target&kind=BLOCK&active=true",
        ),
        ("history", reverse("stats_user_events", args=[eksi_id]) + "?role=actor"),
        ("tasks", reverse("stats_user_tasks", args=[eksi_id])),
    ]
    return format_html(
        " · ".join(['<a href="{}">{}</a>'] * len(links)),
        *[part for label, url in links for part in (url, label)],
    )
