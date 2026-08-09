from django.db import migrations, models


class Migration(migrations.Migration):
    """
    Tells an app run from an extension run.

    Both clients post the same payload to the same endpoint with the same shared
    key -- by design, so the backend has no special case -- which also meant
    nothing on the row said where it came from. User agents cannot stand in: the
    app's WebView reports a mobile Chrome UA indistinguishable from the extension
    on mobile Chrome.

    Backfill is the default: every row already in the table was posted by the
    extension, and the shipped extension does not send the field.
    """

    dependencies = [
        ('api', '0009_alter_actionconfig_options_and_more'),
    ]

    operations = [
        migrations.AddField(
            model_name='action',
            name='client',
            field=models.CharField(
                blank=True,
                choices=[('EXTENSION', 'browser extension'), ('ANDROID', 'Android app')],
                default='EXTENSION',
                max_length=16,
            ),
        ),
        migrations.AddField(
            model_name='eksisozlukuser',
            name='last_activity_client',
            field=models.CharField(
                blank=True,
                choices=[('EXTENSION', 'browser extension'), ('ANDROID', 'Android app')],
                max_length=16,
                null=True,
            ),
        ),
    ]
