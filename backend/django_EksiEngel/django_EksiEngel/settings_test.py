"""Test settings: the production settings with a throwaway database.

`django_EksiEngel.settings` reads SECRET_KEY, SHARED_API_KEY and the DATABASE_*
keys straight from the environment. This module supplies the ones that are not
worth exporting by hand, so the suite runs with one command:

    python manage.py test --settings=django_EksiEngel.settings_test

The database is Postgres, not SQLite, on purpose. Migrations are part of what a
release ships -- `history/migrations/0002_trust_and_person_relations.py` declares
a covering index (`models.Index(..., include=(...))`), which SQLite cannot
create, and runs a raw `RunSQL` backfill. A SQLite run would have to skip
migrations entirely, and then the suite would be green against a schema that
exists nowhere.

Point it at any Postgres with the DATABASE_* variables; the defaults reach a
local server on 5432, which is what the `backend` job in
`.github/workflows/check.yml` provides:

    DATABASE_HOST=127.0.0.1 DATABASE_PORT=55432 DATABASE_PASS=test \\
        python manage.py test --settings=django_EksiEngel.settings_test
"""

import os

os.environ.setdefault("SECRET_KEY", "test-secret-key-not-used-outside-tests")
os.environ.setdefault("DEBUG", "False")
os.environ.setdefault("DATABASE_NAME", "eksiengel_test")
os.environ.setdefault("DATABASE_USER", "eksiengel_test")
os.environ.setdefault("DATABASE_PASS", "eksiengel_test")
# The sync/legacy/stats tests send this literal key; nothing outside the test
# process ever sees it.
os.environ.setdefault("SHARED_API_KEY", "testkey")

from .settings import *  # noqa: F401,F403,E402

# Uploads under test never touch the host's release directory.
RELEASE_DIR = os.environ.get("RELEASE_DIR", "/tmp/eksiengelplus-test-release")
