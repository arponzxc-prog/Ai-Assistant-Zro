package com.arponzxc.novaai.devstudio.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arponzxc.novaai.devstudio.agent.AutonomousDevAgent
import com.arponzxc.novaai.devstudio.build.BuildAndRunEngine
import com.arponzxc.novaai.devstudio.model.AgentStepLog
import com.arponzxc.novaai.devstudio.model.ApkVerificationResult
import com.arponzxc.novaai.devstudio.model.BuildErrorDiagnostic
import com.arponzxc.novaai.devstudio.model.BuildValidationResult
import com.arponzxc.novaai.devstudio.model.ConfirmationActionType
import com.arponzxc.novaai.devstudio.model.DevAgentPhase
import com.arponzxc.novaai.devstudio.model.DevChangeProposal
import com.arponzxc.novaai.devstudio.model.DevFileNode
import com.arponzxc.novaai.devstudio.model.DevProject
import com.arponzxc.novaai.devstudio.model.DevStudioTab
import com.arponzxc.novaai.devstudio.model.PendingStudioConfirmation
import com.arponzxc.novaai.devstudio.model.ProjectBuildStatus
import com.arponzxc.novaai.devstudio.model.ProjectRunStatus
import com.arponzxc.novaai.devstudio.model.ProjectType
import com.arponzxc.novaai.devstudio.model.SandboxCommandRisk
import com.arponzxc.novaai.devstudio.model.TerminalLogEntry
import com.arponzxc.novaai.devstudio.model.TerminalSource
import com.arponzxc.novaai.devstudio.model.TermuxCommandResult
import com.arponzxc.novaai.devstudio.model.TermuxCommandState
import com.arponzxc.novaai.devstudio.model.TermuxEnvironmentStatus
import com.arponzxc.novaai.devstudio.model.TransferProgressState
import com.arponzxc.novaai.devstudio.model.WebConsoleEntry
import com.arponzxc.novaai.devstudio.preview.LocalWebPreviewServer
import com.arponzxc.novaai.devstudio.security.DevSandboxGuard
import com.arponzxc.novaai.devstudio.termux.TermuxBridge
import com.arponzxc.novaai.devstudio.workspace.DevWorkspaceManager
import com.arponzxc.novaai.devstudio.workspace.SharedWorkspaceBridge
import com.arponzxc.novaai.devstudio.workspace.SharedWorkspaceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class DevStudioUiState(
    val activeTab: DevStudioTab = DevStudioTab.DASHBOARD,
    val projects: List<DevProject> = emptyList(),
    val activeProject: DevProject? = null,
    val fileNodes: List<DevFileNode> = emptyList(),
    val fileSearchQuery: String = "",
    val selectedFilePath: String? = null,
    val editorContent: String = "",
    val savedContentSnapshot: String = "",
    val undoStack: List<String> = emptyList(),
    val redoStack: List<String> = emptyList(),
    val pendingProposal: DevChangeProposal? = null,
    val aiExplainOutput: String? = null,
    val validationResult: BuildValidationResult? = null,
    val compilerDiagnostics: List<BuildErrorDiagnostic> = emptyList(),
    val termuxStatus: TermuxEnvironmentStatus? = null,
    val terminalLogs: List<TerminalLogEntry> = emptyList(),
    val previewUrl: String? = null,
    val isPreviewDesktopMode: Boolean = false,
    val isPreviewFullscreen: Boolean = false,
    val previewLiveReloadToken: Long = 0L,
    val previewAutoReloadEnabled: Boolean = true,
    val webConsoleLogs: List<WebConsoleEntry> = emptyList(),
    val agentPhase: DevAgentPhase = DevAgentPhase.IDLE,
    val agentSteps: List<AgentStepLog> = emptyList(),
    val pendingConfirmation: PendingStudioConfirmation? = null,
    val statusMessage: String? = null,
    val isBusy: Boolean = false,
    val sharedWorkspace: SharedWorkspaceConfig? = null,
    val transferProgress: TransferProgressState = TransferProgressState(),
    val activeCommandExecutionId: String? = null,
    val isCommandRunning: Boolean = false,
    val buildRetryCount: Int = 0,
    val lastApkVerification: ApkVerificationResult? = null,
    val activeProjectLimitationNotice: String? = null
) {
    val hasUnsavedChanges: Boolean get() = selectedFilePath != null && editorContent != savedContentSnapshot
}

class DevStudioViewModel(application: Application) : AndroidViewModel(application) {

    val workspaceManager = DevWorkspaceManager(application.filesDir)
    val sharedWorkspaceBridge = SharedWorkspaceBridge(application.applicationContext)
    private val previewServer = LocalWebPreviewServer()
    private val autonomousAgent = AutonomousDevAgent(application.applicationContext)

    private val _uiState = MutableStateFlow(DevStudioUiState())
    val uiState: StateFlow<DevStudioUiState> = _uiState.asStateFlow()

    private var activeAgentJob: Job? = null
    private var activeBuildJob: Job? = null
    private var lastObservedWebToken: Long = 0L

    init {
        refreshSharedWorkspaceConfig()
        refreshProjects()
        refreshTermuxEnvironment()
    }

    fun selectTab(tab: DevStudioTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun refreshSharedWorkspaceConfig() {
        val config = sharedWorkspaceBridge.getConfig()
        _uiState.update { it.copy(sharedWorkspace = config) }
    }

    fun onSharedWorkspaceFolderAuthorized(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            sharedWorkspaceBridge.authorizeAndInitializeWorkspace(uri)
                .onSuccess { cfg ->
                    _uiState.update { it.copy(sharedWorkspace = cfg) }
                    postStatus("Authorized ${cfg.rootFolderName}/ (${SharedWorkspaceBridge.REQUIRED_SUBFOLDERS.size} workspace folders ready)")
                }
                .onFailure { err ->
                    postStatus("Folder authorization failed: ${err.message}")
                }
        }
    }

    fun setCustomTermuxWorkspaceBase(path: String) {
        if (path.isBlank()) return
        sharedWorkspaceBridge.setCustomTermuxBasePath(path)
        refreshSharedWorkspaceConfig()
        postStatus("Updated shared Termux base path: $path")
    }

