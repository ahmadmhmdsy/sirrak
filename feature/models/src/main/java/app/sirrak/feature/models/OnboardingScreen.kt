package app.sirrak.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.ReadingColumn
import app.sirrak.core.design.ScreenTitle
import app.sirrak.core.design.SettingsChoiceRow
import app.sirrak.core.design.SettingsKeyValueRow
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.ConsentRules
import app.sirrak.core.models.DeviceProfileProvider
import app.sirrak.core.models.DownloadPhase
import app.sirrak.core.models.LicenceConsentStore
import app.sirrak.core.models.ModelCatalogueProvider
import app.sirrak.core.models.ModelDownloader
import app.sirrak.core.models.ModelRecommender
import app.sirrak.core.models.OnboardingFlow
import app.sirrak.core.models.OnboardingStep
import app.sirrak.core.models.OnboardingStore
import app.sirrak.core.runtime.AppContext
import app.sirrak.core.runtime.AppLanguage
import app.sirrak.core.runtime.GgufFile
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.SettingsService
import app.sirrak.core.runtime.findActivity

/**
 * ONB-1..ONB-5: the first run is three screens (privacy, language, model), each skippable. The model
 * screen recommends one catalogue entry from the measured device report, shows the licence consent
 * before a gated download, and offers the "try without a model" path that goes straight to chat with
 * the demo engine. The app observes [OnboardingStore] and swaps to the shell when this completes.
 */
@Composable
fun OnboardingScreen(container: AppContext, modifier: Modifier = Modifier) {
    val settings = remember(container) { container.service(SettingsService::class.java) }
    val catalogueProvider = remember(container) { container.service(ModelCatalogueProvider::class.java) }
    val profileProvider = remember(container) { container.service(DeviceProfileProvider::class.java) }
    val downloader = remember(container) { container.service(ModelDownloader::class.java) }
    val consentStore = remember(container) { container.service(LicenceConsentStore::class.java) }
    val onboardingStore = remember(container) { container.service(OnboardingStore::class.java) }
    val modelStore = remember(container) { container.service(ModelStore::class.java) }
    val activityContext = LocalContext.current

    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val steps = remember { OnboardingFlow.ordered() }
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]

    val language by settings.language.collectAsStateWithLifecycle()
    val progress by downloader.progress.collectAsStateWithLifecycle()
    val installed by modelStore.models.collectAsStateWithLifecycle()
    var refreshKey by remember { mutableIntStateOf(0) }
    val profile = remember(refreshKey) { profileProvider.profile() }
    val catalogue = remember(catalogueProvider) { catalogueProvider.catalogue() }
    val recommendation = remember(catalogue, profile) { ModelRecommender.recommend(catalogue, profile) }

    var pendingEntry by remember { mutableStateOf<CatalogueEntry?>(null) }
    var consentEntry by remember { mutableStateOf<CatalogueEntry?>(null) }

    val finish: (Boolean) -> Unit = { skipped ->
        onboardingStore.complete(skipped = skipped)
        activityContext.findActivity()?.recreate()
    }
    val startDownload: (CatalogueEntry, String?) -> Unit = { entry, token ->
        // P3.4: contextual permission ask so the foreground download notification is visible.
        requestDownloadNotificationPermission(activityContext)
        pendingEntry = entry
        downloader.start(entry = entry, authorization = token)
    }
    val requestDownload: (CatalogueEntry) -> Unit = { entry ->
        if (ConsentRules.requiresConsent(entry) && !consentStore.isAccepted(entry.id)) {
            consentEntry = entry
        } else {
            startDownload(entry, null)
        }
    }

    LaunchedEffect(progress?.phase, progress?.file) {
        val current = progress ?: return@LaunchedEffect
        if (current.phase != DownloadPhase.INSTALLED) return@LaunchedEffect
        val file = current.file ?: return@LaunchedEffect
        val source = pendingEntry
        val result = modelStore.importFile(
            displayName = source?.fileName ?: file.name,
            source = file,
            expectedSha256 = source?.sha256,
        )
        result.onSuccess { model ->
            modelStore.select(model)
            refreshKey++
        }
        downloader.clear()
        pendingEntry = null
    }

    ReadingColumn(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.onboarding_step_counter, stepIndex + 1, steps.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { (stepIndex + 1).toFloat() / steps.size.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            )
            when (step) {
                OnboardingStep.PRIVACY -> PrivacyStep()
                OnboardingStep.LANGUAGE -> LanguageStep(
                    selectedIndex = if (language == AppLanguage.ARABIC) 0 else 1,
                    onSelect = { index ->
                        settings.setLanguage(if (index == 0) AppLanguage.ARABIC else AppLanguage.ENGLISH)
                    },
                )
                OnboardingStep.MODEL -> ModelStep(
                    profile = profile,
                    recommendation = recommendation,
                    installed = installed.isNotEmpty(),
                    progress = progress,
                    onDownload = { recommendation.entry?.let(requestDownload) },
                )
            }
            Spacer(Modifier.height(28.dp))
            when (step) {
                OnboardingStep.PRIVACY, OnboardingStep.LANGUAGE -> {
                    Button(
                        onClick = { stepIndex = (stepIndex + 1).coerceAtMost(steps.lastIndex) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.onboarding_next))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (stepIndex > 0) {
                            TextButton(onClick = { stepIndex-- }) {
                                Text(stringResource(R.string.onboarding_back))
                            }
                        } else {
                            Spacer(Modifier.height(0.dp))
                        }
                        TextButton(onClick = { finish(true) }) {
                            Text(stringResource(R.string.onboarding_model_skip))
                        }
                    }
                }
                OnboardingStep.MODEL -> {
                    if (installed.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.onboarding_model_installed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Button(onClick = { finish(false) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.onboarding_finish))
                        }
                    } else {
                        Button(
                            onClick = { recommendation.entry?.let(requestDownload) },
                            enabled = recommendation.entry != null && progress?.phase != DownloadPhase.DOWNLOADING,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.onboarding_model_download))
                        }
                    }
                    TextButton(onClick = { finish(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onboarding_model_try_without))
                    }
                    TextButton(onClick = { finish(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onboarding_model_skip))
                    }
                }
            }
        }
    }

    consentEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { consentEntry = null },
            title = { Text(stringResource(R.string.models_licence_accept_title)) },
            text = { Text(stringResource(R.string.models_licence_accept_body, entry.displayName, entry.licence)) },
            confirmButton = {
                TextButton(onClick = {
                    consentStore.accept(entry.id, entry.licence)
                    consentEntry = null
                    startDownload(entry, null)
                }) {
                    Text(stringResource(R.string.models_licence_accept_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { consentEntry = null }) {
                    Text(stringResource(R.string.models_cancel))
                }
            },
        )
    }
}

