package eu.kanade.tachiyomi.extension.installer

import android.content.Context
import android.content.pm.PackageManager
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.util.system.getUriSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import logcat.LogPriority
import rikka.shizuku.Shizuku
import tachiyomi.core.common.util.system.logcat
import java.io.BufferedReader
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ShizukuApkInstallBackendAdapter(
    private val context: Context,
) : ApkInstallBackendAdapter {
    override val backend = ApkInstallBackend.SHIZUKU

    override fun supports(kind: ApkExtensionKind): Boolean = kind == ApkExtensionKind.NOVEL_KOTLIN

    override fun install(request: ApkInstallRequest): Flow<InstallStep> = flow {
        emit(InstallStep.Installing)
        if (!Shizuku.pingBinder()) {
            logcat(LogPriority.ERROR) { "Shizuku is not ready for APK install" }
            emit(InstallStep.Error)
            return@flow
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            logcat(LogPriority.ERROR) { "Shizuku permission is required for APK install" }
            emit(InstallStep.Error)
            return@flow
        }

        var sessionId: String? = null
        try {
            val size = request.file?.length()?.takeIf { it > 0L }
                ?: context.getUriSize(request.uri)
                ?: throw IllegalStateException("Unable to determine APK size")
            val inputStream = request.file?.inputStream()
                ?: context.contentResolver.openInputStream(request.uri)
                ?: throw IllegalStateException("Unable to open APK input stream")
            inputStream.use {
                val createResult = exec("pm install-create -r -i ${context.packageName} -S $size")
                sessionId = SESSION_ID_REGEX.find(createResult.combinedOutput)?.value
                    ?: throw RuntimeException(
                        "Failed to create Shizuku install session: ${createResult.combinedOutput}",
                    )

                val writeResult = exec("pm install-write -S $size $sessionId base -", it)
                if (writeResult.resultCode != 0) {
                    throw RuntimeException("Failed to write APK to session $sessionId: ${writeResult.combinedOutput}")
                }

                val commitResult = exec("pm install-commit $sessionId")
                if (commitResult.resultCode != 0) {
                    throw RuntimeException(
                        "Failed to commit install session $sessionId: ${commitResult.combinedOutput}",
                    )
                }
            }
            emit(InstallStep.Installed)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed Shizuku APK install for ${request.packageName}" }
            sessionId?.let { runCatching { exec("pm install-abandon $it") } }
            emit(InstallStep.Error)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun uninstall(request: ApkUninstallRequest): ApkInstallResult = withContext(Dispatchers.IO) {
        if (!Shizuku.pingBinder()) return@withContext ApkInstallResult.Error("Shizuku is not ready")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            return@withContext ApkInstallResult.Error("Shizuku permission is required")
        }
        return@withContext runCatching {
            val result = exec("pm uninstall ${request.packageName}")
            if (result.resultCode == 0) ApkInstallResult.Installed else ApkInstallResult.Error(result.combinedOutput)
        }.getOrElse { ApkInstallResult.Error(it.message ?: it::class.simpleName.orEmpty(), it) }
    }

    override fun cancel(packageName: String) = Unit

    private fun exec(command: String, stdin: InputStream? = null): ShellResult {
        @Suppress("DEPRECATION")
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        var stdout = ""
        var stderr = ""
        val stdoutThread = thread(start = true) {
            stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
        }
        val stderrThread = thread(start = true) {
            stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
        }
        if (stdin != null) {
            process.outputStream.use { stdin.copyTo(it) }
        } else {
            process.outputStream.close()
        }
        val finished = process.waitFor(SHELL_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            return ShellResult(-1, stdout, stderr.ifBlank { "Command timed out: $command" })
        }
        stdoutThread.join()
        stderrThread.join()
        return ShellResult(process.exitValue(), stdout, stderr)
    }

    private data class ShellResult(val resultCode: Int, val out: String, val err: String) {
        val combinedOutput: String get() = listOf(out, err).filter { it.isNotBlank() }.joinToString("\n")
    }

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 14045
        const val SHELL_COMMAND_TIMEOUT_SECONDS = 120L
        val SESSION_ID_REGEX = Regex("""(?<=\[).+?(?=])""")
    }
}
