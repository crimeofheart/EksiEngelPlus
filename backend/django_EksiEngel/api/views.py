from django.db import transaction
from django.db.models import Count, Exists, OuterRef, Q, F
from rest_framework import generics
from rest_framework import views, status
from rest_framework.permissions import AllowAny, IsAdminUser, IsAuthenticated
from rest_framework.throttling import AnonRateThrottle, UserRateThrottle
from rest_framework.response import Response
from django.http import HttpResponse
from rest_framework.pagination import PageNumberPagination
from history import stats as history_stats
from .serializers import CollectActionDataSerializer, EksiSozlukUserStatViewSerializer, WriteActionViewSerializer, TotalActionViewSerializer
from .models import FAILED_ACTION, Action, ActionConfig, EksiSozlukUser
from rest_framework.authentication import SessionAuthentication, BasicAuthentication 
from django.db.models.functions import TruncDay
from django.shortcuts import render
from .authentication import SharedAPIKeyAuthentication

class CsrfExemptSessionAuthentication(SessionAuthentication):

    def enforce_csrf(self, request):
        return  # To not perform the csrf check previously happening

class WhereIsEksiSozlukView(views.APIView):
    permission_classes = [AllowAny]
    authentication_classes = (CsrfExemptSessionAuthentication, BasicAuthentication)

    def get(self, request, format=None):
        return HttpResponse("https://eksisozluk.com")
        
class CollectActionDataView(views.APIView):
    permission_classes = [AllowAny]
    authentication_classes = (SharedAPIKeyAuthentication, CsrfExemptSessionAuthentication)
    throttle_classes = [AnonRateThrottle, UserRateThrottle]
    
    # for debug
    def get(self, request, format=None):
        serializer = CollectActionDataSerializer(None, many=False)
        return Response(serializer.data)
        
    def post(self, request, format=None):
        serializer = CollectActionDataSerializer(data=request.data)
        if serializer.is_valid():
            # One transaction for the report and the ledger events derived from it.
            with transaction.atomic():
                serializer.save()
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

class _LegacyRankingView(views.APIView):
    """The pre-ledger ranking routes, same paths and field names, now read from the ledger.

    Counts are per Ekşi id, so ids that had several EksiSozlukUser rows (a nick
    change made a new one) now merge into one. They also count only BLOCK, where
    the old query counted every author_list membership of a BAN-mode run.
    """
    permission_classes = [IsAdminUser]
    unique = False
    count_field = None

    def get(self, request, format=None):
        try:
            limit = int(request.query_params.get("limit", 500))
        except ValueError:
            return Response({"error": "invalid limit"}, status=status.HTTP_400_BAD_REQUEST)
        rows = history_stats.legacy_ranking(self.unique, limit)
        return Response([
            {"eksisozluk_name": nick, "eksisozluk_id": eksi_id, self.count_field: n}
            for eksi_id, nick, n in rows
        ])


class MostBannedUsersUniqueView(_LegacyRankingView):
    authentication_classes = (CsrfExemptSessionAuthentication, BasicAuthentication)
    unique = True
    count_field = "banned_by_unique_count"


class MostBannedUsersView(_LegacyRankingView):
    unique = False
    count_field = "banned_by_count"


class UserStatPagination(PageNumberPagination):
    page_size = 100
    max_page_size = 1000
    page_size_query_param = "page_size"


class EksiSozlukUserStatView(generics.ListAPIView):
    permission_classes = [IsAdminUser]
    serializer_class = EksiSozlukUserStatViewSerializer
    pagination_class = UserStatPagination
    queryset = EksiSozlukUser.objects.order_by("id")

    def list(self, request, *args, **kwargs):
        # Five grouped queries per page instead of five aggregates per row.
        page = self.paginate_queryset(self.get_queryset())
        fields = history_stats.user_stat_fields(u.eksisozluk_id for u in page)
        serializer = self.get_serializer(page, many=True, context={"fields": fields})
        return self.get_paginated_response(serializer.data)

# List last failed actions
class FailedActionsView(generics.ListAPIView):
    permission_classes = [IsAdminUser]
    serializer_class = WriteActionViewSerializer
        
    def get_queryset(self):
        return Action.objects.filter(FAILED_ACTION).order_by('-pk')[:10]
               
# List Total Action Number day by day
class TotalActionView(generics.ListAPIView):
    permission_classes = [IsAdminUser]
    authentication_classes = (CsrfExemptSessionAuthentication, BasicAuthentication)
    serializer_class = TotalActionViewSerializer

    def get_queryset(self):
        return (
            Action.objects.annotate(day=TruncDay('date'))  # Group by day
            .values('day')                                # Select only the day field
            .annotate(total=Count('id'))                 # Count actions per day
            .order_by('day')                             # Sort by day
        )