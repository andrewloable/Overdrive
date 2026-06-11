#!/usr/bin/env python3
"""
Generate Android strings.xml vehicle-view entries from web i18n JSON bundles.

Usage:
    python3 tools/i18n/generate_vehicle_strings.py

Idempotent: replaces the BEGIN/END GENERATED block on every run.
Run from the project root (BladeWatch/).
"""

import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).parent.parent.parent
I18N_DIR = ROOT / "app/src/main/assets/web/i18n"
RES_DIR = ROOT / "app/src/main/res"

LOCALE_QUALIFIER = {
    "de":    "values-de",
    "en":    "values",
    "es":    "values-es",
    "fr":    "values-fr",
    "hi":    "values-hi",
    "it":    "values-it",
    "ja":    "values-ja",
    "ko":    "values-ko",
    "nb":    "values-nb",
    "nl":    "values-nl",
    "pt-BR": "values-pt-rBR",
    "ru":    "values-ru",
    "th":    "values-th",
    "tr":    "values-tr",
    "vi":    "values-vi",
    "zh-CN": "values-zh-rCN",
    "zh-TW": "values-zh-rTW",
}

# (android_resource_name, web_i18n_dotpath or None)
# Order matters: preserved in output XML.
# Entries with web_i18n_dotpath=None are English-only (not in locale files).
KEY_MAP = [
    # ─── Tab labels ───────────────────────────────────────────────────────────
    ("vehicle_tab_trunk",               "vehicle.tab_trunk"),
    ("vehicle_tab_climate",             "vehicle.tab_climate"),
    ("vehicle_tab_seats",               "vehicle.tab_seats"),
    ("vehicle_tab_windows",             None),            # no web key
    ("vehicle_tab_lights",              "vehicle.tab_lights"),
    ("vehicle_tab_adas",                "vehicle.tab_adas"),
    ("vehicle_control_charging_tab",    "vehicle_control.charging_tab"),

    # ─── Status card ──────────────────────────────────────────────────────────
    ("vehicle_locked",                  "vehicle.locked"),
    ("vehicle_unlocked",                "vehicle.unlocked"),
    ("vehicle_range_label",             None),            # "Range"

    # ─── Error / system ───────────────────────────────────────────────────────
    ("vehicle_data_unavailable",        None),
    ("vehicle_action_failed",           None),

    # ─── Trunk panel ──────────────────────────────────────────────────────────
    ("vehicle_open_trunk",              None),
    ("vehicle_close_trunk",             "vehicle.close_trunk"),
    ("vehicle_trunk_info_open",         None),

    # ─── Climate panel ────────────────────────────────────────────────────────
    ("vehicle_ac_on",                   "vehicle.ac_on"),
    ("vehicle_ac_off",                  "vehicle.ac_off"),
    ("vehicle_max_cooling_on",          None),
    ("vehicle_max_cooling_off",         None),
    ("vehicle_temp_label",              None),
    ("vehicle_fan_speed_label",         None),
    ("vehicle_fan_level",               None),            # "Level %1$d"
    ("vehicle_inside_temp_fmt",         None),            # "Inside: %1$.1f°C"

    # ─── Seats panel ──────────────────────────────────────────────────────────
    ("vehicle_seat_driver",             None),
    ("vehicle_seat_passenger",          None),
    ("vehicle_seat_no_controls",        None),
    ("vehicle_seat_heat_label",         None),            # "Heat %1$s"
    ("vehicle_seat_cool_label",         None),            # "Cool %1$s"
    ("vehicle_heat_off",                None),            # "(Off)"
    ("vehicle_heat_low",                None),            # "(Low)"
    ("vehicle_heat_high",               None),            # "(High)"
    ("vehicle_seat_pos_1",              "vehicle.seat_pos_1"),
    ("vehicle_seat_pos_2",              "vehicle.seat_pos_2"),

    # ─── Windows panel ────────────────────────────────────────────────────────
    ("vehicle_all_windows",             "vehicle.all_windows"),
    ("vehicle_window_front_left",       None),
    ("vehicle_window_front_right",      None),
    ("vehicle_window_rear_left",        None),
    ("vehicle_window_rear_right",       None),
    ("vehicle_window_close",            None),
    ("vehicle_window_close_vent",       None),
    ("vehicle_window_vent_12",          None),
    ("vehicle_window_open_all",         None),
    ("vehicle_sunroof",                 "vehicle.sunroof"),
    ("vehicle_sunshade",                "vehicle.sunshade"),

    # ─── Lights panel ─────────────────────────────────────────────────────────
    ("vehicle_btn_drl_title",           "vehicle.btn_drl_title"),
    ("vehicle_btn_slw_title",           "vehicle.btn_slw_title"),

    # ─── Charging panel ───────────────────────────────────────────────────────
    ("vehicle_control_section_charge_cap", "vehicle_control.section_charge_cap"),
    ("vehicle_charge_cap_not_supported",   None),
    ("vehicle_charge_limit_label",         None),
    ("vehicle_enable_charge_limit",        None),
    ("vehicle_charge_limit_range",         None),

    # ─── Tyre overlay ─────────────────────────────────────────────────────────
    ("vehicle_tyre_no_signal",          "vehicle.tyre_no_signal"),
    ("vehicle_tyre_slow_leak",          "vehicle.tyre_slow_leak"),
    ("vehicle_tyre_fast_leak",          "vehicle.tyre_fast_leak"),
    ("vehicle_tyre_low",                "vehicle.tyre_low"),
    ("vehicle_tyre_high",               "vehicle.tyre_high"),
    ("vehicle_tyre_ok",                 "vehicle.tyre_ok"),
    ("vehicle_tyre_check_pressure",     None),

    # ─── Toggle pill ──────────────────────────────────────────────────────────
    ("vehicle_toggle_on",               None),            # "ON"
    ("vehicle_toggle_off",              None),            # "OFF"

    # ─── Client-side error fallbacks ─────────────────────────────────────────
    ("vehicle_err_climate_control",     None),
    ("vehicle_err_max_cooling",         None),
    ("vehicle_err_drl_control",         None),
    ("vehicle_err_slw_control",         None),
    ("vehicle_err_charge_limit_toggle", None),
]

