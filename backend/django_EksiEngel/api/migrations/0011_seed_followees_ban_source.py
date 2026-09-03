from django.db import migrations

# frontend/app/assets/js/enums.js sends the ban_source pk, so the row must exist or
# the action POST fails FK validation and the telemetry is dropped. FOLLOWEES is the
# audience "the accounts this author follows" -- unrelated to ClickSource.FOLLOWING,
# which names the page a click came from.
BAN_SOURCES = [
    (15, "FOLLOWEES"),
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
        ("api", "0010_action_client_and_more"),
    ]

    operations = [
        migrations.RunPython(seed_ban_sources, noop),
    ]