@Composable
private fun PrivacyStep() {
    ScreenTitle(stringResource(R.string.onboarding_privacy_title))
    SettingsNote(stringResource(R.string.onboarding_privacy_body))
    SettingsSection(title = stringResource(R.string.models_section_title)) {
        SettingsNote(stringResource(R.string.onboarding_privacy_local))
        SettingsNote(stringResource(R.string.onboarding_privacy_network))
        SettingsNote(stringResource(R.string.onboarding_privacy_no_telemetry))
    }
}

@Composable
private fun LanguageStep(selectedIndex: Int, onSelect: (Int) -> Unit) {
    ScreenTitle(stringResource(R.string.onboarding_language_title))
    SettingsNote(stringResource(R.string.onboarding_language_body))
    SettingsChoiceRow(
        label = stringResource(R.string.onboarding_language_title),
        options = listOf(
            stringResource(R.string.onboarding_language_arabic),
            stringResource(R.string.onboarding_language_english),
        ),
        selectedIndex = selectedIndex,
        onSelect = onSelect,
    )
}

@Composable
private fun ModelStep(
    profile: app.sirrak.core.models.DeviceProfile,
    recommendation: app.sirrak.core.models.Recommendation,
    installed: Boolean,
    progress: app.sirrak.core.models.DownloadProgress?,
    onDownload: () -> Unit,
) {
    ScreenTitle(stringResource(R.string.onboarding_model_title))
    SettingsNote(stringResource(R.string.onboarding_model_body))
    SettingsSection(title = stringResource(R.string.models_device_title)) {
        SettingsKeyValueRow(stringResource(R.string.models_device_ram), "${profile.totalRamMb} MB")
        SettingsKeyValueRow(stringResource(R.string.models_device_storage), GgufFile.formatSize(profile.availableStorageBytes))
        SettingsKeyValueRow(stringResource(R.string.models_device_android), "API ${profile.sdkInt}")
    }
    recommendation.entry?.let { entry ->
        SettingsSection(title = stringResource(R.string.models_recommended_title)) {
            SettingsKeyValueRow(entry.displayName, entry.sizeLabel)
            SettingsNote(stringResource(R.string.models_recommended_why_recommended_fits))
            if (recommendation.storageTight) {
                SettingsNote(stringResource(R.string.models_storage_warning))
            }
        }
    }
    SettingsNote(stringResource(R.string.onboarding_model_demo_note))
    progress?.let { current ->
        if (current.phase == DownloadPhase.DOWNLOADING || current.phase == DownloadPhase.QUEUED) {
            LinearProgressIndicator(progress = { current.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.models_download_percent, current.percent) +
                    " · " + GgufFile.formatSize(current.bytesDownloaded) + " / " + GgufFile.formatSize(current.totalBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else if (current.phase == DownloadPhase.VERIFYING) {
            Text(
                text = stringResource(R.string.models_download_verifying),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (current.phase == DownloadPhase.FAILED) {
            Text(
                text = stringResource(R.string.models_reason_network),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.models_action_retry))
            }
        }
    }
    if (installed) {
        Text(
            text = stringResource(R.string.onboarding_model_installed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
