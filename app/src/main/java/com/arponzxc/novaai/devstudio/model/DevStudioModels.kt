package com.arponzxc.novaai.devstudio.model

enum class ProjectType(val label: String) {
    ANDROID_KOTLIN("Android Kotlin"),
    HTML_WEB("HTML Web App")
}

enum class DetectedProjectType(val label: String) {
    NATIVE_ANDROID("Native Android Project"),
    HTML_WEBSITE("HTML Website Project"),
    IMPORTED_PROJECT("Imported Project"),
    INVALID("Invalid Project")
}

enum class ProjectBuildStatus(val label: String) {
    NOT_BUILT("Not Built"),
    VALIDATING("Validating"),
    PREPARING("Preparing"),
    BUILDING("Building"),
    BUILD_SUCCESSFUL("Build Successful"),
    BUILD_FAILED("Build Failed"),
    CANCELLING("Cancelling"),
    CANCELLED("Cancelled"),
    APK_READY("APK Ready")
}

enum class ProjectRunStatus(val label: String) {
    NOT_BUILT("Not Built"),
    BUILDING("Building"),
    BUILD_FAILED("Build Failed"),
    BUILD_SUCCESSFUL("Build Successful"),
    READY_TO_RUN("Ready to Run"),
    RUNNING("Running"),
    STOPPED("Stopped"),
    ERROR("Error")
}

enum class DevAgentPhase(val label: String) {
    IDLE("Idle"),
    PLANNING("Planning"),
    CREATING_PROJECT("Creating Project"),
    GENERATING_FILES("Generating Files"),
    WRITING_CODE("Writing Code"),
    VALIDATING("Validating"),
    BUILDING_OR_PREVIEWING("Building / Previewing"),
    FIXING_ERRORS("Fixing Errors"),
    AWAITING_CONFIRMATION("Awaiting Confirmation"),
    LAUNCHING("Launching"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

enum class TermuxCommandState(val label: String) {
    PENDING("Pending"),
    PERMISSION_REQUIRED("Permission Required"),
    DISPATCHED("Dispatched"),
    RUNNING("Running"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    TIMED_OUT("Timed Out"),
    CANCELLED("Cancelled"),
    RESULT_UNAVAILABLE("Result Unavailable")
}

enum class SandboxCommandRisk(val label: String) {
    SAFE_READ("Safe Read"),
    SAFE_WORKSPACE_BUILD("Workspace Build"),
    REQUIRES_CONFIRMATION("Needs Confirmation"),
    BLOCKED_DANGEROUS("Blocked")
}

enum class TerminalSource(val label: String) {
    AGENT("AGENT"),
    USER("USER"),
    BUILD("BUILD"),
    SYSTEM("SYSTEM")
}

enum class DevStudioTab(val label: String) {
    DASHBOARD("Projects"),
    BUILDER("AI Builder"),
    WORKSPACE("Files"),
    EDITOR("Editor"),
    PREVIEW("Web Live"),
    BUILD_TERMUX("Build & Termux"),
    AGENT("Agent")
}

enum class ConfirmationActionType {
    DELETE_FILE,
    DELETE_PROJECT,
    APPLY_CODE_DIFF,
    RUN_TERMUX_COMMAND,
    INSTALL_APK,
    OVERWRITE_SHARED_PROJECT,
    CLOSE_UNSAVED_PROJECT
}

data class DevProject(
    val id: String,
    val name: String,
    val packageName: String,
    val type: ProjectType,
    val rootPath: String,
    val entryPoint: String,
    val description: String = "",
    val buildStatus: ProjectBuildStatus = ProjectBuildStatus.NOT_BUILT,
    val runStatus: ProjectRunStatus = ProjectRunStatus.NOT_BUILT,
    val lastErrorSummary: String? = null,
    val lastApkPath: String? = null,
    val lastApkPackage: String? = null,
    val lastApkVersion: String? = null,
    val sharedWorkingDir: String? = null,
    val hasGradleWrapper: Boolean = false,
    val generatedByAi: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class DevFileNode(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val language: String,
    val depth: Int
)

enum class DiffAction {
    CREATE,
    MODIFY,
    DELETE
}

data class CodeDiffEntry(
    val relativePath: String,
    val action: DiffAction,
    val beforeContent: String,
    val afterContent: String,
    val summary: String,
    val unifiedDiff: String,
    val addedLines: Int,
    val removedLines: Int
)

data class DevChangeProposal(
    val id: String,
    val projectId: String,
    val title: String,
    val rationale: String,
    val diffs: List<CodeDiffEntry>,
    val createdAt: Long = System.currentTimeMillis()
)

data class BuildErrorDiagnostic(
    val severity: String, // ERROR, WARNING
    val category: String,
    val file: String,
    val line: Int,
    val message: String,
    val suggestedFix: String
)

data class BuildValidationResult(
    val isValid: Boolean,
    val detectedType: DetectedProjectType = DetectedProjectType.INVALID,
    val hasGradleWrapper: Boolean = false,
    val diagnostics: List<BuildErrorDiagnostic>,
    val summary: String
)

data class ApkVerificationResult(
    val isValid: Boolean,
    val apkPath: String,
    val fileName: String,
    val sizeBytes: Long,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val failureReason: String? = null
)

data class ToolAvailability(
    val name: String,
    val isDetected: Boolean,
    val statusDetail: String,
    val isVerifiedViaTermux: Boolean = false
)

data class TermuxEnvironmentStatus(
    val isTermuxInstalled: Boolean,
    val termuxVersionName: String?,
    val isRunCommandPermissionGranted: Boolean,
    val hasLiveProbeCompleted: Boolean = false,
    val allowExternalAppsHint: String,
    val tools: List<ToolAvailability>
)

data class TermuxCommandResult(
    val executionId: String,
    val command: String,
    val workingDir: String,
    val state: TermuxCommandState,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val executionTimeMs: Long,
    val errorMessage: String? = null,
    val risk: SandboxCommandRisk
)

data class TerminalLogEntry(
    val id: String,
    val executionId: String = "",
    val timestamp: Long,
    val source: TerminalSource,
    val command: String,
    val workingDir: String = "",
    val output: String,
    val stderr: String = "",
    val exitCode: Int?,
    val executionState: TermuxCommandState = TermuxCommandState.COMPLETED,
    val executionTimeMs: Long = 0L,
    val risk: SandboxCommandRisk
)

data class WebConsoleEntry(
    val id: String,
    val level: String,
    val message: String,
    val sourceId: String,
    val lineNumber: Int,
    val timestamp: Long = System.currentTimeMillis()
)

data class AgentStepLog(
    val id: String,
    val phase: DevAgentPhase,
    val title: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean = true
)

data class PendingStudioConfirmation(
    val id: String,
    val title: String,
    val description: String,
    val actionType: ConfirmationActionType,
    val targetPathOrCommand: String = "",
    val projectId: String = "",
    val proposal: DevChangeProposal? = null
)

data class TransferProgressState(
    val active: Boolean = false,
    val phase: String = "",
    val copiedFiles: Int = 0,
    val totalFiles: Int = 0,
    val destinationPath: String = ""
)

data class GeneratedFileSpec(
    val relativePath: String,
    val content: String,
    val description: String = ""
)

data class GeneratedProjectPlan(
    val projectName: String,
    val packageName: String,
    val summary: String,
    val architectureNotes: List<String>,
    val files: List<GeneratedFileSpec>,
    val generatedByAi: Boolean,
    val limitationNotice: String? = null
)
