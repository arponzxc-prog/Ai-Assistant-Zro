package com.arponzxc.novaai.devstudio.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.arponzxc.novaai.devstudio.model.DevStudioTab
import com.arponzxc.novaai.devstudio.model.ProjectType

class DevStudioActivity : ComponentActivity() {

    private val viewModel: DevStudioViewModel by viewModels()

    private val zipImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val inferredName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.ifBlank { "ImportedApp" }
                ?: "ImportedApp"
            viewModel.importZipFromUri(uri, inferredName)
        }
    }

    private val zipExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportActiveProjectToUri(uri)
        }
    }

    private val sharedWorkspaceFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.onSharedWorkspaceFolderAuthorized(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.arponzxc.novaai.util.ThemeManager.applyTheme(this)

        handleLaunchIntent(intent)

        setContent {
            DevStudioTheme {
                DevStudioScreen(
                    viewModel = viewModel,
                    onCloseActivity = { finish() },
                    onLaunchZipImport = {
                        zipImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                    },
                    onLaunchZipExport = {
                        val base = viewModel.uiState.value.activeProject?.id ?: "nova_project"
                        zipExportLauncher.launch("$base.zip")
                    },
                    onAuthorizeSharedFolder = {
                        sharedWorkspaceFolderLauncher.launch(null)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        val tabName = intent.getStringExtra(EXTRA_INITIAL_TAB)
        if (!tabName.isNullOrBlank()) {
            runCatching { DevStudioTab.valueOf(tabName) }.getOrNull()?.let { viewModel.selectTab(it) }
        }
        val autoPrompt = intent.getStringExtra(EXTRA_AUTO_PROMPT)
        val autoTypeName = intent.getStringExtra(EXTRA_AUTO_PROJECT_TYPE)
        if (!autoPrompt.isNullOrBlank()) {
            val type = runCatching { ProjectType.valueOf(autoTypeName ?: "") }
                .getOrDefault(ProjectType.ANDROID_KOTLIN)
            viewModel.selectTab(DevStudioTab.BUILDER)
            viewModel.runAutonomousBuild(autoPrompt, type)
            return
        }

        if (intent.getBooleanExtra(EXTRA_AUTO_TRIGGER_BUILD, false)) {
            viewModel.selectTab(DevStudioTab.BUILD_TERMUX)
            viewModel.requestBuildAndroidApkViaTermux()
        } else if (intent.getBooleanExtra(EXTRA_AUTO_START_PREVIEW, false)) {
            viewModel.selectTab(DevStudioTab.PREVIEW)
            viewModel.startWebPreview()
        } else if (intent.getBooleanExtra(EXTRA_AUTO_PROBE_TERMUX, false)) {
            viewModel.selectTab(DevStudioTab.BUILD_TERMUX)
            viewModel.probeTermuxToolchainLive()
        } else if (intent.getBooleanExtra(EXTRA_CLOSE_ACTIVE_PROJECT, false)) {
            viewModel.requestCloseActiveProject(forceClose = false)
        }
    }

    companion object {
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
        const val EXTRA_AUTO_PROMPT = "extra_auto_prompt"
        const val EXTRA_AUTO_PROJECT_TYPE = "extra_auto_project_type"
        const val EXTRA_AUTO_TRIGGER_BUILD = "extra_auto_trigger_build"
        const val EXTRA_AUTO_START_PREVIEW = "extra_auto_start_preview"
        const val EXTRA_AUTO_PROBE_TERMUX = "extra_auto_probe_termux"
        const val EXTRA_CLOSE_ACTIVE_PROJECT = "extra_close_active_project"

        fun createLaunchIntent(
            context: Context,
            initialTab: DevStudioTab? = null,
            autoPrompt: String? = null,
            autoProjectType: ProjectType? = null,
            autoTriggerBuild: Boolean = false,
            autoStartPreview: Boolean = false,
            autoProbeTermux: Boolean = false,
            closeActiveProject: Boolean = false
        ): Intent = Intent(context, DevStudioActivity::class.java).apply {
            initialTab?.let { putExtra(EXTRA_INITIAL_TAB, it.name) }
            autoPrompt?.let { putExtra(EXTRA_AUTO_PROMPT, it) }
            autoProjectType?.let { putExtra(EXTRA_AUTO_PROJECT_TYPE, it.name) }
            if (autoTriggerBuild) putExtra(EXTRA_AUTO_TRIGGER_BUILD, true)
            if (autoStartPreview) putExtra(EXTRA_AUTO_START_PREVIEW, true)
            if (autoProbeTermux) putExtra(EXTRA_AUTO_PROBE_TERMUX, true)
            if (closeActiveProject) putExtra(EXTRA_CLOSE_ACTIVE_PROJECT, true)
        }
    }
}
