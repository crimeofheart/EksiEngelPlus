from django.urls import path

from . import views

urlpatterns = [
    path("enroll/", views.EnrollView.as_view(), name="sync_enroll"),
    path("push/", views.PushView.as_view(), name="sync_push"),
    path("pull/", views.PullView.as_view(), name="sync_pull"),
    path("tasks/<uuid:task_id>/cancel/", views.TaskCancelView.as_view(), name="sync_cancel"),
    path(
        "tasks/<uuid:task_id>/takeover/",
        views.TaskTakeoverView.as_view(),
        name="sync_takeover",
    ),
    path("pair/start/", views.PairStartView.as_view(), name="sync_pair_start"),
    path("pair/claim/", views.PairClaimView.as_view(), name="sync_pair_claim"),
    path(
        "pair/claim/<int:request_id>/",
        views.PairStatusView.as_view(),
        name="sync_pair_status",
    ),
    path("pair/requests/", views.PairRequestsView.as_view(), name="sync_pair_requests"),
    path(
        "pair/requests/<int:request_id>/approve/",
        views.PairDecideView.as_view(),
        name="sync_pair_approve",
    ),
    path(
        "pair/requests/<int:request_id>/deny/",
        views.PairDenyView.as_view(),
        name="sync_pair_deny",
    ),
    path("devices/", views.DevicesView.as_view(), name="sync_devices"),
    path("devices/<int:device_id>/", views.DeviceRemoveView.as_view(), name="sync_device"),
    path("history/", views.OwnHistoryView.as_view(), name="sync_history"),
    path("checks/", views.ChecksView.as_view(), name="sync_checks"),
    path("account/", views.AccountView.as_view(), name="sync_account"),
]
