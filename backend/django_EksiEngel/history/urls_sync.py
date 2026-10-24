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
    path("history/", views.OwnHistoryView.as_view(), name="sync_history"),
    path("account/", views.AccountView.as_view(), name="sync_account"),
]
