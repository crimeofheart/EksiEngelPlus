from django.db import migrations, models


def clear_sentinels(apps, schema_editor):
    """Old rows recorded "unknown"/0 for fields the extension never sent.

    Now that the columns are nullable, collapse the sentinels to NULL so a genuine
    value and a missing one stop looking the same.
    """
    ClientAnalytic = apps.get_model("client_data_collector", "ClientAnalytic")
    ClientAnalytic.objects.filter(user_agent__in=("unknown", "")).update(user_agent=None)
    ClientAnalytic.objects.filter(client_name__in=("unknown", "")).update(client_name=None)
    ClientAnalytic.objects.filter(client_uid=0).update(client_uid=None)


class Migration(migrations.Migration):

    dependencies = [
        ("client_data_collector", "0008_alter_clientanalytic_options"),
    ]

    operations = [
        migrations.AddField(
            model_name="clientanalytic",
            name="version",
            field=models.CharField(blank=True, max_length=16, null=True),
        ),
        migrations.AlterField(
            model_name="clientanalytic",
            name="user_agent",
            field=models.CharField(blank=True, max_length=1024, null=True),
        ),
        migrations.AlterField(
            model_name="clientanalytic",
            name="client_name",
            field=models.CharField(blank=True, max_length=96, null=True),
        ),
        migrations.AlterField(
            model_name="clientanalytic",
            name="client_uid",
            field=models.BigIntegerField(blank=True, null=True),
        ),
        migrations.RunPython(clear_sentinels, migrations.RunPython.noop),
    ]
