"""Contrast guard for the admin skin (assets/eksiengel/admin.css).

The skin retints Django's admin by overriding its CSS variables. Two things make that
easy to get subtly wrong, and both have already shipped once:

  * A pale accent on the green header. Django's default accent is pale *because* its
    header is blue; making both green collapsed the site name to 2.07:1.
  * Our unconditional :root loads after dark_mode.css, so any variable we set there and
    forget to restate in our dark block forces its light value into dark mode. That is
    what turned the panel headers white with 1.25:1 text.

Neither is visible from a unit test of the views, so check the numbers directly.
"""

import re
from pathlib import Path

import django
from django.conf import settings
from django.test import SimpleTestCase

ADMIN_CSS = Path(django.__file__).parent / "contrib/admin/static/admin/css"
OUR_CSS = Path(settings.BASE_DIR) / "assets/eksiengel/admin.css"

# WCAG 2.1 AA: 4.5:1 for normal text, 3:1 for large text.
AA_TEXT = 4.5
AA_LARGE = 3.0


def _vars(block):
    return dict(re.findall(r"--([a-z0-9-]+):\s*([^;]+);", block))


def _luminance(hex_color):
    h = hex_color.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    channels = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    channels = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]


def contrast(fg, bg):
    a, b = _luminance(fg), _luminance(bg)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)


class AdminThemeContrastTests(SimpleTestCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        base = _vars((ADMIN_CSS / "base.css").read_text().split(":root")[1].split("\n}")[0])
        dark_mode = _vars(
            (ADMIN_CSS / "dark_mode.css").read_text().split(":root")[1].split("\n    }")[0]
        )
        ours = OUR_CSS.read_text()
        cls.ours_light = _vars(ours.split("@media (prefers-color-scheme: dark)")[0])
        cls.ours_dark = _vars(
            ours.split("@media (prefers-color-scheme: dark)")[1].split("\n}\n}")[0]
        )
        cls.dark_mode_css = dark_mode
        # Load order: base.css -> dark_mode.css -> ours(:root) -> ours(@media dark).
        cls.light = {**base, **cls.ours_light}
        cls.dark = {**base, **dark_mode, **cls.ours_light, **cls.ours_dark}

    def resolve(self, scope, name, seen=()):
        value = scope.get(name)
        if value is None or name in seen:
            return None
        value = value.strip()
        alias = re.fullmatch(r"var\(--([a-z0-9-]+)\)", value)
        if alias:
            return self.resolve(scope, alias.group(1), seen + (name,))
        return value

    def pairs(self, scope):
        """(label, foreground, background, minimum) for every text-bearing surface."""
        checks = [
            ("panel/module header text", "body-quiet-color", "darkened-bg", AA_TEXT),
            ("filter sidebar text", "body-fg", "darkened-bg", AA_TEXT),
            ("breadcrumb text", "breadcrumbs-fg", "breadcrumbs-bg", AA_TEXT),
            ("breadcrumb links", "breadcrumbs-link-fg", "breadcrumbs-bg", AA_TEXT),
            ("body text", "body-fg", "body-bg", AA_TEXT),
            ("quiet text", "body-quiet-color", "body-bg", AA_TEXT),
            ("links", "link-fg", "body-bg", AA_TEXT),
            ("link hover", "link-hover-color", "body-bg", AA_TEXT),
            ("button text", "button-fg", "button-bg", AA_TEXT),
            ("default button text", "button-fg", "default-button-bg", AA_TEXT),
            ("object-tools text", "button-fg", "object-tools-bg", AA_TEXT),
            ("text on selected row", "body-fg", "selected-row", AA_TEXT),
            ("text on selected bg", "body-fg", "selected-bg", AA_TEXT),
        ]
        resolved = []
        for label, fg_var, bg_var, need in checks:
            fg, bg = self.resolve(scope, fg_var), self.resolve(scope, bg_var)
            if fg and bg and fg.startswith("#") and bg.startswith("#"):
                resolved.append((label, fg, bg, need))

        # The header is a gradient; every stop has to carry the branding and nav text.
        header = self.resolve(scope, "header-bg") or ""
        for stop in re.findall(r"#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{3}\b", header):
            for label, var, need in (
                ("site name", "accent", AA_LARGE),
                ("header text", "header-color", AA_TEXT),
                ("header links", "header-link-color", AA_TEXT),
            ):
                fg = self.resolve(scope, var)
                if fg and fg.startswith("#"):
                    resolved.append((f"{label} on header {stop}", fg, stop, need))
        return resolved

    def assert_all_readable(self, scope, mode):
        failures = [
            f"{label}: {contrast(fg, bg):.2f}:1 ({fg} on {bg}), needs {need}:1"
            for label, fg, bg, need in self.pairs(scope)
            if contrast(fg, bg) < need
        ]
        self.assertEqual(failures, [], f"{mode} mode contrast failures:\n  " + "\n  ".join(failures))

    def test_light_mode_meets_aa(self):
        self.assert_all_readable(self.light, "light")

    def test_dark_mode_meets_aa(self):
        self.assert_all_readable(self.dark, "dark")

    def test_no_light_value_leaks_into_dark_mode(self):
        leaked = sorted(
            name
            for name in self.ours_light
            if name in self.dark_mode_css and name not in self.ours_dark
        )
        self.assertEqual(
            leaked,
            [],
            "These are themed by dark_mode.css but overridden in our unconditional :root "
            "without being restated in our dark block, so the light value wins in dark "
            f"mode: {leaked}",
        )
