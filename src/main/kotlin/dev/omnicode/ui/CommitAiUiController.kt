package dev.omnicode.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.omnicode.persistence.OmniCodeLocalStore
import dev.omnicode.persistence.UsageRecord
import dev.omnicode.provider.ProviderException
import dev.omnicode.service.CommitAiErrorCode
import dev.omnicode.service.CommitAiException
import dev.omnicode.service.CommitAiResult
import dev.omnicode.service.CommitAiService
import dev.omnicode.service.estimateUsageCost
import dev.omnicode.settings.OmniCodePlatformSettingsService
import dev.omnicode.settings.OmniCodeSettingsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal class CommitAiUiController(
    private val project: Project,
    private val onBusyChanged: (Boolean) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val generate: suspend (Project) -> CommitAiResult = CommitAiService()::generate,
    private val localStore: OmniCodeLocalStore = OmniCodeLocalStore.default(),
) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var disposed = false
    private var generation = 0L
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (disposed || isRunning || project.isDisposed) return
        val requestGeneration = ++generation
        val providerId = OmniCodeSettingsService.getInstance().snapshot().providerId
        onBusyChanged(true)
        onStatusChanged("正在生成 Commit 信息…")

        job = scope.launch {
            val outcome = try {
                Result.success(generate(project))
            } catch (cancelled: CancellationException) {
                return@launch
            } catch (error: Throwable) {
                Result.failure(error)
            }
            outcome.getOrNull()?.let { result ->
                scope.launch { recordUsage(providerId, result) }
            }
            dispatchEdt {
                if (disposed || requestGeneration != generation || project.isDisposed) return@dispatchEdt
                job = null
                onBusyChanged(false)
                outcome.fold(
                    onSuccess = { result ->
                        if (CommitMessageDialog(project, result).showAndGet()) {
                            onStatusChanged("Commit 信息已复制到剪贴板")
                        } else {
                            onStatusChanged("")
                        }
                    },
                    onFailure = { error ->
                        val message = friendlyError(error)
                        onStatusChanged(message.take(80))
                        Messages.showErrorDialog(project, message, "Commit AI")
                    },
                )
            }
        }
    }

    override fun dispose() {
        disposed = true
        generation++
        job?.cancel()
        job = null
        scope.cancel()
    }

    private suspend fun recordUsage(providerId: String, result: CommitAiResult) {
        if (result.usage.totalTokens <= 0) return
        try {
            val estimatedCost = estimateUsageCost(
                providerId = providerId,
                model = result.model,
                usage = result.usage,
                pricing = OmniCodePlatformSettingsService.getInstance().snapshot().pricing,
            )
            withContext(Dispatchers.IO) {
                localStore.recordUsage(
                    UsageRecord(
                        runId = "commit-ai-${UUID.randomUUID()}",
                        providerId = providerId,
                        model = result.model,
                        inputTokens = result.usage.inputTokens,
                        outputTokens = result.usage.outputTokens,
                        estimatedCostUsd = estimatedCost,
                        projectId = projectFingerprint(project.basePath.orEmpty()),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Usage persistence is best-effort and must never hide a generated message.
        }
    }

    private fun dispatchEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action()
        else application.invokeLater(action, ModalityState.nonModal())
    }
}

internal fun friendlyError(error: Throwable): String = when (error) {
    is CommitAiException -> when (error.code) {
        CommitAiErrorCode.DISABLED -> "Commit AI 尚未启用，请在 OmniCode 平台设置中开启。"
        CommitAiErrorCode.INVALID_PROJECT -> "当前项目没有可用的本地工作目录。"
        CommitAiErrorCode.GIT_NOT_FOUND -> "未找到 Git 可执行文件。"
        CommitAiErrorCode.GIT_FAILED -> error.message.safeUiMessage("无法读取暂存区改动。")
        CommitAiErrorCode.GIT_TIMEOUT -> "读取暂存区超时，请缩小暂存的改动范围后重试。"
        CommitAiErrorCode.DIFF_TOO_LARGE -> error.message.safeUiMessage("暂存区改动过大，Commit AI 无法处理。")
        CommitAiErrorCode.NO_STAGED_CHANGES -> "暂存区没有改动，请先 Stage 文件再生成 Commit 信息。"
        CommitAiErrorCode.EMPTY_MODEL_RESPONSE -> "模型返回了空的 Commit 信息。"
    }
    is ProviderException -> when (error.statusCode) {
        401, 403 -> "当前供应商拒绝了已保存的凭据，请检查 API Key。"
        429 -> "当前供应商触发限流，请稍后重试。"
        else -> error.message.safeUiMessage("当前供应商无法生成 Commit 信息。")
    }
    else -> "无法生成 Commit 信息。"
}

private fun String?.safeUiMessage(fallback: String): String = this
    ?.lineSequence()
    ?.firstOrNull()
    ?.replace(Regex("[\\p{Cntrl}&&[^\\t]]"), " ")
    ?.take(280)
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: fallback

private fun projectFingerprint(basePath: String): String {
    if (basePath.isBlank()) return ""
    return MessageDigest.getInstance("SHA-256")
        .digest(basePath.toByteArray(StandardCharsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
