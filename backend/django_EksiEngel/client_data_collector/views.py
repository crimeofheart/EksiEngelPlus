from django.http import HttpResponse
from django.shortcuts import redirect
from django.views.decorators.csrf import csrf_exempt
from django.utils import timezone
from rest_framework import status
from rest_framework.decorators import api_view, authentication_classes, permission_classes
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.authentication import SessionAuthentication, BasicAuthentication
import logging

logger = logging.getLogger(__name__)

from .models import ClientData, BanSource, BanMode, LogLevel
from .models import ClientAnalytic, ClickType
from api.authentication import SharedAPIKeyAuthentication


class CsrfExemptSessionAuthentication(SessionAuthentication):
    """Disable CSRF check for session authentication"""
    def enforce_csrf(self, request):
        return  # To not perform the csrf check


def index(request):
    return HttpResponse("Hello, world. I'm client data collector.")


def _optional(value, max_length):
    """Normalise absent/blank/placeholder client fields to None, clipped to the column."""
    if value is None:
        return None
    text = str(value).strip()
    if not text or text.lower() == "unknown":
        return None
    return text[:max_length]


def _optional_int(value):
    try:
        number = int(value)
    except (TypeError, ValueError):
        return None
    return number or None


@csrf_exempt
@api_view(['POST'])
@authentication_classes([SharedAPIKeyAuthentication, CsrfExemptSessionAuthentication, BasicAuthentication])
@permission_classes([AllowAny])
def upload(request):
    if request.method == 'POST':
        # request.data, not request.POST then request.body.
        #
        # Touching request.POST consumes the input stream, so the fallthrough to
        # request.body raised RawPostDataException -- a 500 -- for every JSON
        # post. Only form-encoded bodies, which populate request.POST and never
        # reach the second branch, could get through at all.
        #
        # DRF has already parsed both encodings by this point, and @api_view
        # turns a malformed body into a 400 before the view is entered, which is
        # what the hand-rolled json.loads was for.
        data = request.data
        if not data:
            return Response('Empty Request', status=status.HTTP_400_BAD_REQUEST)

        try:
            ClientData.objects.create(
                date=timezone.now(),
                user_agent=data.get("user_agent"),
                client_name=data.get("client_name"),
                ban_source=BanSource.objects.get(ban_source=data.get("ban_source")),
                ban_mode=BanMode.objects.get(ban_mode=data.get("ban_mode")),
                fav_entry_id=data.get("fav_entry_id"),
                fav_title_id=data.get("fav_title_id"),
                fav_title_name=data.get("fav_title_name"),
                fav_author_id=data.get("fav_author_id"),
                fav_author_name=data.get("fav_author_name"),
                author_name_list=data.get("author_name_list"),
                author_id_list=data.get("author_id_list"),
                author_list_size=data.get("author_list_size"),
                total_action=data.get("total_action"),
                successful_action=data.get("successful_action"),
                is_early_stopped=data.get("is_early_stopped"),
                log_level=LogLevel.objects.get(log_level=data.get("log_level")),
                log=data.get("log")
            )
            return Response('OK', status=status.HTTP_201_CREATED)
        except Exception as e:
            logger.error(f"ClientData upload error: {str(e)}")
            return Response('Veri işlenirken bir hata oluştu', status=status.HTTP_400_BAD_REQUEST)
    else:
        return Response('Method Not Allowed', status=status.HTTP_405_METHOD_NOT_ALLOWED)


@csrf_exempt
@api_view(['GET', 'POST'])
@authentication_classes([SharedAPIKeyAuthentication, CsrfExemptSessionAuthentication, BasicAuthentication])
def analytics(request):
    # POST is allowed with API key authentication (for extension to send analytics)
    # GET requires admin access (for viewing analytics)
    
    if request.method == 'GET':
        # Analytics live on the admin dashboard now. The POST branch below is untouched:
        # it is the endpoint the extension writes to.
        #
        # request.user may be None, not AnonymousUser: SharedAPIKeyAuthentication
        # returns (None, key), so a request carrying the shared key authenticates
        # without a user at all and `request.user.is_authenticated` raised
        # AttributeError -- a 500 where a 403 was meant. It failed closed, so it
        # never granted anything, but a key holder could crash the endpoint at
        # will.
        user = request.user
        if not (user and user.is_authenticated and user.is_staff):
            return Response('Bu sayfaya erişim yetkiniz yok', status=status.HTTP_403_FORBIDDEN)
        return redirect('admin:index')

    if request.method == 'POST':
        data = request.data
        try:
            click_type_value = data.get("click_type")
            click_type_obj, _ = ClickType.objects.get_or_create(
                click_type=click_type_value if click_type_value else "UNKNOWN"
            )
            ClientAnalytic.objects.create(
                date=timezone.now(),
                # Store NULL, never a placeholder: older builds send none of these, and a
                # row that says "unknown" cannot be told apart from a user named that.
                user_agent=_optional(data.get("user_agent"), 1024),
                client_name=_optional(data.get("client_name"), 96),
                client_uid=_optional_int(data.get("client_uid")),
                version=_optional(data.get("version"), 16),
                click_type=click_type_obj,
            )
            return Response('OK', status=status.HTTP_201_CREATED)
        except Exception as e:
            logger.error(f"Analytics upload error: {str(e)}")
            return Response('Veri işlenirken bir hata oluştu', status=status.HTTP_400_BAD_REQUEST)
    else:
        return Response('Method Not Allowed', status=status.HTTP_405_METHOD_NOT_ALLOWED)
