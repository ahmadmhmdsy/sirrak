package app.sirrak

import app.sirrak.core.design.ThemePacks
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.7 guards the store graphics the same way NOTICE and the SBOM are guarded: the brand data file is
 * the single source, and the generated PNGs must still match the shipped design tokens, the shipped
 * adaptive-icon geometry, the shipped Arabic/English copy and the manifest hashes.
 *
 * If a token, the launcher mark or a brand string changes, this test fails until `scripts/store-graphics.ps1`
 * is run again (and manifest.json is committed with the PNGs).
 */
class StoreGraphicsBrandTest {

    private val graphicsDir = File("../store/graphics")
    private val brandFile = File(graphicsDir, "brand.json")
    private val brand = Json.parseToJsonElement(brandFile.readText()).jsonObject

    private fun palette(): Map<String, String> =
        brand.getValue("palette").jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun copyLines(): Map<String, String> =
        brand.getValue("copy").jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun markPaths(): List<String> =
        brand.getValue("mark").jsonObject.getValue("paths").jsonArray
            .map { it.jsonObject.getValue("d").jsonPrimitive.content }

    private fun hex(argb: Long): String =
        "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    private fun pngSize(file: File): Pair<Int, Int> {
        assertTrue("missing store graphic ${file.absolutePath}", file.isFile)
        val bytes = file.readBytes()
        assertEquals("PNG signature", 0x89.toByte(), bytes[0])
        fun int(at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun paletteMirrorsTheShippedGirihInkThemePack() {
        val light = ThemePacks.default.light
        val palette = palette()
        assertEquals(hex(light.background), palette["ground"])
        assertEquals(hex(light.surface), palette["surface"])
        assertEquals(hex(light.onBackground), palette["ink"])
        assertEquals(hex(light.onSurfaceVariant), palette["muted"])
        assertEquals(hex(light.primary), palette["lapis"])
        assertEquals(hex(light.brass), palette["brass"])
        assertEquals(hex(light.hairline), palette["hairline"])
    }

    @Test
    fun markMatchesTheShippedAdaptiveIconAndFontsExist() {
        val vector = File("src/main/res/drawable/ic_launcher_foreground.xml").readText()
        val xmlPaths = Regex("android:pathData=\"([^\"]+)\"")
            .findAll(vector).map { it.groupValues[1] }.toList()
        assertEquals("brand mark must be the launcher mark", xmlPaths, markPaths())
        val fonts = brand.getValue("fonts").jsonObject
        assertTrue("at least the display and sans fonts are referenced", fonts.size >= 2)
        fonts.forEach { (role, path) ->
            val file = File(".." + File.separator + path.jsonPrimitive.content)
            assertTrue("missing font ${role}: ${file.absolutePath}", file.isFile)
            val head = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            val magic = head.joinToString("") { "%02x".format(it) }
            assertTrue("font ${role} does not look like a TrueType font ($magic)", magic == "00010000" || magic == "4f54544f")
        }
    }

    @Test
    fun copyComesFromShippedStringsAndStoreListing() {
        val copy = copyLines()
        val appStrings = File("src/main/res/values/strings.xml").readText()
        assertTrue("app_name must stay the brand wordmark", appStrings.contains("<string name=\"app_name\">${copy.getValue("arabicWordmark")}</string>"))
        // Display styling may letterspace/uppercase the wordmark; the letters themselves must match the listing name.
        assertEquals(
            File("../store/listing/en/name.txt").readText().trim().uppercase(),
            copy["latinWordmark"]!!.uppercase(),
        )
        assertEquals(copy["arabicWordmark"], File("../store/listing/ar/name.txt").readText().trim())
        val chatStrings = File("../feature/chat/src/main/res/values/strings.xml").readText()
        val promise = Regex("<string name=\"empty_promise\">([^<]+)</string>")
            .find(chatStrings)!!.groupValues[1].replace("\\n", "\n")
        assertEquals(copy["taglineLine1"], promise.split("\n")[0])
        assertEquals(copy["taglineLine2"], promise.split("\n")[1])
        val privacy = Regex("<string name=\"empty_privacy\">([^<]+)</string>")
            .find(chatStrings)!!.groupValues[1]
        assertEquals(privacy, copy["privacyLine"])
    }

    @Test
    fun generatedBinariesMatchTheManifest() {
        val manifest = Json.parseToJsonElement(File(graphicsDir, "manifest.json").readText()).jsonObject
        val outputs = manifest.getValue("outputs").jsonArray.map { it.jsonObject }
        assertEquals(2, outputs.size)
        outputs.forEach { output ->
            val name = output.getValue("file").jsonPrimitive.content
            val file = File(graphicsDir, name)
            val (width, height) = pngSize(file)
            assertEquals(name, output.getValue("width").jsonPrimitive.content.toInt(), width)
            assertEquals(name, output.getValue("height").jsonPrimitive.content.toInt(), height)
            assertEquals(name, output.getValue("bytes").jsonPrimitive.content.toLong(), file.length())
            assertEquals(name, output.getValue("sha256").jsonPrimitive.content, sha256(file))
        }
        val brandSha = manifest.getValue("sources").jsonObject.getValue("brandSha256").jsonPrimitive.content
        assertEquals("manifest must be regenerated with brand.json", sha256(brandFile), brandSha)
        val icon = File(graphicsDir, "icon-512.png")
        assertEquals(512 to 512, pngSize(icon))
        val feature = File(graphicsDir, "feature-graphic-1024x500.png")
        assertEquals(1024 to 500, pngSize(feature))
        assertNotNull(File(graphicsDir, "README.md").takeIf { it.isFile })
    }
}
