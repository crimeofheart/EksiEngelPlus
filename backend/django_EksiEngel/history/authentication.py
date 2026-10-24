from rest_framework.authentication import BaseAuthentication, get_authorization_header
from rest_framework.exceptions import AuthenticationFailed
from rest_framework.permissions import BasePermission

from . import sync
from .models import Device

KEYWORD = b"device"


class DevicePrincipal:
    """request.user for a device: authenticated, never staff, keyed for throttling."""

    is_authenticated = True
    is_staff = False
    is_superuser = False
    is_anonymous = False

    def __init__(self, device):
        self.device = device
        self.pk = f"device-{device.pk}"


class DeviceTokenAuthentication(BaseAuthentication):
    """`Authorization: Device <token>` -- the only credential that reads history."""

    def authenticate(self, request):
        parts = get_authorization_header(request).split()
        if not parts or parts[0].lower() != KEYWORD:
            return None
        if len(parts) != 2:
            raise AuthenticationFailed("Malformed device token header")
        device = sync.device_for_token(parts[1].decode(errors="replace"))
        if device is None:
            raise AuthenticationFailed("Unknown or revoked device")
        sync.touch(device)
        return DevicePrincipal(device), device

    def authenticate_header(self, request):
        return "Device"


class IsDevice(BasePermission):
    def has_permission(self, request, view):
        return isinstance(request.auth, Device)
