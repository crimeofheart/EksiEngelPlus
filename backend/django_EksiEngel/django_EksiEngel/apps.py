from django.contrib.admin.apps import AdminConfig


class EksiEngelAdminConfig(AdminConfig):
    default_site = "django_EksiEngel.admin.EksiEngelAdminSite"