# English values for keys that have no web i18n entry.
EN_FALLBACK = {
    "vehicle_tab_windows":              "Windows",
    "vehicle_range_label":              "Range",
    "vehicle_data_unavailable":         "Vehicle data unavailable.",
    "vehicle_action_failed":            "Action failed. Check vehicle connection.",
    "vehicle_open_trunk":               "Open Trunk",
    "vehicle_trunk_info_open":          "Opening the trunk will unlock the car first.",
    "vehicle_max_cooling_on":           "Max Cooling: ON",
    "vehicle_max_cooling_off":          "Max Cooling: OFF",
    "vehicle_temp_label":               "Temperature",
    "vehicle_fan_speed_label":          "Fan Speed",
    "vehicle_fan_level":                "Level %1$d",
    "vehicle_inside_temp_fmt":          "Inside: %1$.1f°C",
    "vehicle_seat_driver":              "Driver",
    "vehicle_seat_passenger":           "Passenger",
    "vehicle_seat_no_controls":         "No seat controls available for this vehicle.",
    "vehicle_seat_heat_label":          "Heat %1$s",
    "vehicle_seat_cool_label":          "Cool %1$s",
    "vehicle_heat_off":                 "(Off)",
    "vehicle_heat_low":                 "(Low)",
    "vehicle_heat_high":                "(High)",
    "vehicle_window_front_left":        "Front Left",
    "vehicle_window_front_right":       "Front Right",
    "vehicle_window_rear_left":         "Rear Left",
    "vehicle_window_rear_right":        "Rear Right",
    "vehicle_window_close":             "Close",
    "vehicle_window_close_vent":        "Close Vent",
    "vehicle_window_vent_12":           "Vent 12%%",
    "vehicle_window_open_all":          "Open All",
    "vehicle_charge_cap_not_supported": "Charge cap is not supported by this vehicle.",
    "vehicle_charge_limit_label":       "Charge Limit",
    "vehicle_enable_charge_limit":      "Enable Charge Limit",
    "vehicle_charge_limit_range":       "Minimum 50%%, maximum 100%%",
    "vehicle_tyre_check_pressure":      "Check pressure",
    "vehicle_toggle_on":                "ON",
    "vehicle_toggle_off":               "OFF",
    "vehicle_err_climate_control":      "Climate control failed.",
    "vehicle_err_max_cooling":          "Max cooling failed.",
    "vehicle_err_drl_control":          "DRL control failed.",
    "vehicle_err_slw_control":          "ADAS control failed.",
    "vehicle_err_charge_limit_toggle":  "Charge limit toggle failed.",
}

BEGIN_MARKER = "<!-- BEGIN GENERATED vehicle-view i18n — DO NOT EDIT MANUALLY -->"
END_MARKER   = "<!-- END GENERATED vehicle-view i18n -->"


