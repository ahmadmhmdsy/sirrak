package app.sirrak

import app.sirrak.core.runtime.AppConfig
import app.sirrak.core.runtime.AppConfigLoader
import app.sirrak.core.runtime.EngineConfig
import app.sirrak.core.runtime.FeaturesConfig
import app.sirrak.core.runtime.NetworkEndpoint
import app.sirrak.core.runtime.PrivacyConfig
import app.sirrak.core.runtime.ThemeConfig
import app.sirrak.core.design.ThemePacks
import java.io.File
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test
    fun shippedConfigAssetParsesAndValidates() {
        val file = File("src/main/assets/config/app.json")
        assertTrue("shipped config asset missing at ${file.absolutePath}", file.exists())
        val config = AppConfigLoader.parse(file.readText())
        assertEquals(1, config.schemaVersion)
        assertEquals("llamacpp", config.engine.provider)
        assertEquals(2048, config.engine.contextTokens)
        assertEquals("girih_ink", config.theme.defaultPack)
        // P3: the shipped build declares every network endpoint it can reach, by data.
        assertFalse(config.privacy.offlineByDefault)
        assertEquals(3, config.privacy.endpoints.size)
        val byId = config.privacy.endpoints.associateBy { it.id }
        assertEquals(setOf("model_download", "hf_search", "hf_probe"), byId.keys)
        byId.values.forEach { endpoint ->
            assertEquals("huggingface.co", endpoint.host)
            assertEquals(endpoint.id, endpoint.purposeKey)
        }
        assertTrue(AppConfigLoader.validate(config).isEmpty())
    }

    @Test
    fun unknownConfigKeyFailsLoudly() {
        assertThrows(SerializationException::class.java) {
            AppConfigLoader.parse("""{"schemaVersion":1,"featurez":{}}""")
        }
    }

    @Test
    fun malformedJsonFailsLoudly() {
        assertThrows(SerializationException::class.java) {
            AppConfigLoader.parse("""{"schemaVersion":"one"}""")
        }
    }

    @Test
    fun defaultsAreValid() {
        assertTrue(AppConfigLoader.validate(AppConfig()).isEmpty())
    }

    @Test
    fun engineSettingsWithinRangeAreValid() {
        val config = AppConfig(
            engine = EngineConfig(
                contextTokens = 4096,
                maxTokens = 1024,
                temperature = 0.2f,
                topP = 0.9f,
                topK = 20,
            ),
        )
        assertTrue(AppConfigLoader.validate(config).isEmpty())
    }

    @Test
    fun engineSettingsOutsideRangeAreReported() {
        val problems = AppConfigLoader.validate(
            AppConfig(
                engine = EngineConfig(
                    contextTokens = 128,
                    maxTokens = 0,
                    temperature = 5f,
                    topP = 2f,
                    topK = 0,
                ),
            ),
        )
        assertEquals(5, problems.size)
        assertTrue(problems.any { it.contains("contextTokens") })
        assertTrue(problems.any { it.contains("topK") })
    }

    @Test
    fun themePackDefaultsToGirihInk() {
        assertEquals("girih_ink", AppConfig().theme.defaultPack)
    }

    @Test
    fun themePackFromConfigIsResolvedAgainstTheRegistry() {
        val config = AppConfigLoader.parse("""{"schemaVersion":1,"theme":{"defaultPack":"lapis_night"}}""")
        assertEquals("lapis_night", config.theme.defaultPack)
        assertEquals("lapis_night", ThemePacks.byId(config.theme.defaultPack).id)
        assertTrue(AppConfigLoader.validate(config).isEmpty())
    }

    @Test
    fun unknownThemePackFallsBackAtResolutionTimeNotAtParseTime() {
        // core:runtime must not depend on :core:design, so validity is a resolver concern; the UI must
        // never crash or show an unstyled app because of a typo in a config asset.
        val config = AppConfigLoader.parse("""{"schemaVersion":1,"theme":{"defaultPack":"ghost"}}""")
        assertEquals("ghost", config.theme.defaultPack)
        assertTrue(AppConfigLoader.validate(config).isEmpty())
        assertEquals("girih_ink", ThemePacks.byId(config.theme.defaultPack).id)
    }

    @Test
    fun blankThemePackIsReported() {
        val problems = AppConfigLoader.validate(AppConfig(theme = ThemeConfig(defaultPack = "  ")))
        assertTrue(problems.single().contains("theme.defaultPack"))
    }

    @Test
    fun privacyDefaultsAreHonestAndValid() {
        val privacy = AppConfig().privacy
        assertFalse(privacy.offlineByDefault)
        assertTrue(privacy.endpoints.isEmpty())
        assertEquals("", privacy.policyUrl)
        assertEquals("", privacy.reportEmail)
    }

    @Test
    fun privacyConfigParsesEndpointsAndLinksFromJson() {
        val config = AppConfigLoader.parse(
            """
            {"schemaVersion":1,"privacy":{
              "offlineByDefault":true,
              "policyUrl":"https://example.org/privacy",
              "securityUrl":"https://example.org/security",
              "reportEmail":"reports@example.org",
              "endpoints":[
                {"id":"model_download","host":"huggingface.co","purpose":"Download a model",
                 "whenUsed":"Only when you start a download","userInitiated":true,
                 "purposeKey":"model_download"}
              ]}}
            """.trimIndent(),
        )
        assertTrue(config.privacy.offlineByDefault)
        assertEquals("https://example.org/privacy", config.privacy.policyUrl)
        assertEquals("reports@example.org", config.privacy.reportEmail)
        val endpoint = config.privacy.endpoints.single()
        assertEquals("model_download", endpoint.id)
        assertEquals("huggingface.co", endpoint.host)
        assertTrue(endpoint.userInitiated)
        assertTrue(AppConfigLoader.validate(config).isEmpty())
    }

    @Test
    fun privacyDuplicateEndpointIdsAreReported() {
        val endpoint = NetworkEndpoint(id = "model_download", host = "huggingface.co")
        val problems = AppConfigLoader.validate(
            AppConfig(privacy = PrivacyConfig(endpoints = listOf(endpoint, endpoint))),
        )
        assertTrue(problems.any { it.contains("duplicate ids") })
    }

    @Test
    fun privacyEndpointHostMustBeAHostName() {
        val problems = AppConfigLoader.validate(
            AppConfig(
                privacy = PrivacyConfig(
                    endpoints = listOf(
                        NetworkEndpoint(id = "blank", host = "  "),
                        NetworkEndpoint(id = "url", host = "https://huggingface.co/path"),
                        NetworkEndpoint(id = "slash", host = "huggingface.co/models"),
                    ),
                ),
            ),
        )
        assertEquals(3, problems.count { it.contains(".host") })
    }

    @Test
    fun privacyLinksMustBeHttps() {
        val problems = AppConfigLoader.validate(
            AppConfig(
                privacy = PrivacyConfig(
                    policyUrl = "http://example.org/privacy",
                    securityUrl = "example.org/security",
                ),
            ),
        )
        assertEquals(2, problems.size)
        assertTrue(problems.all { it.contains("https") })
    }

    @Test
    fun privacyReportEmailMustBeAnAddressOrBlank() {
        assertTrue(
            AppConfigLoader.validate(
                AppConfig(privacy = PrivacyConfig(reportEmail = "reports@example.org")),
            ).isEmpty(),
        )
        val problems = AppConfigLoader.validate(
            AppConfig(privacy = PrivacyConfig(reportEmail = "not an address")),
        )
        assertTrue(problems.single().contains("reportEmail"))
    }

    @Test
    fun enabledEntriesMustExistInOrder() {
        val problems = AppConfigLoader.validate(
            AppConfig(features = FeaturesConfig(order = listOf("chat"), enabled = listOf("ghost"))),
        )
        assertTrue(problems.single().contains("not in order"))
    }
}