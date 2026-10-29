from django.db import migrations


class Migration(migrations.Migration):
    """Separate from 0003: Postgres refuses to alter a table in the transaction
    that queued foreign-key checks against it, as 0003's key rows do."""

    dependencies = [
        ("history", "0003_join_key_per_profile"),
    ]

    operations = [
        migrations.RemoveField(
            model_name="account",
            name="join_key_hash",
        ),
    ]
