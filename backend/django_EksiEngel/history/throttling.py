from rest_framework.throttling import ScopedRateThrottle


class IPScopedRateThrottle(ScopedRateThrottle):
    """A scoped rate keyed on the client address even for an authenticated device.

    Pairing-code claims must be limited per address: keyed per device, anyone
    could enrol a fresh device for every batch of guesses.
    """

    def get_cache_key(self, request, view):
        return self.cache_format % {"scope": self.scope, "ident": self.get_ident(request)}
