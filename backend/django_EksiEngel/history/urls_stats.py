from django.urls import path

from . import views

# Admin-only. Rankings and anyone's history stay on this side of IsAdminUser.
urlpatterns = [
    path("top/", views.TopView.as_view(), name="stats_top"),
    path("timeseries/", views.TimeseriesView.as_view(), name="stats_timeseries"),
    path("users/<int:eksi_id>/", views.UserSummaryView.as_view(), name="stats_user"),
    path(
        "users/<int:eksi_id>/events/",
        views.UserEventsView.as_view(),
        name="stats_user_events",
    ),
    path(
        "users/<int:eksi_id>/relations/",
        views.UserRelationsView.as_view(),
        name="stats_user_relations",
    ),
    path(
        "users/<int:eksi_id>/tasks/",
        views.UserTasksView.as_view(),
        name="stats_user_tasks",
    ),
]
