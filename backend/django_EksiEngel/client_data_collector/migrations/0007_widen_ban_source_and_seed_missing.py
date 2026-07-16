from django.db import migrations, models

# Mirrors api/migrations/0008. Sources 7-14 never made it in: they are longer than
# ban_source's original max_length of 10, so the seed migration errored out.
BAN_SOURCES = [
    (1, 'SINGLE'),
    (2, 'FAV'),
    (3, 'FOLLOW'),
    (4, 'LIST'),
    (5, 'UNDOBANALL'),
    (6, 'TITLE'),
    (7, 'BLOCKED_MUTED_TITLES'),
    (8, 'MIGRATE_BLOCKED_TO_MUTED'),
    (9, 'BLOCK_MUTED_USERS'),
    (10, 'REFRESH_MUTED_LIST'),
    (11, 'REFRESH_BLOCKED_LIST'),
    (12, 'DATE_BASED_BULK'),
    (13, 'UNMUTEALL'),
    (14, 'REFRESH_FOLLOWED_LIST'),
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
        ('client_data_collector', '0006_seed_lookup_data'),
    ]

    operations = [
        migrations.AlterField(
            model_name='bansource',
            name='ban_source',
            field=models.CharField(max_length=30),
        ),
        migrations.RunPython(seed_ban_sources, noop),
    ]
