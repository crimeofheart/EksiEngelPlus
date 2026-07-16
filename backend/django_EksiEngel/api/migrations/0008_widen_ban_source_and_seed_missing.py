from django.db import migrations, models

# frontend/app/assets/js/enums.js sends these as the ban_source pk, so the rows must
# exist or the action POST fails FK validation and the telemetry is dropped. Sources
# 7-14 never made it in: they are longer than ban_source's original max_length of 10.
BAN_SOURCES = [
    (1, "SINGLE"),
    (2, "FAV"),
    (3, "FOLLOW"),
    (4, "LIST"),
    (5, "UNDOBANALL"),
    (6, "TITLE"),
    (7, "BLOCKED_MUTED_TITLES"),
    (8, "MIGRATE_BLOCKED_TO_MUTED"),
    (9, "BLOCK_MUTED_USERS"),
    (10, "REFRESH_MUTED_LIST"),
    (11, "REFRESH_BLOCKED_LIST"),
    (12, "DATE_BASED_BULK"),
    (13, "UNMUTEALL"),
    (14, "REFRESH_FOLLOWED_LIST"),
]


def seed_ban_sources(apps, schema_editor):
    BanSource = apps.get_model("api", "BanSource")
    for pk, value in BAN_SOURCES:
        BanSource.objects.get_or_create(pk=pk, defaults={"ban_source": value})


def noop(apps, schema_editor):
    # Deliberately not deleting: Action.ban_source is PROTECTed and these are referenced.
    pass


class Migration(migrations.Migration):
    dependencies = [
        ("api", "0007_seed_lookup_data"),
    ]

    operations = [
        migrations.AlterField(
            model_name="bansource",
            name="ban_source",
            field=models.CharField(max_length=30),
        ),
        migrations.RunPython(seed_ban_sources, noop),
    ]
