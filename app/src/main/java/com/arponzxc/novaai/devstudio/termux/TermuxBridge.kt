package com.arponzxc.novaai.devstudio.termux

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.arponzxc.novaai.devstudio.model.SandboxCommandRisk
import com.arponzxc.novaai.devstudio.model.TermuxCommandResult
import com.arponzxc.novaai.devstudio.model.TermuxCommandState
import com.arponzxc.novaai.devstudio.model.TermuxEnvironmentStatus
import com.arponzxc.novaai.devstudio.model.ToolAvailability
import com.arponzxc.novaai.devstudio.security.DevSandboxGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TermuxBridge {

    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val PERMISSION_TERMUX_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    private const val PLUGIN_RESULT_STDOUT = "stdout"
    private const val PLUGIN_RESULT_STDERR = "stderr"
    private const val PLUGIN_RESULT_EXIT_CODE = "exitCode"
    private const val PLUGIN_RESULT_ERR = "err"
    private const val PLUGIN_RESULT_ERRMSG = "errmsg"

    private const val TERMUX_HOME_WORKDIR = "/data/data/com.termux/files/home"
    private const val CALLBACK_WAIT_TIMEOUT_MS = 25_000L

    private val pendingExecutions = ConcurrentHashMap<String, CompletableDeferred<Bundle?>>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    const val SETUP_GUIDE_TEXT = """
To enable real Gradle / Node / Python CLI execution & exit codes via Termux:
1. Install Termux (F-Droid or GitHub official APK).
2. Inside Termux run:
   pkg update && pkg upgrade -y
   pkg install openjdk-17 gradle git nodejs-lts python -y
   termux-setup-storage
3. Enable external app commands in Termux:
   mkdir -p ~/.termux
   echo "allow-external-apps = true" >> ~/.termux/termux.properties
   termux-reload-settings
4. Grant NOVA AI the 'Run commands in Termux environment' permission in Android Settings.
    """

    fun onTermuxResultReceived(executionId: String, resultBundle: Bundle?) {
        if (executionId.isBlank()) return
        val deferred = pendingExecutions.remove(executionId) ?: return
        deferred.complete(resultBundle)
    }

    fun cancelExecution(executionId: String): Boolean {
        cancelledIds += executionId
        val removed = pendingExecutions.remove(executionId)
        removed?.cancel()
        return removed != null
    }

    suspend fun inspectEnvironment(context: Context): TermuxEnvironmentStatus = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PACKAGE, 0)
            }
        } catch (_: Exception) {
            null
        }

        val installed = pkgInfo != null
        val versionName = pkgInfo?.versionName
        val permGranted = ContextCompat.checkSelfPermission(
            context,
            PERMISSION_TERMUX_RUN_COMMAND
        ) == PackageManager.PERMISSION_GRANTED

        val tools = listOf(
            ToolAvailability(
                name = "Termux App",
                isDetected = installed,
                statusDetail = if (installed) "Installed (v${versionName ?: "unknown"})" else "Not installed on device",
                isVerifiedViaTermux = true
            ),
            ToolAvailability(
                name = "RUN_COMMAND IPC",
                isDetected = installed && permGranted,
                statusDetail = when {
                    !installed -> "Unavailable (Termux missing)"
                    permGranted -> "Permission granted"
                    else -> "Permission denied — enable in App Permissions"
                },
                isVerifiedViaTermux = true
            ),
            ToolAvailability("Java (Termux)", false, if (installed && permGranted) "Not yet probed inside Termux (Tap Scan Inside Termux)" else "Unavailable without Termux IPC"),
            ToolAvailability("Gradle (Termux)", false, if (installed && permGranted) "Not yet probed inside Termux" else "Unavailable without Termux IPC"),
            ToolAvailability("Python (Termux)", false, if (installed && permGranted) "Not yet probed inside Termux" else "Unavailable without Termux IPC"),
            ToolAvailability("Node.js (Termux)", false, if (installed && permGranted) "Not yet probed inside Termux" else "Unavailable without Termux IPC"),
            ToolAvailability("npm (Termux)", false, if (installed && permGranted) "Not yet probed inside Termux" else "Unavailable without Termux IPC"),
            ToolAvailability("Git (Termux)", false, if (installed && permGranted) "Not yet probed inside Termux" else "Unavailable without Termux IPC"),
            ToolAvailability("Disk Space (df -h)", false, if (installed && permGranted) "Not yet probed inside Termux" else "Unavailable without Termux IPC")
        )

        TermuxEnvironmentStatus(
            isTermuxInstalled = installed,
            termuxVersionName = versionName,
            isRunCommandPermissionGranted = permGranted,
            hasLiveProbeCompleted = false,
            allowExternalAppsHint = SETUP_GUIDE_TEXT.trim(),
            tools = tools
        )
    }

    /**
     * Executes a single probe inside Termux itself via official RUN_COMMAND PendingIntent callback
     * to discover true tool availability inside Termux (never probes NOVA AI's private app process).
     */
    suspend fun probeTermuxToolchainLive(context: Context): Pair<TermuxEnvironmentStatus, TermuxCommandResult> {
        val baseStatus = inspectEnvironment(context)
        if (!baseStatus.isTermuxInstalled || !baseStatus.isRunCommandPermissionGranted) {
            val missingResult = TermuxCommandResult(
                executionId = UUID.randomUUID().toString(),
                command = "Termux environment probe",
                workingDir = TERMUX_HOME_WORKDIR,
                state = if (!baseStatus.isTermuxInstalled) TermuxCommandState.RESULT_UNAVAILABLE else TermuxCommandState.PERMISSION_REQUIRED,
                stdout = "",
                stderr = "",
                exitCode = null,
                executionTimeMs = 0L,
                errorMessage = if (!baseStatus.isTermuxInstalled) "Termux is not installed." else "RUN_COMMAND permission not granted.",
                risk = SandboxCommandRisk.SAFE_READ
            )
            return baseStatus to missingResult
        }

        val probeScript = listOf(
            "echo __JAVA__; (java -version 2>&1 | head -n 1 || echo MISSING)",
            "echo __GRADLE__; (gradle --version 2>&1 | grep -m1 Gradle || echo MISSING)",
            "echo __PYTHON__; (python3 --version 2>&1 || python --version 2>&1 || echo MISSING)",
            "echo __NODE__; (node --version 2>&1 || echo MISSING)",
            "echo __NPM__; (npm --version 2>&1 || echo MISSING)",
            "echo __GIT__; (git --version 2>&1 || echo MISSING)",
            "echo __DF__; (df -h . 2>&1 | tail -n 1 || echo UNKNOWN)"
        ).joinToString("; ")

        val result = executeCommand(
            context = context,
            command = probeScript,
            termuxWorkingDir = TERMUX_HOME_WORKDIR,
            userConfirmed = true,
            timeoutMs = CALLBACK_WAIT_TIMEOUT_MS,
            isInternalProbe = true
        )

        if (result.state != TermuxCommandState.COMPLETED) {
            val updatedTools = baseStatus.tools.map { tool ->
                if (tool.isVerifiedViaTermux) tool
                else tool.copy(
                    isDetected = false,
                    statusDetail = "Termux probe ${result.state.label}: ${result.errorMessage ?: "No callback received"}"
                )
            }
            return baseStatus.copy(hasLiveProbeCompleted = false, tools = updatedTools) to result
        }

        val parsed = parseProbeOutput(result.stdout)
        val liveTools = listOf(
            baseStatus.tools[0],
            baseStatus.tools[1],
            toolAvailabilityFrom("Java (Termux)", parsed["JAVA"]),
            toolAvailabilityFrom("Gradle (Termux)", parsed["GRADLE"]),
            toolAvailabilityFrom("Python (Termux)", parsed["PYTHON"]),
            toolAvailabilityFrom("Node.js (Termux)", parsed["NODE"]),
            toolAvailabilityFrom("npm (Termux)", parsed["NPM"]),
            toolAvailabilityFrom("Git (Termux)", parsed["GIT"]),
            ToolAvailability(
                name = "Disk Space (df -h)",
                isDetected = !parsed["DF"].isNullOrBlank() && parsed["DF"] != "UNKNOWN",
                statusDetail = parsed["DF"] ?: "Unavailable",
                isVerifiedViaTermux = true
            )
        )
        return baseStatus.copy(hasLiveProbeCompleted = true, tools = liveTools) to result
    }

    suspend fun executeCommand(
        context: Context,
        command: String,
        termuxWorkingDir: String,
        userConfirmed: Boolean = false,
        timeoutMs: Long = DevSandboxGuard.MAX_COMMAND_TIMEOUT_MS,
        isInternalProbe: Boolean = false,
        executionId: String = UUID.randomUUID().toString()
    ): TermuxCommandResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val risk = if (isInternalProbe) SandboxCommandRisk.SAFE_READ else DevSandboxGuard.classifyCommand(command)

        if (risk == SandboxCommandRisk.BLOCKED_DANGEROUS) {
            return@withContext TermuxCommandResult(
                executionId = executionId,
                command = command,
                workingDir = termuxWorkingDir,
                state = TermuxCommandState.FAILED,
                stdout = "",
                stderr = "BLOCKED BY SANDBOX GUARD: Root escalation or destructive system command rejected.",
                exitCode = 126,
                executionTimeMs = 0L,
                errorMessage = "Dangerous command blocked.",
                risk = risk
            )
        }
        if (risk == SandboxCommandRisk.REQUIRES_CONFIRMATION && !userConfirmed) {
            return@withContext TermuxCommandResult(
                executionId = executionId,
                command = command,
                workingDir = termuxWorkingDir,
                state = TermuxCommandState.PERMISSION_REQUIRED,
                stdout = "",
                stderr = "",
                exitCode = null,
                executionTimeMs = 0L,
                errorMessage = "Explicit user confirmation required before executing command.",
                risk = risk
            )
        }

        val env = inspectEnvironment(context)
        if (!env.isTermuxInstalled) {
            return@withContext TermuxCommandResult(
                executionId = executionId,
                command = command,
                workingDir = termuxWorkingDir,
                state = TermuxCommandState.RESULT_UNAVAILABLE,
                stdout = "",
                stderr = "",
                exitCode = null,
                executionTimeMs = System.currentTimeMillis() - startedAt,
                errorMessage = "Termux (com.termux) is not installed on this device.",
                risk = risk
            )
        }
        if (!env.isRunCommandPermissionGranted) {
            return@withContext TermuxCommandResult(
                executionId = executionId,
                command = command,
                workingDir = termuxWorkingDir,
                state = TermuxCommandState.PERMISSION_REQUIRED,
                stdout = "",
                stderr = "",
                exitCode = null,
                executionTimeMs = System.currentTimeMillis() - startedAt,
                errorMessage = "Missing com.termux.permission.RUN_COMMAND permission.",
                risk = risk
            )
        }

        val resultDeferred = CompletableDeferred<Bundle?>()
        pendingExecutions[executionId] = resultDeferred

        val dispatchedError = dispatchOfficialRunCommandService(
            context = context,
            executionId = executionId,
            shellCommand = command,
            workingDir = termuxWorkingDir.ifBlank { TERMUX_HOME_WORKDIR }
        )

        if (dispatchedError != null) {
            pendingExecutions.remove(executionId)
            return@withContext TermuxCommandResult(
                executionId = executionId,
                command = command,
                workingDir = termuxWorkingDir,
                state = TermuxCommandState.FAILED,
                stdout = "",
                stderr = dispatchedError,
                exitCode = null,
                executionTimeMs = System.currentTimeMillis() - startedAt,
                errorMessage = dispatchedError,
                risk = risk
            )
        }

        val bundle = withTimeoutOrNull(timeoutMs) {
            resultDeferred.await()
        }
        val elapsed = System.currentTimeMillis() - startedAt

        if (cancelledIds.remove(executionId)) {
            pendingExecutions.remove(executionId)
            return@withContext TermuxCommandResult(
                executionId = executionId,
                command = command,
                workingDir = termuxWorkingDir,
                state = TermuxCommandState.CANCELLED,
                stdout = "",
                stderr = "",
                exitCode = null,
                executionTimeMs = elapsed,
                errorMessage = "Command wait cancelled in NOVA Studio (note: Termux background shell process may still require closing inside Termux).",
                risk = risk
            )
        }

        if (bundle == null) {
            pendingExecutions.remove(executionId)
            return@withContext TermuxCommandResult(
                executionId = executionId,
                command = command,
                workingDir = termuxWorkingDir,
                state = TermuxCommandState.TIMED_OUT,
                stdout = "",
                stderr = "",
                exitCode = null,
                executionTimeMs = elapsed,
                errorMessage = "No Termux callback received within ${timeoutMs / 1000}s. Ensure allow-external-apps = true in ~/.termux/termux.properties.",
                risk = risk
            )
        }

        val rawStdout = DevSandboxGuard.redactSensitiveData(bundle.getString(PLUGIN_RESULT_STDOUT).orEmpty())
        val rawStderr = DevSandboxGuard.redactSensitiveData(bundle.getString(PLUGIN_RESULT_STDERR).orEmpty())
        val hasExitKey = bundle.containsKey(PLUGIN_RESULT_EXIT_CODE)
        val exitCode = if (hasExitKey) bundle.getInt(PLUGIN_RESULT_EXIT_CODE) else null
        val errCode = bundle.getInt(PLUGIN_RESULT_ERR, 0)
        val errMsg = bundle.getString(PLUGIN_RESULT_ERRMSG).orEmpty()

        val state = when {
            errCode != 0 && exitCode == null -> TermuxCommandState.FAILED
            exitCode == null -> TermuxCommandState.RESULT_UNAVAILABLE
            exitCode == 0 -> TermuxCommandState.COMPLETED
            else -> TermuxCommandState.FAILED
        }

        return@withContext TermuxCommandResult(
            executionId = executionId,
            command = command,
            workingDir = termuxWorkingDir,
            state = state,
            stdout = rawStdout,
            stderr = rawStderr,
            exitCode = exitCode,
            executionTimeMs = elapsed,
            errorMessage = errMsg.ifBlank { null },
            risk = risk
        )
    }

    private fun dispatchOfficialRunCommandService(
        context: Context,
        executionId: String,
        shellCommand: String,
        workingDir: String
    ): String? {
        return try {
            val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
                putExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, executionId)
            }
            val flags = PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                executionId.hashCode(),
                callbackIntent,
                flags
            )

            val serviceIntent = Intent(TERMUX_RUN_COMMAND_ACTION).apply {
                component = ComponentName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
                putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", shellCommand))
                putExtra(EXTRA_WORKDIR, workingDir)
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_SESSION_ACTION, "0")
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            null
        } catch (se: SecurityException) {
            "Termux rejected RUN_COMMAND IPC: ${se.message}. Check allow-external-apps = true in ~/.termux/termux.properties and grant permission."
        } catch (e: Exception) {
            "Failed dispatching Termux RunCommandService: ${e.message}"
        }
    }

    fun openTermuxApp(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    internal fun parseProbeOutput(stdout: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var currentKey: String? = null
        stdout.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("__") && line.endsWith("__") && line.length > 4) {
                currentKey = line.removePrefix("__").removeSuffix("__")
            } else if (currentKey != null && line.isNotBlank() && !map.containsKey(currentKey)) {
                map[currentKey!!] = line
            }
        }
        return map
    }

    private fun toolAvailabilityFrom(name: String, value: String?): ToolAvailability {
        val clean = value?.trim().orEmpty()
        val missing = clean.isBlank() ||
            clean.equals("MISSING", ignoreCase = true) ||
            clean.contains("command not found", ignoreCase = true) ||
            clean.contains("not found", ignoreCase = true)
        return ToolAvailability(
            name = name,
            isDetected = !missing,
            statusDetail = if (missing) "Missing in Termux" else clean,
            isVerifiedViaTermux = true
        )
    }
}