    fun refreshProjects(selectId: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = workspaceManager.listProjects()
            val target = selectId?.let { id -> list.firstOrNull { it.id == id } }
                ?: _uiState.value.activeProject?.let { cur -> list.firstOrNull { it.id == cur.id } }
                ?: list.firstOrNull()
            _uiState.update { it.copy(projects = list, activeProject = target) }
            target?.let {
                refreshFileTree(it.id)
                refreshApkMetadataIfPresent(it)
            }
        }
    }

    private fun refreshApkMetadataIfPresent(project: DevProject) {
        val apkFile = project.lastApkPath?.let { File(it) }?.takeIf { it.exists() }
            ?: BuildAndRunEngine.findBuiltDebugApk(File(project.rootPath))
        val info = apkFile?.let { BuildAndRunEngine.verifyApkWithMetadata(it, getApplication()) }
        _uiState.update { it.copy(lastApkVerification = info) }
    }

    fun selectProject(project: DevProject, openTab: DevStudioTab? = null) {
        _uiState.update {
            it.copy(
                activeProject = project,
                activeTab = openTab ?: it.activeTab,
                selectedFilePath = null,
                editorContent = "",
                savedContentSnapshot = "",
                undoStack = emptyList(),
                redoStack = emptyList(),
                validationResult = null,
                compilerDiagnostics = emptyList(),
                buildRetryCount = 0,
                activeProjectLimitationNotice = if (!project.generatedByAi && project.description.contains("starter", true)) {
                    "Template Fallback Starter Project: Built from a pre-bundled template."
                } else {
                    null
                }
            )
        }
        refreshFileTree(project.id)
        viewModelScope.launch(Dispatchers.IO) {
            refreshApkMetadataIfPresent(project)
        }
        if (project.type == ProjectType.HTML_WEB && previewServer.isRunning) {
            startWebPreview(project)
        }
    }

    fun requestCloseActiveProject(forceClose: Boolean = false) {
        val active = _uiState.value.activeProject ?: return
        if (_uiState.value.hasUnsavedChanges && !forceClose) {
            _uiState.update {
                it.copy(
                    pendingConfirmation = PendingStudioConfirmation(
                        id = UUID.randomUUID().toString(),
                        title = "Unsaved Changes in '${_uiState.value.selectedFilePath}'",
                        description = "Discard unsaved edits and close '${active.name}'?",
                        actionType = ConfirmationActionType.CLOSE_UNSAVED_PROJECT,
                        projectId = active.id
                    )
                )
            }
            return
        }
        if (previewServer.isRunning) {
            previewServer.stop()
        }
        viewModelScope.launch(Dispatchers.IO) {
            workspaceManager.updateProjectStatus(active.id, runStatus = ProjectRunStatus.STOPPED)
            refreshProjects(active.id)
        }
        _uiState.update {
            it.copy(
                activeProject = null,
                selectedFilePath = null,
                editorContent = "",
                savedContentSnapshot = "",
                undoStack = emptyList(),
                redoStack = emptyList(),
                previewUrl = null,
                isPreviewFullscreen = false,
                validationResult = null,
                compilerDiagnostics = emptyList(),
                activeTab = DevStudioTab.DASHBOARD
            )
        }
        postStatus("Closed '${active.name}' and stopped active runtime services.")
    }

    fun refreshFileTree(projectId: String? = _uiState.value.activeProject?.id, query: String? = null) {
        val pid = projectId ?: return
        val search = query ?: _uiState.value.fileSearchQuery
        viewModelScope.launch(Dispatchers.IO) {
            val nodes = runCatching { workspaceManager.listFileTree(pid, search) }.getOrDefault(emptyList())
            _uiState.update { it.copy(fileNodes = nodes, fileSearchQuery = search) }
        }
    }

    fun openFileInEditor(relativePath: String) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspaceManager.readFile(project.id, relativePath) }
                .onSuccess { text ->
                    _uiState.update {
                        it.copy(
                            selectedFilePath = relativePath,
                            editorContent = text,
                            savedContentSnapshot = text,
                            undoStack = emptyList(),
                            redoStack = emptyList(),
                            aiExplainOutput = null,
                            activeTab = DevStudioTab.EDITOR
                        )
                    }
                }
                .onFailure { err ->
                    postStatus("Could not open file: ${err.message}")
                }
        }
    }

    fun updateEditorContent(newText: String) {
        _uiState.update { state ->
            if (newText == state.editorContent) return@update state
            val nextUndo = (state.undoStack + state.editorContent).takeLast(40)
            state.copy(
                editorContent = newText,
                undoStack = nextUndo,
                redoStack = emptyList()
            )
        }
    }

    fun undoEdit() {
        _uiState.update { state ->
            val prev = state.undoStack.lastOrNull() ?: return@update state
            state.copy(
                editorContent = prev,
                undoStack = state.undoStack.dropLast(1),
                redoStack = (state.redoStack + state.editorContent).takeLast(40)
            )
        }
    }

    fun redoEdit() {
        _uiState.update { state ->
            val next = state.redoStack.lastOrNull() ?: return@update state
            state.copy(
                editorContent = next,
                redoStack = state.redoStack.dropLast(1),
                undoStack = (state.undoStack + state.editorContent).takeLast(40)
            )
        }
    }

    fun saveActiveFile() {
        val project = _uiState.value.activeProject ?: return
        val path = _uiState.value.selectedFilePath ?: return
        val text = _uiState.value.editorContent
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspaceManager.writeFile(project.id, path, text, createBackup = true) }
                .onSuccess {
                    _uiState.update { it.copy(savedContentSnapshot = text) }
                    refreshFileTree(project.id)
                    if (project.type == ProjectType.HTML_WEB && previewServer.isRunning) {
                        pollWebPreviewChanges(forceReload = true)
                        postStatus("Saved $path • Live preview updated")
                    } else {
                        postStatus("Saved $path (backup snapshot created)")
                    }
                }
                .onFailure { postStatus("Save failed: ${it.message}") }
        }
    }

    fun revertActiveFileBackup() {
        val project = _uiState.value.activeProject ?: return
        val path = _uiState.value.selectedFilePath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val reverted = runCatching { workspaceManager.revertLastBackup(project.id, path) }.getOrDefault(false)
            if (reverted) {
                val restored = workspaceManager.readFile(project.id, path)
                _uiState.update {
                    it.copy(
                        editorContent = restored,
                        savedContentSnapshot = restored,
                        undoStack = emptyList(),
                        redoStack = emptyList()
                    )
                }
                if (project.type == ProjectType.HTML_WEB && previewServer.isRunning) {
                    pollWebPreviewChanges(forceReload = true)
                }
                postStatus("Reverted $path to previous backup snapshot.")
            } else {
                postStatus("No earlier backup snapshot found for $path.")
            }
        }
    }

    fun createWorkspaceFile(relativePath: String) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspaceManager.createFile(project.id, relativePath, "") }
                .onSuccess {
                    refreshFileTree(project.id)
                    openFileInEditor(relativePath)
                    postStatus("Created file $relativePath")
                }
                .onFailure { postStatus("Create file failed: ${it.message}") }
        }
    }

    fun createWorkspaceFolder(relativePath: String) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspaceManager.createDirectory(project.id, relativePath) }
                .onSuccess {
                    refreshFileTree(project.id)
                    postStatus("Created folder $relativePath")
                }
                .onFailure { postStatus("Create folder failed: ${it.message}") }
        }
    }

    fun renameWorkspaceEntry(relativePath: String, newName: String) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspaceManager.renameEntry(project.id, relativePath, newName) }
                .onSuccess {
                    refreshFileTree(project.id)
                    postStatus("Renamed $relativePath to $newName")
                }
                .onFailure { postStatus("Rename failed: ${it.message}") }
        }
    }

    fun requestDeleteFileConfirmation(relativePath: String) {
        val project = _uiState.value.activeProject ?: return
        _uiState.update {
            it.copy(
                pendingConfirmation = PendingStudioConfirmation(
                    id = UUID.randomUUID().toString(),
                    title = "Confirm File Deletion",
                    description = "Delete '$relativePath' from project '${project.name}'? A backup snapshot will be saved if it is a file.",
                    actionType = ConfirmationActionType.DELETE_FILE,
                    targetPathOrCommand = relativePath,
                    projectId = project.id
                )
            )
        }
    }

    fun requestDeleteProjectConfirmation(project: DevProject) {
        _uiState.update {
            it.copy(
                pendingConfirmation = PendingStudioConfirmation(
                    id = UUID.randomUUID().toString(),
                    title = "Delete Project '${project.name}'?",
                    description = "This permanently removes workspace folder '${project.id}' and all its files.",
                    actionType = ConfirmationActionType.DELETE_PROJECT,
                    targetPathOrCommand = project.id,
                    projectId = project.id
                )
            )
        }
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(pendingConfirmation = null) }
    }

    fun confirmPendingAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null) }
        when (pending.actionType) {
            ConfirmationActionType.DELETE_FILE -> viewModelScope.launch(Dispatchers.IO) {
                runCatching { workspaceManager.deleteEntry(pending.projectId, pending.targetPathOrCommand) }
                    .onSuccess {
                        if (_uiState.value.selectedFilePath == pending.targetPathOrCommand) {
                            _uiState.update { it.copy(selectedFilePath = null, editorContent = "", savedContentSnapshot = "") }
                        }
                        refreshFileTree(pending.projectId)
                        postStatus("Deleted ${pending.targetPathOrCommand}")
                    }
                    .onFailure { postStatus("Delete failed: ${it.message}") }
            }
            ConfirmationActionType.DELETE_PROJECT -> viewModelScope.launch(Dispatchers.IO) {
                val activeId = _uiState.value.activeProject?.id
                if (activeId == pending.projectId && previewServer.isRunning) {
                    previewServer.stop()
                    _uiState.update { it.copy(previewUrl = null) }
                }
                workspaceManager.deleteProject(pending.projectId)
                refreshProjects()
                postStatus("Deleted project.")
            }
            ConfirmationActionType.APPLY_CODE_DIFF -> {
                val proposal = pending.proposal ?: return
                applyProposalNow(proposal)
            }
            ConfirmationActionType.RUN_TERMUX_COMMAND -> {
                executeTermuxCommand(pending.targetPathOrCommand, TerminalSource.USER, confirmed = true)
            }
            ConfirmationActionType.INSTALL_APK -> {
                launchVerifiedApkInstall(pending.targetPathOrCommand)
            }
            ConfirmationActionType.OVERWRITE_SHARED_PROJECT -> {
                requestBuildAndroidApkViaTermux(overwriteConfirmed = true)
            }
            ConfirmationActionType.CLOSE_UNSAVED_PROJECT -> {
                requestCloseActiveProject(forceClose = true)
            }
        }
    }

    fun runAutonomousBuild(prompt: String, type: ProjectType, optionalName: String? = null) {
        if (prompt.isBlank()) {
            postStatus("Enter an app or website specification prompt.")
            return
        }
        activeAgentJob?.cancel()
        activeAgentJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    activeProjectLimitationNotice = null,
                    agentPhase = DevAgentPhase.PLANNING,
                    agentSteps = listOf(
                        AgentStepLog(
                            id = UUID.randomUUID().toString(),
                            phase = DevAgentPhase.PLANNING,
                            title = "Analyzing specification",
                            detail = "Target: ${type.label} • Prompt: ${prompt.take(100)}"
                        )
                    )
                )
            }
            try {
                val plan = autonomousAgent.planAndGenerateProject(prompt, type, optionalName)
                appendAgentStep(
                    DevAgentPhase.CREATING_PROJECT,
                    if (plan.generatedByAi) "AI Architecture Planned" else "Template Fallback Active",
                    "${plan.projectName} • ${plan.files.size} files" +
                        (plan.limitationNotice?.let { "\n$it" }.orEmpty()),
                    isSuccess = true
                )
                val fileMap = plan.files.associate { it.relativePath to it.content }
                val entryPoint = if (type == ProjectType.ANDROID_KOTLIN) {
                    plan.files.firstOrNull { it.relativePath.endsWith("MainActivity.kt") }?.relativePath
                        ?: "app/src/main/AndroidManifest.xml"
                } else {
                    "index.html"
                }
                val project = withContext(Dispatchers.IO) {
                    workspaceManager.createProject(
                        name = plan.projectName,
                        packageName = plan.packageName,
                        type = type,
                        entryPoint = entryPoint,
                        description = plan.summary,
                        files = fileMap,
                        generatedByAi = plan.generatedByAi
                    )
                }
                appendAgentStep(DevAgentPhase.WRITING_CODE, "Workspace created", "${project.id} (${fileMap.size} files)")

                val validation = withContext(Dispatchers.IO) { BuildAndRunEngine.validateProject(project) }
                appendAgentStep(
                    DevAgentPhase.VALIDATING,
                    if (validation.isValid) "Validation passed" else "Validation issues detected",
                    validation.summary,
                    isSuccess = validation.isValid
                )

                val finalProject = withContext(Dispatchers.IO) {
                    workspaceManager.updateProjectStatus(
                        projectId = project.id,
                        buildStatus = if (type == ProjectType.HTML_WEB && validation.isValid) {
                            ProjectBuildStatus.BUILD_SUCCESSFUL
                        } else {
                            ProjectBuildStatus.NOT_BUILT
                        },
                        runStatus = if (type == ProjectType.HTML_WEB && validation.isValid) {
                            ProjectRunStatus.READY_TO_RUN
                        } else {
                            ProjectRunStatus.NOT_BUILT
                        },
                        hasGradleWrapper = validation.hasGradleWrapper
                    ) ?: project
                }

                refreshProjects(finalProject.id)
                _uiState.update {
                    it.copy(
                        activeProject = finalProject,
                        validationResult = validation,
                        compilerDiagnostics = validation.diagnostics,
                        activeProjectLimitationNotice = plan.limitationNotice,
                        isBusy = false,
                        agentPhase = DevAgentPhase.COMPLETED
                    )
                }
                openFileInEditor(entryPoint)
                if (type == ProjectType.HTML_WEB && validation.isValid) {
                    startWebPreview(finalProject)
                }
                appendAgentStep(
                    DevAgentPhase.COMPLETED,
                    "Ready in Studio",
                    plan.limitationNotice ?: "Open Editor, Live Preview, or Build & Termux."
                )
            } catch (e: Exception) {
                appendAgentStep(DevAgentPhase.FAILED, "Agent stopped", e.message ?: "Unknown error", isSuccess = false)
                _uiState.update { it.copy(isBusy = false, agentPhase = DevAgentPhase.FAILED) }
                postStatus("Generation failed: ${e.message}")
            }
        }
    }

    fun cancelAgentJob() {
        activeAgentJob?.cancel()
        activeAgentJob = null
        appendAgentStep(DevAgentPhase.CANCELLED, "Cancelled by user", "Stopped active workflow.", isSuccess = false)
        _uiState.update { it.copy(isBusy = false, agentPhase = DevAgentPhase.CANCELLED) }
    }

    fun explainCurrentFile() {
        val project = _uiState.value.activeProject ?: return
        val path = _uiState.value.selectedFilePath ?: return
        val content = _uiState.value.editorContent
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, aiExplainOutput = "Analyzing $path...") }
            val explanation = autonomousAgent.explainCode(project, path, content)
            _uiState.update { it.copy(isBusy = false, aiExplainOutput = explanation) }
        }
    }

    fun requestAiCodeEdit(instruction: String) {
        val project = _uiState.value.activeProject ?: return
        val path = _uiState.value.selectedFilePath ?: return
        val content = _uiState.value.editorContent
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = autonomousAgent.proposeScopedCodeEdit(workspaceManager, project, path, content, instruction)
            _uiState.update { it.copy(isBusy = false) }
            result.onSuccess { proposal ->
                _uiState.update { it.copy(pendingProposal = proposal) }
            }.onFailure { err ->
                postStatus(err.message ?: "AI code edit failed.")
            }
        }
    }

    fun dismissProposal() {
        _uiState.update { it.copy(pendingProposal = null) }
    }

    fun approveProposal(proposal: DevChangeProposal) {
        _uiState.update { it.copy(pendingProposal = null) }
        applyProposalNow(proposal)
    }

    private fun applyProposalNow(proposal: DevChangeProposal) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val count = workspaceManager.applyChangeProposal(proposal.projectId, proposal)
                val curPath = _uiState.value.selectedFilePath
                if (curPath != null) {
                    val refreshed = runCatching { workspaceManager.readFile(proposal.projectId, curPath) }.getOrNull()
                    if (refreshed != null) {
                        _uiState.update {
                            it.copy(editorContent = refreshed, savedContentSnapshot = refreshed)
                        }
                    }
                }
                refreshFileTree(proposal.projectId)
                val activeProj = workspaceManager.getProject(proposal.projectId)
                if (activeProj?.type == ProjectType.HTML_WEB && previewServer.isRunning) {
                    pollWebPreviewChanges(forceReload = true)
                }
                postStatus("Applied $count file change(s) with backup snapshot.")
            }.onFailure {
                postStatus("Failed applying diff: ${it.message}")
            }
        }
    }

    fun validateActiveProject() {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    activeProject = project.copy(buildStatus = ProjectBuildStatus.VALIDATING)
                )
            }
            val validation = BuildAndRunEngine.validateProject(project)
            val apk = BuildAndRunEngine.findBuiltDebugApk(File(project.rootPath))
            val apkInfo = apk?.let { BuildAndRunEngine.verifyApkWithMetadata(it, getApplication()) }?.takeIf { it.isValid }
            val updated = workspaceManager.updateProjectStatus(
                projectId = project.id,
                buildStatus = when {
                    apkInfo != null -> ProjectBuildStatus.APK_READY
                    !validation.isValid -> ProjectBuildStatus.BUILD_FAILED
                    else -> ProjectBuildStatus.NOT_BUILT
                },
                runStatus = when {
                    project.type == ProjectType.HTML_WEB && validation.isValid -> ProjectRunStatus.READY_TO_RUN
                    apkInfo != null -> ProjectRunStatus.READY_TO_RUN
                    !validation.isValid -> ProjectRunStatus.BUILD_FAILED
                    else -> project.runStatus
                },
                lastApkPath = apkInfo?.apkPath,
                lastApkPackage = apkInfo?.packageName,
                lastApkVersion = apkInfo?.versionName,
                hasGradleWrapper = validation.hasGradleWrapper,
                lastErrorSummary = if (!validation.isValid) validation.summary else null,
                clearErrorSummary = validation.isValid
            )
            _uiState.update {
                it.copy(
                    activeProject = updated ?: project,
                    validationResult = validation,
                    compilerDiagnostics = validation.diagnostics,
                    lastApkVerification = apkInfo
                )
            }
            refreshProjects(project.id)
            postStatus(validation.summary)
        }
    }

    fun aiAutoFixBuildErrors() {
        val project = _uiState.value.activeProject ?: return
        val allDiags = _uiState.value.compilerDiagnostics
        // Only real errors are sent to the AI; warnings never block a build.
        val diags = allDiags.filter { it.severity == "ERROR" }
        if (diags.isEmpty()) {
            postStatus(
                if (allDiags.isEmpty()) "No build diagnostics to auto-fix."
                else "Only warnings were found, nothing is blocking the build."
            )
            return
        }
        val attempt = _uiState.value.buildRetryCount + 1
        if (attempt > DevSandboxGuard.MAX_BUILD_AUTO_FIX_RETRIES) {
            postStatus("Reached max AI auto-fix retry limit (${DevSandboxGuard.MAX_BUILD_AUTO_FIX_RETRIES}). Review code or edit manually in Editor.")
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    agentPhase = DevAgentPhase.FIXING_ERRORS,
                    buildRetryCount = attempt
                )
            }
            appendAgentStep(
                DevAgentPhase.FIXING_ERRORS,
                "Auto-Fix Attempt $attempt / ${DevSandboxGuard.MAX_BUILD_AUTO_FIX_RETRIES}",
                "Analyzing ${diags.size} build diagnostic(s)..."
            )
            val result = autonomousAgent.proposeBuildErrorFix(workspaceManager, project, diags)
            _uiState.update { it.copy(isBusy = false, agentPhase = DevAgentPhase.AWAITING_CONFIRMATION) }
            result.onSuccess { proposal ->
                _uiState.update { it.copy(pendingProposal = proposal) }
                postStatus("Auto-Fix attempt $attempt/$MAX_AUTO_FIX: review proposed diff before applying.")
            }.onFailure {
                _uiState.update { state -> state.copy(agentPhase = DevAgentPhase.FAILED) }
                postStatus("Auto-fix unavailable: ${it.message}")
            }
        }
    }

    fun startWebPreview(project: DevProject? = _uiState.value.activeProject) {
        val target = project ?: return
        if (target.type != ProjectType.HTML_WEB) {
            postStatus("Web preview server supports HTML Web projects.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val validation = BuildAndRunEngine.validateProject(target)
            if (!validation.isValid) {
                val updated = workspaceManager.updateProjectStatus(
                    target.id,
                    buildStatus = ProjectBuildStatus.BUILD_FAILED,
                    runStatus = ProjectRunStatus.ERROR,
                    lastErrorSummary = validation.summary
                )
                _uiState.update {
                    it.copy(
                        activeProject = updated ?: target,
                        validationResult = validation,
                        compilerDiagnostics = validation.diagnostics,
                        activeTab = DevStudioTab.BUILD_TERMUX
                    )
                }
                postStatus("Web validation failed before preview: ${validation.summary}")
                return@launch
            }
            runCatching {
                val rootDir = File(target.rootPath)
                val url = previewServer.start(rootDir)
                lastObservedWebToken = previewServer.getDirectoryChangeToken(rootDir)
                val updated = workspaceManager.updateProjectStatus(
                    target.id,
                    buildStatus = ProjectBuildStatus.BUILD_SUCCESSFUL,
                    runStatus = ProjectRunStatus.RUNNING,
                    clearErrorSummary = true
                )
                _uiState.update {
                    it.copy(
                        activeProject = updated ?: target,
                        previewUrl = url,
                        validationResult = validation,
                        compilerDiagnostics = validation.diagnostics,
                        webConsoleLogs = emptyList(),
                        previewLiveReloadToken = it.previewLiveReloadToken + 1,
                        activeTab = DevStudioTab.PREVIEW
                    )
                }
                postStatus("Live HTTP server running on $url")
            }.onFailure { err ->
                postStatus("Failed starting local preview server: ${err.message}")
            }
        }
    }

    fun stopWebPreview() {
        val project = _uiState.value.activeProject
        previewServer.stop()
        viewModelScope.launch(Dispatchers.IO) {
            val updated = project?.let {
                workspaceManager.updateProjectStatus(it.id, runStatus = ProjectRunStatus.STOPPED)
            }
            _uiState.update {
                it.copy(
                    activeProject = updated ?: project,
                    previewUrl = null,
                    isPreviewFullscreen = false
                )
            }
            postStatus("Local web preview server stopped.")
        }
    }

    fun pollWebPreviewChanges(forceReload: Boolean = false) {
        val project = _uiState.value.activeProject ?: return
        if (!previewServer.isRunning || project.type != ProjectType.HTML_WEB) return
        if (!_uiState.value.previewAutoReloadEnabled && !forceReload) return

        val token = previewServer.getDirectoryChangeToken(File(project.rootPath))
        if (forceReload || (token != 0L && token != lastObservedWebToken)) {
            lastObservedWebToken = token
            _uiState.update { it.copy(previewLiveReloadToken = it.previewLiveReloadToken + 1) }
        }
    }

    fun togglePreviewAutoReload() {
        _uiState.update { it.copy(previewAutoReloadEnabled = !it.previewAutoReloadEnabled) }
    }

    fun togglePreviewViewportMode() {
        _uiState.update { it.copy(isPreviewDesktopMode = !it.isPreviewDesktopMode) }
    }

    fun togglePreviewFullscreen() {
        _uiState.update { it.copy(isPreviewFullscreen = !it.isPreviewFullscreen) }
    }

    fun recordWebConsoleMessage(level: String, message: String, sourceId: String, line: Int) {
        val entry = WebConsoleEntry(
            id = UUID.randomUUID().toString(),
            level = level,
            message = message,
            sourceId = sourceId.substringAfterLast('/'),
            lineNumber = line
        )
        _uiState.update { state ->
            state.copy(webConsoleLogs = (listOf(entry) + state.webConsoleLogs).take(60))
        }
    }

    fun refreshTermuxEnvironment() {
        viewModelScope.launch {
            val env = TermuxBridge.inspectEnvironment(getApplication())
            _uiState.update { it.copy(termuxStatus = env) }
        }
    }

    fun probeTermuxToolchainLive() {
        viewModelScope.launch {
            val execId = UUID.randomUUID().toString()
            _uiState.update { it.copy(isCommandRunning = true, activeCommandExecutionId = execId) }
            val (env, probeResult) = TermuxBridge.probeTermuxToolchainLive(getApplication())
            appendCommandResultLog(TerminalSource.SYSTEM, probeResult)
            _uiState.update {
                it.copy(
                    termuxStatus = env,
                    isCommandRunning = false,
                    activeCommandExecutionId = null
                )
            }
            if (probeResult.state == TermuxCommandState.COMPLETED) {
                postStatus("Live Termux toolchain scan completed.")
            } else {
                postStatus(probeResult.errorMessage ?: "Termux live scan finished (${probeResult.state.label}).")
            }
        }
    }

    fun executeTermuxCommand(
        command: String,
        source: TerminalSource = TerminalSource.USER,
        confirmed: Boolean = false
    ) {
        val clean = command.trim()
        if (clean.isBlank()) return

        val risk = DevSandboxGuard.classifyCommand(clean)
        if (risk == SandboxCommandRisk.BLOCKED_DANGEROUS) {
            appendTerminalLog(
                source = source,
                cmd = clean,
                output = "BLOCKED: Dangerous/root command rejected by security policy.",
                stderr = "",
                exitCode = 126,
                state = TermuxCommandState.FAILED,
                risk = risk
            )
            postStatus("Dangerous command blocked.")
            return
        }
        if (risk == SandboxCommandRisk.REQUIRES_CONFIRMATION && !confirmed) {
            _uiState.update {
                it.copy(
                    pendingConfirmation = PendingStudioConfirmation(
                        id = UUID.randomUUID().toString(),
                        title = "Confirm Shell Command",
                        description = "Run this command inside Termux?\n\n$clean",
                        actionType = ConfirmationActionType.RUN_TERMUX_COMMAND,
                        targetPathOrCommand = clean
                    )
                )
            }
            return
        }

        val activeProject = _uiState.value.activeProject
        val targetWorkdir = activeProject?.sharedWorkingDir
            ?: _uiState.value.sharedWorkspace?.resolvedTermuxBasePath
            ?: "/data/data/com.termux/files/home"

        viewModelScope.launch {
            val execId = UUID.randomUUID().toString()
            _uiState.update { it.copy(isCommandRunning = true, activeCommandExecutionId = execId) }
            val result = TermuxBridge.executeCommand(
                context = getApplication(),
                command = clean,
                termuxWorkingDir = targetWorkdir,
                userConfirmed = true,
                executionId = execId
            )
            appendCommandResultLog(source, result)
            val parsedDiags = BuildAndRunEngine.parseGradleCompilerOutput(result.stdout + "\n" + result.stderr)
            _uiState.update { state ->
                state.copy(
                    isCommandRunning = false,
                    activeCommandExecutionId = null,
                    compilerDiagnostics = if (parsedDiags.isNotEmpty()) parsedDiags else state.compilerDiagnostics
                )
            }
            if (result.state != TermuxCommandState.COMPLETED) {
                postStatus(result.errorMessage ?: "Command exited with ${result.exitCode ?: result.state.label}")
            }
        }
    }

    fun cancelActiveBuildOrCommand() {
        val execId = _uiState.value.activeCommandExecutionId
        if (!execId.isNullOrBlank()) {
            TermuxBridge.cancelExecution(execId)
        }
        activeBuildJob?.cancel()
        activeBuildJob = null
        val activeProj = _uiState.value.activeProject
        viewModelScope.launch(Dispatchers.IO) {
            if (activeProj != null) {
                workspaceManager.updateProjectStatus(activeProj.id, buildStatus = ProjectBuildStatus.CANCELLED)
                refreshProjects(activeProj.id)
            }
        }
        _uiState.update {
            it.copy(
                isCommandRunning = false,
                activeCommandExecutionId = null,
                transferProgress = TransferProgressState(),
                agentPhase = DevAgentPhase.CANCELLED
            )
        }
        postStatus("Cancelled active build / Termux command wait.")
    }

    fun requestBuildAndroidApkViaTermux(overwriteConfirmed: Boolean = false) {
        val project = _uiState.value.activeProject ?: return
        if (project.type != ProjectType.ANDROID_KOTLIN) {
            postStatus("Only Android Kotlin projects produce APKs.")
            return
        }

        activeBuildJob?.cancel()
        activeBuildJob = viewModelScope.launch {
            val app = getApplication<Application>()
            // 1. Structural & source validation before dispatch
            workspaceManager.updateProjectStatus(project.id, buildStatus = ProjectBuildStatus.VALIDATING)
            val validation = withContext(Dispatchers.IO) { BuildAndRunEngine.validateProject(project) }
            if (!validation.isValid) {
                val updated = withContext(Dispatchers.IO) {
                    workspaceManager.updateProjectStatus(
                        project.id,
                        buildStatus = ProjectBuildStatus.BUILD_FAILED,
                        runStatus = ProjectRunStatus.BUILD_FAILED,
                        lastErrorSummary = validation.summary
                    )
                }
                _uiState.update {
                    it.copy(
                        activeProject = updated ?: project,
                        validationResult = validation,
                        compilerDiagnostics = validation.diagnostics
                    )
                }
                postStatus("Validation failed: fix errors before building.")
                return@launch
            }

            // 2. Shared Workspace transfer (NOVA_AI_Projects/AndroidProjects/<Name>)
            val sharedCfg = sharedWorkspaceBridge.getConfig()
            if (!sharedCfg.isAuthorized) {
                postStatus("Authorize NOVA_AI_Projects/ shared folder first so Termux can read & compile the project.")
                return@launch
            }

            withContext(Dispatchers.IO) {
                workspaceManager.updateProjectStatus(project.id, buildStatus = ProjectBuildStatus.PREPARING)
            }
            val transfer = sharedWorkspaceBridge.transferProjectToSharedWorkspace(
                project = project,
                sourceRoot = File(project.rootPath),
                overwriteConfirmed = overwriteConfirmed,
                onProgress = { progress -> _uiState.update { it.copy(transferProgress = progress) } }
            )
            _uiState.update { it.copy(transferProgress = TransferProgressState()) }

            if (transfer.requiresOverwriteConfirmation) {
                _uiState.update {
                    it.copy(
                        pendingConfirmation = PendingStudioConfirmation(
                            id = UUID.randomUUID().toString(),
                            title = "Overwrite Shared Workspace Folder?",
                            description = "Folder 'NOVA_AI_Projects/AndroidProjects/${transfer.sharedFolderName}' already exists. Sync latest edits and overwrite it before building?",
                            actionType = ConfirmationActionType.OVERWRITE_SHARED_PROJECT,
                            projectId = project.id
                        )
                    )
                }
                return@launch
            }

            val workingDirPosix = transfer.sharedTermuxPath
            if (!transfer.success || workingDirPosix.isNullOrBlank()) {
                val msg = transfer.failedReason
                    ?: "Could not map SAF folder to Termux POSIX path. Set custom base path in Dashboard if using external SD."
                withContext(Dispatchers.IO) {
                    workspaceManager.updateProjectStatus(project.id, buildStatus = ProjectBuildStatus.BUILD_FAILED, lastErrorSummary = msg)
                }
                postStatus(msg)
                return@launch
            }

            // 3. Build plan inspection (Gradle wrapper vs Termux system gradle)
            val buildPlan = BuildAndRunEngine.prepareAndroidBuildPlan(File(project.rootPath))
            if (!buildPlan.isReadyToDispatch) {
                val msg = buildPlan.configurationIssues.joinToString("\n").ifBlank { "Build configuration incomplete." }
                postStatus(msg)
                return@launch
            }
            if (buildPlan.configurationIssues.isNotEmpty()) {
                appendAgentStep(
                    DevAgentPhase.BUILDING_OR_PREVIEWING,
                    "Build Wrapper Note",
                    buildPlan.configurationIssues.joinToString("\n")
                )
            }

            // 4. Execute Gradle build in exact project working directory via Termux
            val execId = UUID.randomUUID().toString()
            withContext(Dispatchers.IO) {
                workspaceManager.updateProjectStatus(
                    project.id,
                    buildStatus = ProjectBuildStatus.BUILDING,
                    sharedWorkingDir = workingDirPosix,
                    hasGradleWrapper = buildPlan.hasGradleWrapper
                )
            }
            _uiState.update {
                it.copy(
                    isCommandRunning = true,
                    activeCommandExecutionId = execId,
                    agentPhase = DevAgentPhase.BUILDING_OR_PREVIEWING
                )
            }

            val cmdResult = TermuxBridge.executeCommand(
                context = app,
                command = buildPlan.command,
                termuxWorkingDir = workingDirPosix,
                userConfirmed = true,
                timeoutMs = BuildAndRunEngine.BUILD_TIMEOUT_MS,
                executionId = execId
            )
            appendCommandResultLog(TerminalSource.BUILD, cmdResult)

            // 5. Try collecting APK from shared workspace into BuildOutputs & internal storage if exitCode == 0
            val internalRoot = File(project.rootPath)
            if (cmdResult.state == TermuxCommandState.COMPLETED && cmdResult.exitCode == 0) {
                withContext(Dispatchers.IO) {
                    sharedWorkspaceBridge.collectApkFromSharedOrPosixWorkspace(project, internalRoot, workingDirPosix)
                }
            }

            // 6. Real verification of Termux exit code AND APK integrity
            val report = withContext(Dispatchers.IO) {
                BuildAndRunEngine.evaluateTermuxBuildResult(
                    result = cmdResult,
                    projectRootToSearchApk = File(workingDirPosix).takeIf {
                        BuildAndRunEngine.findBuiltDebugApk(it) != null
                    } ?: internalRoot,
                    context = app
                )
            }

            val updated = withContext(Dispatchers.IO) {
                workspaceManager.updateProjectStatus(
                    projectId = project.id,
                    buildStatus = report.buildStatus,
                    runStatus = when (report.buildStatus) {
                        ProjectBuildStatus.APK_READY, ProjectBuildStatus.BUILD_SUCCESSFUL -> ProjectRunStatus.READY_TO_RUN
                        ProjectBuildStatus.CANCELLED -> ProjectRunStatus.STOPPED
                        else -> ProjectRunStatus.BUILD_FAILED
                    },
                    lastErrorSummary = if (report.buildStatus == ProjectBuildStatus.APK_READY) null else report.summary,
                    clearErrorSummary = report.buildStatus == ProjectBuildStatus.APK_READY,
                    lastApkPath = report.apkInfo?.apkPath,
                    lastApkPackage = report.apkInfo?.packageName,
                    lastApkVersion = report.apkInfo?.versionName
                )
            }

            _uiState.update { state ->
                state.copy(
                    activeProject = updated ?: project,
                    compilerDiagnostics = report.diagnostics,
                    lastApkVerification = report.apkInfo,
                    isCommandRunning = false,
                    activeCommandExecutionId = null,
                    agentPhase = if (report.buildStatus == ProjectBuildStatus.APK_READY) DevAgentPhase.COMPLETED else DevAgentPhase.FAILED
                )
            }
            refreshProjects(project.id)
            postStatus(report.summary)
        }
    }

    fun requestApkInstallConfirmation() {
        val project = _uiState.value.activeProject ?: return
        val apk = project.lastApkPath?.let { File(it) }?.takeIf { it.exists() }
            ?: BuildAndRunEngine.findBuiltDebugApk(File(project.rootPath))
        if (apk == null) {
            postStatus("No built APK found. Run Gradle APK Build in Termux first.")
            return
        }
        val verified = BuildAndRunEngine.verifyApkWithMetadata(apk, getApplication())
        if (!verified.isValid) {
            postStatus("APK invalid: ${verified.failureReason}")
            return
        }
        val metaSummary = buildString {
            append("APK File: ${verified.fileName} (${verified.sizeBytes / 1024} KB)")
            verified.packageName?.let { append("\nPackage: $it") }
            verified.versionName?.let { append("\nVersion: $it") }
        }
        _uiState.update {
            it.copy(
                lastApkVerification = verified,
                pendingConfirmation = PendingStudioConfirmation(
                    id = UUID.randomUUID().toString(),
                    title = "Confirm APK Installation",
                    description = "$metaSummary\n\nOpen Android Package Installer to install this build?",
                    actionType = ConfirmationActionType.INSTALL_APK,
                    targetPathOrCommand = apk.absolutePath,
                    projectId = project.id
                )
            )
        }
    }

    private fun launchVerifiedApkInstall(apkPath: String) {
        val app = getApplication<Application>()
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            postStatus("APK file not found.")
            return
        }
        runCatching {
            val intent = BuildAndRunEngine.createApkInstallOrShareIntent(app, apkFile)
            app.startActivity(intent)
            postStatus("Opened Android Package Installer. Confirm installation in the system prompt.")
        }.onFailure {
            runCatching {
                app.startActivity(BuildAndRunEngine.createApkShareIntent(app, apkFile))
            }.onFailure { err ->
                postStatus("Could not open installer/share sheet: ${err.message}")
            }
        }
    }

    fun launchInstalledProjectApp() {
        val project = _uiState.value.activeProject ?: return
        val pkg = _uiState.value.lastApkVerification?.packageName
            ?: project.lastApkPackage
            ?: project.packageName
        BuildAndRunEngine.launchInstalledApp(getApplication(), pkg)
            .onSuccess { msg -> postStatus(msg) }
            .onFailure { err -> postStatus(err.message ?: "App not installed on device yet.") }
    }

    fun importZipFromUri(uri: Uri, projectName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true) }
            runCatching {
                val input = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to read selected ZIP stream.")
                input.use { stream ->
                    workspaceManager.importProjectZip(stream, projectName)
                }
            }.onSuccess { imported ->
                _uiState.update { it.copy(isBusy = false) }
                refreshProjects(imported.id)
                postStatus("Imported '${imported.name}' (${imported.type.label})")
            }.onFailure { err ->
                _uiState.update { it.copy(isBusy = false) }
                postStatus("ZIP import rejected: ${err.message}")
            }
        }
    }

    fun exportActiveProjectToUri(uri: Uri) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Cannot write to target ZIP file.")
                out.use { workspaceManager.exportProjectToZip(project.id, it) }
            }.onSuccess { count ->
                postStatus("Exported $count files to ZIP.")
            }.onFailure { err ->
                postStatus("Export failed: ${err.message}")
            }
        }
    }

    fun clearTerminalLogs() {
        _uiState.update { it.copy(terminalLogs = emptyList()) }
    }

    private fun appendCommandResultLog(source: TerminalSource, result: TermuxCommandResult) {
        val formattedOutput = buildString {
            if (result.stdout.isNotBlank()) append(result.stdout.trimEnd())
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("[stderr]\n").append(result.stderr.trimEnd())
            }
            if (!result.errorMessage.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append("[info] ").append(result.errorMessage)
            }
            if (isEmpty()) {
                append("(no stdout/stderr output)")
            }
        }
        appendTerminalLog(
            source = source,
            cmd = result.command,
            workingDir = result.workingDir,
            output = formattedOutput,
            stderr = result.stderr,
            exitCode = result.exitCode,
            state = result.state,
            executionTimeMs = result.executionTimeMs,
            risk = result.risk,
            executionId = result.executionId
        )
    }

    private fun appendTerminalLog(
        source: TerminalSource,
        cmd: String,
        output: String,
        stderr: String,
        exitCode: Int?,
        state: TermuxCommandState,
        risk: SandboxCommandRisk,
        workingDir: String = "",
        executionTimeMs: Long = 0L,
        executionId: String = UUID.randomUUID().toString()
    ) {
        val entry = TerminalLogEntry(
            id = UUID.randomUUID().toString(),
            executionId = executionId,
            timestamp = System.currentTimeMillis(),
            source = source,
            command = cmd,
            workingDir = workingDir,
            output = output,
            stderr = stderr,
            exitCode = exitCode,
            executionState = state,
            executionTimeMs = executionTimeMs,
            risk = risk
        )
        _uiState.update { it.copy(terminalLogs = (listOf(entry) + it.terminalLogs).take(50)) }
    }

    private fun appendAgentStep(phase: DevAgentPhase, title: String, detail: String, isSuccess: Boolean = true) {
        val step = AgentStepLog(
            id = UUID.randomUUID().toString(),
            phase = phase,
            title = title,
            detail = detail,
            isSuccess = isSuccess
        )
        _uiState.update {
            it.copy(agentPhase = phase, agentSteps = it.agentSteps + step)
        }
    }

    private fun postStatus(msg: String) {
        _uiState.update { it.copy(statusMessage = msg) }
    }

    override fun onCleared() {
        previewServer.stop()
        super.onCleared()
    }

    companion object {
        private const val MAX_AUTO_FIX = DevSandboxGuard.MAX_BUILD_AUTO_FIX_RETRIES
    }
}
