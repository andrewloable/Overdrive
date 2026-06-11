package net.bladewatch.app.ui.fragment.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Verifies the i18n parity of vehicle-view generated string resources.
 *
 * Rules enforced:
 *   1. Every key in the generated block of values/strings.xml exists in all 16 locale files.
 *      EXCEPT: keys flagged as English-only (no web i18n equivalent) may be absent from locales —
 *      Android falls back to the default locale automatically.
 *   2. For keys PRESENT in a locale file, the positional placeholder count (%n$s / %n$d / %n$.Xf)
 *      must match the count in the default locale. A mismatch is a runtime String.format crash.
 */
class VehicleI18nParityTest {

    companion object {
        private val RES_DIR = File("src/main/res")
        private val BEGIN = "<!-- BEGIN GENERATED vehicle-view i18n"
        private val END   = "<!-- END GENERATED vehicle-view i18n"

        // Locales that MUST have translations for the 25 translatable keys
        private val TRANSLATABLE_LOCALES = listOf(
            "values-de", "values-es", "values-fr", "values-hi", "values-it",
            "values-ja", "values-ko", "values-nb", "values-nl", "values-pt-rBR",
            "values-ru", "values-th", "values-tr", "values-vi",
            "values-zh-rCN", "values-zh-rTW"
        )

        // Keys from the generator's KEY_MAP with a non-null web i18n path (the 25 translatable ones).
        // These MUST be present in every locale file.
        private val TRANSLATABLE_KEYS = setOf(
            "vehicle_tab_trunk", "vehicle_tab_climate", "vehicle_tab_seats",
            "vehicle_tab_lights", "vehicle_tab_adas", "vehicle_control_charging_tab",
            "vehicle_locked", "vehicle_unlocked",
            "vehicle_close_trunk", "vehicle_ac_on", "vehicle_ac_off",
            "vehicle_seat_pos_1", "vehicle_seat_pos_2",
            "vehicle_all_windows",
            "vehicle_sunroof", "vehicle_sunshade",
            "vehicle_btn_drl_title", "vehicle_btn_slw_title",
            "vehicle_control_section_charge_cap",
            "vehicle_tyre_no_signal", "vehicle_tyre_slow_leak", "vehicle_tyre_fast_leak",
            "vehicle_tyre_low", "vehicle_tyre_high", "vehicle_tyre_ok"
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun parseStrings(qualifier: String): Map<String, String> {
        val file = RES_DIR.resolve("$qualifier/strings.xml")
        if (!file.exists()) return emptyMap()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = mutableMapOf<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            result[el.getAttribute("name")] = el.textContent
        }
        return result
    }

    private fun generatedKeysFromDefault(): List<String> {
        val file = RES_DIR.resolve("values/strings.xml")
        val lines = file.readLines()
        val keys = mutableListOf<String>()
        var inBlock = false
        for (line in lines) {
            if (line.contains(BEGIN)) { inBlock = true; continue }
            if (line.contains(END)) break
            if (inBlock) {
                val m = Regex("""name="([^"]+)"""").find(line)
                if (m != null) keys.add(m.groupValues[1])
            }
        }
        return keys
    }

    private fun placeholderCount(value: String): Int =
        Regex("""%\d+\$[sdf]|%\d+\$\.\d+f""").findAll(value).count()

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test fun `generated block is present in default strings xml`() {
        val file = RES_DIR.resolve("values/strings.xml")
        assertTrue("values/strings.xml must contain the BEGIN marker", file.readText().contains(BEGIN))
    }

    @Test fun `generated block contains all expected vehicle keys`() {
        val keys = generatedKeysFromDefault().toSet()
        for (k in TRANSLATABLE_KEYS) {
            assertTrue("$k missing from values/strings.xml generated block", k in keys)
        }
    }

    @Test fun `translatable keys are present in all locale files`() {
        val defaultStrings = parseStrings("values")
        val failures = mutableListOf<String>()
        for (locale in TRANSLATABLE_LOCALES) {
            val localeStrings = parseStrings(locale)
            for (key in TRANSLATABLE_KEYS) {
                if (key !in localeStrings) {
                    failures.add("$locale: missing key '$key'")
                }
            }
        }
        assertEquals("Translatable keys missing from locale files:\n${failures.joinToString("\n")}", 0, failures.size)
    }

    @Test fun `placeholder counts match default in all locale files`() {
        val defaultStrings = parseStrings("values")
        val failures = mutableListOf<String>()
        for (locale in TRANSLATABLE_LOCALES) {
            val localeStrings = parseStrings(locale)
            for ((key, localeValue) in localeStrings) {
                if (!key.startsWith("vehicle_")) continue
                val defaultValue = defaultStrings[key] ?: continue
                val defaultCount = placeholderCount(defaultValue)
                val localeCount = placeholderCount(localeValue)
                if (defaultCount != localeCount) {
                    failures.add("$locale.$key: expected $defaultCount placeholders, found $localeCount")
                }
            }
        }
        assertEquals("Placeholder count mismatches (would cause runtime crash):\n${failures.joinToString("\n")}", 0, failures.size)
    }
}
