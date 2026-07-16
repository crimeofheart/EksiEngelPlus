from django.contrib import admin
from django.urls import include, path
from django.shortcuts import render, redirect


def privacy_page(request):
    """Serve the privacy policy page."""
    return render(request, 'privacy/index.html')


def landing_page(request):
    """Serve the landing page at root URL."""
    return render(request, 'landing/index.html')


urlpatterns = [
    # Privacy policy page
    path('privacy/', privacy_page, name='privacy'),
    # Landing page at root
    path('', landing_page, name='landing'),
    path("api/", include("api.urls")),
    path("where_is_eksisozluk/", include("where_is_eksisozluk.urls")),

    # The extension POSTs analytics to /admin/api/client_data/analytics and that URL is
    # baked into already-installed builds, so this route must stay, and must stay BEFORE
    # admin.site.urls. See frontend/app/assets/js/config.js.
    path('admin/api/client_data/', include('client_data_collector.urls')),

    # The stats pages that used to live under /admin/api/ are now the dashboard at /admin/.
    path('admin/api/', lambda request: redirect('admin:index'), name='admin_api_index'),
    path('admin/api/client_data_collector/', lambda request: redirect('/admin/client_data_collector/')),

    # Generic admin - must be last
    path('admin/', admin.site.urls),
]