def get_nested(d: dict, dotpath: str):
    """Retrieve a nested value by dot-path (e.g. 'vehicle_control.charging_tab')."""
    parts = dotpath.split(".")
    v = d
    for p in parts:
        if not isinstance(v, dict) or p not in v:
            return None
        v = v[p]
    return v if isinstance(v, str) else None


def convert_placeholders(s: str) -> str:
    """
    Convert {name}-style placeholders to Android positional %n$s.

    Discovers placeholder names in order of first appearance and assigns
    positions 1, 2, ... The same position type (%n$s) is used for all
    placeholders — Android format strings at runtime receive Object args.
    """
    names = []
    for m in re.finditer(r'\{(\w+)\}', s):
        n = m.group(1)
        if n not in names:
            names.append(n)
    pos_map = {n: i + 1 for i, n in enumerate(names)}
    result = re.sub(r'\{(\w+)\}', lambda m: f'%{pos_map[m.group(1)]}$s', s)
    return result


def xml_escape(s: str) -> str:
    """Escape for Android strings.xml: apostrophes, ampersands, angle brackets."""
    s = s.replace("&", "&amp;")
    s = s.replace("<", "&lt;")
    s = s.replace(">", "&gt;")
    s = s.replace("'", "\\'")
    return s


def make_string_element(name: str, value: str) -> str:
    return f'    <string name="{name}">{xml_escape(value)}</string>'


def load_bundle(locale: str) -> dict:
    path = I18N_DIR / f"{locale}.json"
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def build_block(locale: str, bundle: dict) -> list[str]:
    """
    Build list of <string> lines for a given locale.

    For the default locale ("en"): includes ALL keys (translatable + fallback).
    For other locales: includes only translatable keys that exist in the bundle.
    """
    lines = []
    is_default = (locale == "en")

    for name, dotpath in KEY_MAP:
        if dotpath is not None:
            raw = get_nested(bundle, dotpath)
            if raw is not None:
                value = convert_placeholders(raw)
                lines.append(make_string_element(name, value))
            elif is_default:
                # i18n key missing in en.json — this shouldn't happen; use fallback
                fb = EN_FALLBACK.get(name)
                if fb:
                    lines.append(make_string_element(name, fb))
        else:
            # English-only key
            if is_default:
                fb = EN_FALLBACK.get(name)
                if fb is not None:
                    lines.append(make_string_element(name, fb))

    return lines


def replace_block(xml_path: Path, new_lines: list[str]) -> bool:
    """
    Replace BEGIN/END block in the XML file with new_lines.
    If the block doesn't exist, inserts it before </resources>.
    Returns True if the file changed.
    """
    with open(xml_path, encoding="utf-8") as f:
        content = f.read()

    if not new_lines:
        # No content to write — remove block if present
        if BEGIN_MARKER in content:
            pattern = re.compile(
                re.escape(BEGIN_MARKER) + r'.*?' + re.escape(END_MARKER) + r'\n?',
                re.DOTALL
            )
            new_content = pattern.sub("", content)
            with open(xml_path, "w", encoding="utf-8") as f:
                f.write(new_content)
            return True
        return False

    block = (
        BEGIN_MARKER + "\n"
        + "\n".join(new_lines) + "\n"
        + END_MARKER + "\n"
    )

    if BEGIN_MARKER in content:
        pattern = re.compile(
            re.escape(BEGIN_MARKER) + r'.*?' + re.escape(END_MARKER),
            re.DOTALL
        )
        new_content = pattern.sub(block.rstrip("\n"), content)
    else:
        # Insert before </resources>
        new_content = content.replace("</resources>", "\n" + block + "</resources>")

    if new_content == content:
        return False

    with open(xml_path, "w", encoding="utf-8") as f:
        f.write(new_content)
    return True


def main():
    errors = []

    for locale, qualifier in LOCALE_QUALIFIER.items():
        bundle = load_bundle(locale)
        lines = build_block(locale, bundle)

        xml_path = RES_DIR / qualifier / "strings.xml"
        if not xml_path.exists():
            print(f"  SKIP {locale}: {xml_path} not found")
            continue

        changed = replace_block(xml_path, lines)
        status = "updated" if changed else "unchanged"
        print(f"  {locale:6s} → {qualifier:20s}  {len(lines):3d} keys  [{status}]")

    if errors:
        for e in errors:
            print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

    print("\nDone.")


if __name__ == "__main__":
    main()
