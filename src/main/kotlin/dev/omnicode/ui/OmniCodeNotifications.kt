package dev.omnicode.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Native IDE notification used when a task finishes while the user is in another editor/window. */
object OmniCodeNotifications {
    private const val GROUP_ID = "OmniCode Notifications"

    fun taskFinished(project: Project, title: String, detail: String, failed: Boolean) {
        val safeTitle = title.take(120).ifBlank { "OmniCode" }
        val safeDetail = detail.replace(Regex("[\\r\\n]+"), " ").take(500).ifBlank {
            if (failed) "任务未完成，可返回对话查看并从恢复点继续。" else "任务已完成。"
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                safeTitle,
                safeDetail,
                if (failed) NotificationType.WARNING else NotificationType.INFORMATION,
            )
            .notify(project)
    }
}
