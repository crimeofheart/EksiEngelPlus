from django.contrib import admin
from django.db.models import Count, Q
from django.urls import reverse
from django.utils.html import format_html

from .models import BanSource, BanMode, TargetType, ClickSource, LogLevel, TimeSpecifier
from .models import FAILED_ACTION, EksiSozlukUser, Action, ActionConfig, EksiSozlukTitle, EksiSozlukEntry


class LookupAdmin(admin.ModelAdmin):
    # Reference tables. Hidden from the index by EksiEngelAdminSite.get_app_list, but kept
    # registered so they stay reachable by direct URL on the rare occasion one needs editing.
    list_display = ('pk', '__str__',)


admin.site.register(BanSource, LookupAdmin)
admin.site.register(BanMode, LookupAdmin)
admin.site.register(TargetType, LookupAdmin)
admin.site.register(ClickSource, LookupAdmin)
admin.site.register(LogLevel, LookupAdmin)
admin.site.register(TimeSpecifier, LookupAdmin)


class OutcomeFilter(admin.SimpleListFilter):
    title = "outcome"
    parameter_name = "outcome"

    def lookups(self, request, model_admin):
        return (
            ("failed", "Failed (steps missing)"),
            ("early_stopped", "Stopped early by user"),
            ("clean", "Completed cleanly"),
        )

    def queryset(self, request, queryset):
        if self.value() == "failed":
            return queryset.filter(FAILED_ACTION)
        if self.value() == "early_stopped":
            return queryset.filter(is_early_stopped=True)
        if self.value() == "clean":
            return queryset.filter(~FAILED_ACTION, is_early_stopped=False)
        return queryset


class ActionConfigInline(admin.StackedInline):
    model = ActionConfig
    extra = 0
    can_delete = False

    def has_add_permission(self, request, obj=None):
        return False

    def has_change_permission(self, request, obj=None):
        return False


@admin.register(Action)
class ActionAdmin(admin.ModelAdmin):
    # Actions are append-only telemetry posted by the extension, so the admin is a
    # read-only lens over them. Deletion stays available for pruning.
    inlines = (ActionConfigInline,)
    date_hierarchy = "date"
    ordering = ("-id",)
    list_select_related = ("eksi_engel_user", "ban_source", "ban_mode", "log_level")
    list_display = (
        "id", "date", "eksi_engel_user", "ban_source", "ban_mode",
        "funnel", "success_rate", "is_early_stopped", "log_level", "version",
    )
    list_filter = (
        OutcomeFilter, "ban_mode", "ban_source", "log_level",
        "is_early_stopped", "target_type", "click_source", "version",
    )
    search_fields = ("=id", "eksi_engel_user__eksisozluk_name", "=eksi_engel_user__eksisozluk_id")
    raw_id_fields = ("eksi_engel_user", "fav_title", "fav_entry", "fav_author")

    @admin.display(description="success/performed/planned", ordering="successful_action")
    def funnel(self, obj):
        return f"{obj.successful_action}/{obj.performed_action}/{obj.planned_action}"

    @admin.display(description="success rate")
    def success_rate(self, obj):
        if not obj.performed_action:
            return "—"
        return f"{100.0 * obj.successful_action / obj.performed_action:.0f}%"

    @admin.display(description="targets")
    def target_link(self, obj):
        # author_list can hold thousands of rows; link to the filtered changelist
        # instead of rendering every related user inline.
        url = reverse("admin:api_eksisozlukuser_changelist")
        return format_html(
            '<a href="{}?author_list_in_action__id__exact={}">{} authors</a>',
            url, obj.pk, obj.author_list_size,
        )

    def get_fields(self, request, obj=None):
        return [f.name for f in self.model._meta.fields] + ["target_link"]

    def get_readonly_fields(self, request, obj=None):
        return self.get_fields(request, obj)

    def has_add_permission(self, request):
        return False

    def has_change_permission(self, request, obj=None):
        return False


@admin.register(ActionConfig)
class ActionConfigAdmin(admin.ModelAdmin):
    # Telemetry, like Action: the settings the extension had when it ran. Read-only for
    # the same reason, and useful mainly for "how many runs had feature X enabled".
    raw_id_fields = ("action",)
    list_select_related = ("action",)
    list_display = ("pk", "action", "eksi_sozluk_url", "send_data", "enable_mute", "enable_title_ban")
    list_filter = ("send_data", "enable_mute", "enable_title_ban", "enable_noob_ban")
    search_fields = ("=action__id",)

    def has_add_permission(self, request):
        return False

    def has_change_permission(self, request, obj=None):
        return False


@admin.register(EksiSozlukUser)
class EksiSozlukUserAdmin(admin.ModelAdmin):
    # is_eksiengel_user separates actual extension users from the much larger set of
    # scraped block targets. It is the first filter you want on this page.
    list_display = (
        "eksisozluk_name", "eksisozluk_id", "is_eksiengel_user",
        "action_count", "banned_by_count", "last_activity_date", "last_activity_version",
    )
    list_filter = ("is_eksiengel_user", "last_activity_version")
    search_fields = ("eksisozluk_name", "=eksisozluk_id")
    date_hierarchy = "last_activity_date"
    ordering = ("-id",)

    def get_queryset(self, request):
        # distinct=True is required: counting across two multi-valued relations in one
        # query would otherwise multiply the rows together.
        return super().get_queryset(request).annotate(
            _action_count=Count("eksi_engel_user_in_action", distinct=True),
            _banned_by_count=Count(
                "author_list_in_action",
                filter=Q(author_list_in_action__ban_mode__ban_mode="BAN"),
                distinct=True,
            ),
        )

    @admin.display(description="actions run", ordering="_action_count")
    def action_count(self, obj):
        return obj._action_count

    @admin.display(description="times blocked", ordering="_banned_by_count")
    def banned_by_count(self, obj):
        return obj._banned_by_count


@admin.register(EksiSozlukTitle)
class EksiSozlukTitleAdmin(admin.ModelAdmin):
    list_display = ("eksisozluk_id", "eksisozluk_name")
    search_fields = ("eksisozluk_name", "=eksisozluk_id")
    ordering = ("-id",)


@admin.register(EksiSozlukEntry)
class EksiSozlukEntryAdmin(admin.ModelAdmin):
    raw_id_fields = ("eksisozluk_title",)
    list_select_related = ("eksisozluk_title",)
    list_display = ("eksisozluk_id", "eksisozluk_title")
    search_fields = ("=eksisozluk_id", "eksisozluk_title__eksisozluk_name")
    ordering = ("-id",)
