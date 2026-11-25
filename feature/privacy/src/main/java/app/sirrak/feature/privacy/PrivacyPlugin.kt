package app.sirrak.feature.privacy

import androidx.compose.ui.res.stringResource
import app.sirrak.core.privacy.AiReportQueue
import app.sirrak.core.privacy.NetworkAudit
import app.sirrak.core.privacy.OfflineModeStore
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.PageContribution
import app.sirrak.core.runtime.PageIds
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.runtime.PrivacyConfig
import app.sirrak.core.runtime.SettingsDoor
import app.sirrak.core.design.SettingsPageScaffold

/**
 * Privacy feature plugin. It contributes two surfaces and owns no policy:
 *
 * - the "privacy and network" door in the Settings root (live offline switch summary) plus the page
 *   behind it (offline-only switch, declared-endpoint registry + on-device network log, AI-output
 *   reports, published policy links);
 * - a per-answer report action on [app.sirrak.core.runtime.Slots.ChatAnswerActions].
 *
 * The host owns the stores ([OfflineModeStore], [NetworkAudit], [AiReportQueue]) and the config, so
 * the same UI can be reused by a future standalone privacy screen, and a different host (tests) can
 * drive it with in-memory stores.
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
        AppNavigator::class.java,
    )

    override fun install(scope: PluginScope) {
        val offlineModeStore = scope.service(OfflineModeStore::class.java)
        val audit = scope.service(NetworkAudit::class.java)
        val reportQueue = scope.service(AiReportQueue::class.java)
        val config = scope.service(PrivacyConfig::class.java)
        val navigator = scope.service(AppNavigator::class.java)

        scope.door(
            SettingsDoor(
                id = "privacy",
                labelRes = R.string.privacy_section_title,
                order = 10,
                summary = {
                    // Read at composition time; navigating back recomposes the root, so the value is
                    // never stale in the place the user sees it.
                    stringResource(
                        if (offlineModeStore.isOfflineOnly()) {
                            R.string.privacy_door_summary_offline
                        } else {
                            R.string.privacy_door_summary_online
                        },
                    )
                },
                onClick = { navigator.push(PageIds.SETTINGS_PRIVACY) },
            ),
        )
        scope.page(
            PageContribution(PageIds.SETTINGS_PRIVACY, labelRes = R.string.privacy_section_title) {
                SettingsPageScaffold(
                    title = stringResource(R.string.privacy_section_title),
                    onBack = { navigator.back() },
                ) {
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
            },
        )
        scope.slotList(app.sirrak.core.runtime.Slots.ChatAnswerActions, order = 10) {
            ReportAnswerAction(
                reportQueue = reportQueue,
                config = config,
                appLabel = appLabel,
                modelLabel = modelLabel,
            )
        }
    }
}
