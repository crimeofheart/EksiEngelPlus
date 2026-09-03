from django.db import migrations

# Mirrors api/migrations/0011. FOLLOWEES is the audience "the accounts this author
# follows" -- unrelated to ClickSource.FOLLOWING, which names the page a click came from.
BAN_SOURCES = [
    (15, 'FOLLOWEES'),
]


def seed_ban_sources(apps, schema_editor):
    BanSource = apps.get_model('client_data_collector', 'BanSource')
    for pk, value in BAN_SOURCES:
        BanSource.objects.get_or_create(pk=pk, defaults={'ban_source': value})


def noop(apps, schema_editor):
    # Deliberately not deleting: ClientData.ban_source is PROTECTed.
    pass


class Migration(migrations.Migration):
    dependencies = [
        ('client_data_collector', '0009_clientanalytic_identity'),
    ]

    operations = [
        migrations.RunPython(seed_ban_sources, noop),
    ]
