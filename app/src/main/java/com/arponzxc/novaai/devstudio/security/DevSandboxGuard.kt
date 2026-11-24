package com.arponzxc.novaai.devstudio.security

import com.arponzxc.novaai.devstudio.model.SandboxCommandRisk
import java.io.File
import java.nio.file.Files

object DevSandboxGuard {

    const val MAX_COMMAND_TIMEOUT_MS = 120_000L
    const val MAX_OUTPUT_CHARS = 32_768
    const val MAX_ZIP_ENTRIES = 1000
    const val MAX_ZIP_UNCOMPRESSED_BYTES = 50L * 1024L * 1024L
    const val MAX_BUILD_AUTO_FIX_RETRIES = 3

    private val BLOCKED_PATTERNS = listOf(
        Regex("(?i)(^|\\s|;|&&|\\|\\|)su(\\s|$)"),
        Regex("(?i)(^|\\s|;|&&|\\|\\|)sudo\\s+"),
        Regex("(?i)rm\\s+-[a-zA-Z]*[rf][a-zA-Z]*\\s+(/|/system|/data|/vendor|/sdcard|~|\\*)(\\s|$)"),
        Regex("(?i)(mkfs|dd\\s+if=|reboot|shutdown|poweroff|halt|chmod\\s+777\\s+/|chown\\s+-R\\s+.+\\s+/)"),
        Regex(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
        Regex("(?i)(^|\\s)mount(\\s|$)|(^|\\s)umount(\\s|$)|setenforce\\s+0")
    )

    private val CONFIRMATION_PATTERNS = listOf(
        Regex("(?i)(^|\\s|;|&&|\\|\\|)(rm|rmdir|unlink|shred)\\s+"),
        Regex("(?i)(^|\\s|;|&&|\\|\\|)(curl|wget|git\\s+clone|pkg\\s+install|apt\\s+install|npm\\s+install|pip\\s+install)"),
        Regex("(?i)(^|\\s|;|&&|\\|\\|)(adb\\s+install|pm\\s+install|am\\s+start)"),
        Regex("(?i)(^|\\s|;|&&|\\|\\|)(chmod|chown|mv\\s+/|cp\\s+.*\\s+/)")
    )

    private val WORKSPACE_BUILD_PATTERNS = listOf(
        Regex("(?i)(^|\\s)(\\./)?gradlew?\\s+.*"),
        Regex("(?i)(^|\\s)(python3?\\s+-m\\s+http\\.server|npx\\s+|node\\s+.*)"),
        Regex("(?i)(^|\\s)(mkdir|touch|cp|mv)\\s+.*")
    )

    private val SAFE_READ_PATTERNS = listOf(
        Regex("(?i)^(java\\s+-version|javac\\s+-version|gradle\\s+(-v|--version)|node\\s+(-v|--version)|npm\\s+(-v|--version)|python3?\\s+--version|git\\s+--version|which\\s+[a-z0-9_.-]+|pwd|ls(\\s+-la?)?|whoami|uname(\\s+-a)?|echo\\s+[^;|&`$>]+)$")
    )

    private val SECRET_REGEXES = listOf(
        Regex("AIza[0-9A-Za-z_\\-]{20,}") to "AIza***REDACTED***",
        Regex("sk-ant-[0-9A-Za-z_\\-]{16,}") to "sk-ant-***REDACTED***",
        Regex("sk-[0-9A-Za-z_\\-]{16,}") to "sk-***REDACTED***",
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{16,}") to "$1***REDACTED***",
        Regex("(?i)([?&](?:key|api_key|token|access_token)=)[^&\\s\"']+") to "$1***REDACTED***",
        Regex("(?i)((?:api[_-]?key|secret|password|passwd|token)\\s*[:=]\\s*[\"']?)[^\\s\"';,]{6,}") to "$1***REDACTED***"
    )

    /**
     * Resolves [relativePath] strictly within [workspaceRoot].
     * Rejects empty escape patterns, null bytes, "..", absolute escapes, and symlink escapes.
     */
    fun resolveSafeWorkspacePath(workspaceRoot: File, relativePath: String): File {
        val cleaned = relativePath.trim().replace('\\', '/')
        if (cleaned.contains('\u0000')) {
            throw SecurityException("Null byte detected in path.")
        }
        val normalizedRel = cleaned.trimStart('/')
        if (normalizedRel.split('/').any { it == ".." }) {
            throw SecurityException("Parent directory traversal ('..') is blocked.")
        }
        val rootCanonical = workspaceRoot.canonicalFile
        val target = if (normalizedRel.isBlank()) rootCanonical else File(rootCanonical, normalizedRel)
        val targetCanonical = target.canonicalFile

        val rootPath = rootCanonical.path
        val targetPath = targetCanonical.path
        val insideRoot = targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        if (!insideRoot) {
            throw SecurityException("Path escapes workspace boundary: $relativePath")
        }

        // Prevent symlink chains from pointing outside workspace
        if (Files.isSymbolicLink(target.toPath())) {
            val linkTarget = target.canonicalFile
            val linkPath = linkTarget.path
            if (linkPath != rootPath && !linkPath.startsWith(rootPath + File.separator)) {
                throw SecurityException("Symlink points outside workspace boundary.")
            }
        }
        return targetCanonical
    }

    /**
     * Guards ZipInputStream entry extraction against Zip Slip and malformed names.
     */
    fun validateZipEntryTarget(destDir: File, entryName: String): File {
        val cleaned = entryName.replace('\\', '/').trimStart('/')
        if (cleaned.isBlank() || cleaned.contains('\u0000')) {
            throw SecurityException("Invalid ZIP entry name: $entryName")
        }
        if (cleaned.split('/').any { it == ".." || it == "." }) {
            if (cleaned != ".") {
                val hasDotDot = cleaned.split('/').any { it == ".." }
                if (hasDotDot) {
                    throw SecurityException("Zip Slip traversal blocked: $entryName")
                }
            }
        }
        return resolveSafeWorkspacePath(destDir, cleaned)
    }

    fun classifyCommand(command: String): SandboxCommandRisk {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return SandboxCommandRisk.BLOCKED_DANGEROUS

        if (BLOCKED_PATTERNS.any { it.containsMatchIn(trimmed) }) {
            return SandboxCommandRisk.BLOCKED_DANGEROUS
        }
        if (trimmed.contains("../") || trimmed.contains("..\\") || trimmed.contains("`") || trimmed.contains("$(")) {
            return SandboxCommandRisk.REQUIRES_CONFIRMATION
        }
        if (CONFIRMATION_PATTERNS.any { it.containsMatchIn(trimmed) }) {
            return SandboxCommandRisk.REQUIRES_CONFIRMATION
        }
        if (SAFE_READ_PATTERNS.any { it.matches(trimmed) }) {
            return SandboxCommandRisk.SAFE_READ
        }
        if (WORKSPACE_BUILD_PATTERNS.any { it.containsMatchIn(trimmed) }) {
            return SandboxCommandRisk.SAFE_WORKSPACE_BUILD
        }
        // Any unfamiliar shell command requires explicit user confirmation before execution
        return SandboxCommandRisk.REQUIRES_CONFIRMATION
    }

    fun redactSensitiveData(input: String): String {
        var result = input
        for ((regex, replacement) in SECRET_REGEXES) {
            result = result.replace(regex, replacement)
        }
        return if (result.length > MAX_OUTPUT_CHARS) {
            result.take(MAX_OUTPUT_CHARS) + "\n... [output truncated at $MAX_OUTPUT_CHARS chars]"
        } else {
            result
        }
    }

    fun sanitizeProjectSlug(name: String): String {
        val cleaned = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return cleaned.ifBlank { "nova_project" }.take(32)
    }

    fun sanitizeJavaPackageSegment(raw: String): String {
        val cleaned = raw.lowercase().replace(Regex("[^a-z0-9]"), "")
        return when {
            cleaned.isBlank() -> "app"
            cleaned.first().isDigit() -> "a$cleaned"
            else -> cleaned
        }.take(24)
    }
}
