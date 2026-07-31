"""Report telemetry columns that never carry a value.

The extension has grown fields faster than it has grown senders, so several columns
are null on every row -- either because nothing writes them, or because they only
apply to one ban source. Both are worth knowing, and they look identical from the
admin, so this walks the tables and says which is which.

    python manage.py audit_nulls
    python manage.py audit_nulls --app api --threshold 90
    python manage.py audit_nulls --json
"""

import json

from django.apps import apps
from django.core.management.base import BaseCommand
from django.db.models import Count, Q

DEFAULT_APPS = ("api", "client_data_collector")

# Columns wide enough that a DISTINCT scan is not worth it (Action.log is a megabyte).
WIDE_COLUMN = 1000

TEXTISH = {"CharField", "TextField", "SlugField", "EmailField", "URLField"}


def _empty_filter(field):
    """Q matching the rows where this column carries nothing, or None if it always can."""
    parts = []
    if field.null:
        parts.append(Q(**{f"{field.name}__isnull": True}))
    if field.get_internal_type() in TEXTISH:
        parts.append(Q(**{field.name: ""}))
    if not parts:
        return None
    q = parts[0]
    for part in parts[1:]:
        q |= part
    return q


def _distinct_sample(model, field, limit=3):
    """Up to `limit` distinct values, or None when the column is too wide to scan."""
    if field.get_internal_type() in TEXTISH and (field.max_length or WIDE_COLUMN) >= WIDE_COLUMN:
        return None
    if field.is_relation:
        return None
    values = model.objects.order_by().values_list(field.name, flat=True).distinct()[:limit]
    return list(values)


def audit(app_labels=DEFAULT_APPS, threshold=100.0):
    """One row per column that is empty at or above `threshold` percent."""
    findings = []
    for app_label in app_labels:
        for model in apps.get_app_config(app_label).get_models():
            fields = [f for f in model._meta.concrete_fields if not f.primary_key]
            total = model.objects.count()
            if not fields:
                continue
            if not total:
                findings.append({
                    "model": f"{app_label}.{model.__name__}", "field": "*",
                    "rows": 0, "empty": 0, "empty_pct": 100.0,
                    "verdict": "table is empty", "values": [],
                })
                continue

            # One aggregate for the whole table: a column-per-query loop is what makes
            # this kind of audit too slow to be worth running.
            filters = {f.name: _empty_filter(f) for f in fields}
            counts = model.objects.aggregate(**{
                f"f{i}": Count("pk", filter=filters[f.name])
                for i, f in enumerate(fields) if filters[f.name] is not None
            })

            for i, field in enumerate(fields):
                empty = counts.get(f"f{i}", 0)
                pct = round(100.0 * empty / total, 1)
                values = _distinct_sample(model, field)

                # A column that is always empty and one that is always the same value are
                # the same problem wearing different clothes: it tells you nothing. One
                # row makes every column look constant, so that verdict needs two.
                if empty == total:
                    verdict = "always empty"
                elif total > 1 and values is not None and len(values) == 1:
                    verdict = f"constant: {values[0]!r}"
                elif pct >= threshold:
                    verdict = f"empty on {pct}% of rows"
                else:
                    continue

                findings.append({
                    "model": f"{app_label}.{model.__name__}", "field": field.name,
                    "rows": total, "empty": empty, "empty_pct": pct,
                    # The constant verdict already names the value; repeating it as a
                    # sample would double every one of those lines.
                    "verdict": verdict,
                    "values": [] if empty == total or verdict.startswith("constant") else (values or []),
                })
    return findings


class Command(BaseCommand):
    help = "List telemetry columns that are null/blank on every (or almost every) row."

    def add_arguments(self, parser):
        parser.add_argument("--app", action="append", dest="apps", default=None,
                            help=f"App label to audit; repeatable. Default: {' '.join(DEFAULT_APPS)}")
        parser.add_argument("--threshold", type=float, default=100.0,
                            help="Also report columns empty on at least this percent of rows "
                                 "(default 100). Always-empty and constant columns are "
                                 "reported regardless.")
        parser.add_argument("--json", action="store_true", help="Emit JSON instead of a table.")

    def handle(self, *args, **options):
        findings = audit(options["apps"] or DEFAULT_APPS, options["threshold"])

        if options["json"]:
            self.stdout.write(json.dumps(findings, indent=2, default=str))
            return

        if not findings:
            self.stdout.write(self.style.SUCCESS("Every column carries a value somewhere."))
            return

        width = max(len(f"{f['model']}.{f['field']}") for f in findings)
        for finding in findings:
            column = f"{finding['model']}.{finding['field']}".ljust(width)
            line = f"{column}  {finding['empty']:>8}/{finding['rows']:<8}  {finding['verdict']}"
            if finding["values"]:
                line += f"  e.g. {', '.join(str(v) for v in finding['values'])}"
            style = self.style.ERROR if finding["empty_pct"] == 100.0 else self.style.WARNING
            self.stdout.write(style(line))
        self.stdout.write(f"\n{len(findings)} column(s) at or above the threshold.")
