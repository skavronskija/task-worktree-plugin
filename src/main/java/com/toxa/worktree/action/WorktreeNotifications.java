package com.toxa.worktree.action;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

final class WorktreeNotifications {

  private static final String NOTIFICATION_GROUP = "Task Worktree";

  private WorktreeNotifications() {
  }

  static void notifyError(@NotNull Project project, @NotNull String message) {
    Notifications.Bus.notify(
        NotificationGroupManager.getInstance()
                                .getNotificationGroup(NOTIFICATION_GROUP)
                                .createNotification(message, NotificationType.ERROR),
        project
    );
  }

  static void notifyInfo(@NotNull Project project, @NotNull String message) {
    Notifications.Bus.notify(
        NotificationGroupManager.getInstance()
                                .getNotificationGroup(NOTIFICATION_GROUP)
                                .createNotification(message, NotificationType.INFORMATION),
        project
    );
  }
}
