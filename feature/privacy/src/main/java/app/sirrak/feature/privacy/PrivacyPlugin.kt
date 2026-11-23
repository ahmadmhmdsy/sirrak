package app.sirrak.feature.privacy

import app.sirrak.core.privacy.AiReportQueue
import app.sirrak.core.privacy.NetworkAudit
import app.sirrak.core.privacy.OfflineModeStore
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.runtime.PrivacyConfig
import app.sirrak.core.runtime.Slots

/**
 * P3 privacy feature plugin. It contributes two surfaces and owns no policy:
 *
 * - a Settings group (offline-only switch, declared-endpoint registry + on-device network log,
 *   AI-output reports, published policy links);
 * - a per-answer report action on [Slots.ChatAnswerActions].
 *
 * The host owns the stores ([OfflineModeStore], [NetworkAudit], [AiReportQueue]) and the config, so
 * the same UI can be reused by a future standalone privacy screen, and a different host (tests)
 * can drive it with in-memory stores.
 */
class PrivacyPlugin(
    /** Shown in the shared report text; the feature module cannot see the app's R.string. */
    private val appLabel: String = "Sirrak",
    /** Reads the current model label at report time (host-owned registry). */
    private val modelLabel: () -> String = { "" },
    /** Host hook run when the user turns offline-only on (cancel a running download). */
    private val onOfflineEnabled: () -> Unit = {},
) : Plugin {

    override val id: String = "privacy"

    override val requires: List<Class<*>> = listOf(
        OfflineModeStore::class.java,
        NetworkAudit::class.java,
        AiReportQueue::class.java,
        PrivacyConfig::class.java,
    )

    override fun install(scope: PluginScope) {
        val offlineModeStore = scope.service(OfflineModeStore::class.java)
        val audit = scope.service(NetworkAudit::class.java)
        val reportQueue = scope.service(AiReportQueue::class.java)
        val config = scope.service(PrivacyConfig::class.java)

        // 25: after the models card (12), so the settings page reads: models, privacy, then about.
        scope.slotList(Slots.SettingsSections, order = 25) {
            PrivacySection(
                offlineModeStore = offlineModeStore,
                audit = audit,
                reportQueue = reportQueue,
                config = config,
                modelLabel = modelLabel,
                appLabel = appLabel,
                onOfflineEnabled = onOfflineEnabled,
            )
        }
        scope.slotList(Slots.ChatAnswerActions, order = 10) {
            ReportAnswerAction(
                reportQueue = reportQueue,
                config = config,
                appLabel = appLabel,
                modelLabel = modelLabel,
            )
        }
    }
}
