package com.toxa.worktree.service;

import com.intellij.tasks.Task;
import com.intellij.tasks.TaskType;
import com.toxa.worktree.settings.WorktreeSettings;
import git4idea.validators.GitRefNameValidator;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves branch and worktree folder names from user-configured patterns
 * (Settings | Tools | Task Worktree). Supported variables:
 * <ul>
 *   <li>{@code ${id}} — task presentable id, e.g. {@code TP-12345}</li>
 *   <li>{@code ${number}} — task number</li>
 *   <li>{@code ${type}} — task type, lower-cased (e.g. {@code bug}, {@code feature})</li>
 *   <li>{@code ${summary}} — task summary</li>
 *   <li>{@code ${project}} — current project / repository name</li>
 * </ul>
 * A blank pattern falls back to the task's presentable id.
 */
public final class WorktreeNaming {

  private WorktreeNaming() {
  }

  @NotNull
  public static String suggestBranchName(@NotNull Task task, @NotNull String projectName) {
    String rendered = render(WorktreeSettings.getInstance().getBranchNamePattern(), task, projectName);
    if (rendered.isBlank()) {
      rendered = task.getPresentableId();
    }
    return GitRefNameValidator.getInstance().cleanUpBranchName(rendered);
  }

  @NotNull
  public static String suggestFolderName(@NotNull Task task, @NotNull String projectName) {
    String rendered = render(WorktreeSettings.getInstance().getWorktreeNamePattern(), task, projectName);
    if (rendered.isBlank()) {
      rendered = task.getPresentableId();
    }
    return rendered.trim();
  }

  @NotNull
  private static String render(@NotNull String pattern,
                               @NotNull Task task,
                               @NotNull String projectName) {
    if (pattern.isBlank()) {
      return "";
    }
    return pattern
        .replace("${id}", nullToEmpty(task.getPresentableId()))
        .replace("${number}", nullToEmpty(task.getNumber()))
        .replace("${type}", taskType(task))
        .replace("${summary}", sanitizeSummary(task.getSummary()))
        .replace("${project}", projectName);
  }

  @NotNull
  private static String taskType(@NotNull Task task) {
    TaskType type = task.getType();
    if (type == null) {
      return "";
    }
    String resolved = type.name().toLowerCase(Locale.ROOT);
    return WorktreeSettings.getInstance().getTaskTypeMappings().getOrDefault(resolved, resolved);
  }

  @NotNull
  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * Makes a task summary safe to embed in a branch or folder name: spaces become
   * underscores, every other non-alphanumeric character (except {@code _} and {@code -})
   * is dropped, and runs of underscores are collapsed.
   */
  @NotNull
  private static String sanitizeSummary(String summary) {
    if (summary == null || summary.isBlank()) {
      return "";
    }
    String sanitized = summary.trim()
        .replaceAll("\\s+", "_")
        .replaceAll("[^a-zA-Z0-9_-]", "")
        .replaceAll("_{2,}", "_");
    return sanitized.replaceAll("^_+|_+$", "");
  }
}
