from django.contrib import admin
from django.urls import reverse
from django.utils.html import format_html
from django.utils.http import urlencode

from api.admin import LookupAdmin, SafeSearchMixin
from api.eksisozluk import eksi_link

from .models import BanSource, BanMode, TargetType, ClickSource, LogLevel, ClientAnalytic, ClickType

# ClientData and Config are deliberately not registered: both are empty and unwritten.
# ClientData is the legacy denormalized predecessor of api.Action, and nothing writes
# Config. The tables are left untouched -- this only keeps them off the admin index.

# This app carries its own copies of the lookup tables that api also defines. Both sets
# are hidden from the index by EksiEngelAdminSite.get_app_list.
admin.site.register(BanSource, LookupAdmin)
admin.site.register(BanMode, LookupAdmin)
admin.site.register(TargetType, LookupAdmin)
admin.site.register(ClickSource, LookupAdmin)
admin.site.register(LogLevel, LookupAdmin)
admin.site.register(ClickType, LookupAdmin)

# Enough to tell the two shipped builds apart. The full user agent stays one column over.
BROWSERS = (("Firefox", "Firefox"), ("Edg/", "Edge"), ("OPR/", "Opera"), ("Chrome", "Chrome"))


class IdentifiedFilter(admin.SimpleListFilter):
    # Events fired before the extension has scraped eksisozluk carry no username. Being
    # able to split those off is what makes the rest of the columns trustworthy.
    title = "identified"
    parameter_name = "identified"

    def lookups(self, request, model_admin):
        return (("yes", "Username known"), ("no", "Anonymous"))

    def queryset(self, request, queryset):
        if self.value() == "yes":
            return queryset.exclude(client_name=None)
        if self.value() == "no":
            return queryset.filter(client_name=None)
        return queryset


@admin.register(ClientAnalytic)
class ClientAnalyticAdmin(SafeSearchMixin, admin.ModelAdmin):
    # Anonymous until v0.1.5: earlier builds posted only click_type, so client_name,
    # client_uid, user_agent and version are null on every row written before then.
    list_display = (
        "date", "click_type", "client_name", "profile_on_eksi", "actions_by_user",
        "version", "browser",
    )
    list_filter = ("click_type", IdentifiedFilter, "version")
    list_select_related = ("click_type",)
    date_hierarchy = "date"
    ordering = ("-id",)
    search_fields = ("client_name", "=client_uid")

    profile_on_eksi = eksi_link(
        "user", name="client_name", description="on ekşi", label="ekşi ↗")

    @admin.display(description="browser")
    def browser(self, obj):
        for needle, label in BROWSERS:
            if needle in (obj.user_agent or ""):
                return label
        return "—"

    @admin.display(description="actions")
    def actions_by_user(self, obj):
        # No FK across apps: the join is by name, which is what the extension sends.
        if not obj.client_name:
            return "—"
        url = reverse("admin:api_action_changelist")
        return format_html('<a href="{}?{}">runs ↗</a>', url, urlencode({"q": obj.client_name}))

    def has_add_permission(self, request):
        return False

    def has_change_permission(self, request, obj=None):
        return False
