from django.contrib import admin

from api.admin import LookupAdmin

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


@admin.register(ClientAnalytic)
class ClientAnalyticAdmin(admin.ModelAdmin):
    # The extension only ever sends click_type (see commHandler.sendAnalyticsData), so
    # client_uid, client_name and user_agent hold their placeholder defaults on every
    # row. They are omitted here rather than shown as constant columns.
    list_display = ("date", "click_type")
    list_filter = ("click_type",)
    list_select_related = ("click_type",)
    date_hierarchy = "date"
    ordering = ("-id",)

    def has_add_permission(self, request):
        return False

    def has_change_permission(self, request, obj=None):
        return False
