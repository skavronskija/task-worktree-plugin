package com.toxa.worktree.action;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.toxa.worktree.service.WorktreeService;
import git4idea.repo.GitRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Single removal engine for one or many worktrees: confirm, remove in the background, offer a
 * batched {@code --force} retry for the entries git refused, then report one summary notification.
 */
final class WorktreeRemover {

  private static final int MAX_LISTED_LINES = 15;

  private WorktreeRemover() {
  }

  /** A worktree row reduced to what removal needs: where git runs, and what to delete. */
  record RemovableWorktree(@NotNull Path path, @NotNull String branch, @NotNull Path removalWorkDir,
                           @Nullable GitRepository repoToUpdate) {
  }

  private record Failure(@NotNull RemovableWorktree target, @NotNull String detail, int exitCode, boolean forced) {
  }

  private record RemovalOutcome(@NotNull List<RemovableWorktree> removed, @NotNull List<Failure> forceable,
                                @NotNull List<Failure> hardFailed) {
  }

  static void confirmAndRemove(@NotNull Project project, @NotNull List<RemovableWorktree> targets) {
    if (targets.isEmpty()) {
      return;
    }
    int n = targets.size();
    String title = n == 1 ? "Remove Worktree" : "Remove Worktrees";
    String message = n == 1
        ? "Remove worktree at " + targets.get(0).path() + "?\n\nThe branch '" + targets.get(0).branch()
              + "' will be preserved. This deletes the worktree directory."
        : "Remove " + n + " worktrees?\n\n" + cappedList(targets, t -> "  " + describe(t))
              + "\n\nThe branches will be preserved. This deletes the worktree directories.";
    int answer = Messages.showYesNoDialog(project, message, title, Messages.getQuestionIcon());
    if (answer != Messages.YES) {
      return;
    }
    ProgressManager.getInstance().run(
        new Task.Backgroundable(project, n == 1 ? "Removing worktree" : "Removing " + n + " worktrees", true) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            RemovalOutcome outcome = removeAll(project, targets, false, indicator);
            refreshRepos(outcome.removed());
            ApplicationManager.getApplication().invokeLater(
                () -> afterFirstPass(project, outcome.removed(), outcome.forceable(), outcome.hardFailed()),
                project.getDisposed()
            );
          }
        });
  }

  private static void afterFirstPass(@NotNull Project project,
                                     @NotNull List<RemovableWorktree> removed,
                                     @NotNull List<Failure> forceable,
                                     @NotNull List<Failure> hardFailed) {
    if (forceable.isEmpty()) {
      report(project, removed, List.of(), hardFailed);
      return;
    }
    int k = forceable.size();
    String title = k == 1 ? "Force Remove Worktree" : "Force Remove Worktrees";
    String message = k == 1
        ? "Worktree removal failed:\n" + forceable.get(0).detail().trim() + "\n\nForce removal with --force?"
        : k + " worktrees could not be removed:\n\n"
              + cappedList(forceable, f -> "  " + describe(f.target()) + "  " + firstLine(f.detail()))
              + "\n\nForce removal with --force?";
    int answer = Messages.showYesNoDialog(project, message, title, Messages.getWarningIcon());
    if (answer != Messages.YES) {
      // Declining the force retry is a choice, not a failure — only what git already did is reported.
      report(project, removed, List.of(), hardFailed);
      return;
    }
    List<RemovableWorktree> forceTargets = forceable.stream().map(Failure::target).toList();
    ProgressManager.getInstance().run(
        new Task.Backgroundable(project,
                                k == 1 ? "Force-removing worktree" : "Force-removing " + k + " worktrees", true) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            RemovalOutcome forcedOutcome = removeAll(project, forceTargets, true, indicator);
            refreshRepos(forcedOutcome.removed());
            List<Failure> stillFailed = new ArrayList<>(hardFailed);
            stillFailed.addAll(forcedOutcome.hardFailed());
            ApplicationManager.getApplication().invokeLater(
                () -> report(project, removed, forcedOutcome.removed(), stillFailed),
                project.getDisposed()
            );
          }
        });
  }

  private static void report(@NotNull Project project,
                             @NotNull List<RemovableWorktree> removed,
                             @NotNull List<RemovableWorktree> forced,
                             @NotNull List<Failure> failed) {
    int removedCount = removed.size();
    int forcedCount = forced.size();
    String successClause = buildSuccessClause(removed, forced, removedCount, forcedCount);

    if (failed.isEmpty()) {
      if (successClause != null) {
        WorktreeNotifications.notifyInfo(project, successClause);
      }
      return;
    }

    if (successClause == null && failed.size() == 1) {
      Failure only = failed.get(0);
      String command = only.forced() ? "git worktree remove --force" : "git worktree remove";
      WorktreeNotifications.notifyError(
          project, command + " failed (exit " + only.exitCode() + "): " + only.detail().trim());
      return;
    }

    StringBuilder message = new StringBuilder();
    if (successClause != null) {
      message.append(successClause).append('\n');
    }
    message.append("Failed to remove ").append(failed.size()).append(":\n")
           .append(cappedList(failed, f -> "  " + describe(f.target()) + "  " + firstLine(f.detail())));
    WorktreeNotifications.notifyError(project, message.toString());
  }

  @Nullable
  private static String buildSuccessClause(@NotNull List<RemovableWorktree> removed,
                                           @NotNull List<RemovableWorktree> forced,
                                           int removedCount, int forcedCount) {
    int total = removedCount + forcedCount;
    if (total == 0) {
      return null;
    }
    if (removedCount == 1 && forcedCount == 0) {
      return "Removed worktree " + fileName(removed.get(0).path());
    }
    if (forcedCount == 1 && removedCount == 0) {
      return "Force-removed worktree " + fileName(forced.get(0).path());
    }
    return "Removed " + total + " worktrees" + (forcedCount > 0 ? " (" + forcedCount + " forced)." : ".");
  }

  @NotNull
  private static RemovalOutcome removeAll(@NotNull Project project, @NotNull List<RemovableWorktree> targets,
                                          boolean force, @NotNull ProgressIndicator indicator) {
    int n = targets.size();
    List<RemovableWorktree> removed = new ArrayList<>();
    List<Failure> forceable = new ArrayList<>();
    List<Failure> hardFailed = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      RemovableWorktree target = targets.get(i);
      indicator.checkCanceled();
      indicator.setText(describe(target));
      indicator.setFraction((double) i / n);
      WorktreeService.Result result =
          WorktreeService.removeWorktree(project, target.removalWorkDir(), null, target.path(), force);
      if (result.success()) {
        removed.add(target);
        continue;
      }
      String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
      Failure failure = new Failure(target, detail, result.exitCode(), force);
      if (!force && isForceable(detail)) {
        forceable.add(failure);
      } else {
        hardFailed.add(failure);
      }
    }
    return new RemovalOutcome(removed, forceable, hardFailed);
  }

  private static void refreshRepos(@NotNull List<RemovableWorktree> removed) {
    Set<GitRepository> repos = new LinkedHashSet<>();
    for (RemovableWorktree t : removed) {
      if (t.repoToUpdate() != null) {
        repos.add(t.repoToUpdate());
      }
    }
    repos.forEach(GitRepository::update);
  }

  private static boolean isForceable(@NotNull String detail) {
    String lower = detail.toLowerCase();
    return lower.contains("use --force") || lower.contains("contains modified") || lower.contains("locked");
  }

  @NotNull
  private static String describe(@NotNull RemovableWorktree t) {
    String name = fileName(t.path());
    return t.branch().isBlank() ? name : name + "  (" + t.branch() + ")";
  }

  @NotNull
  private static String fileName(@NotNull Path path) {
    return path.getFileName() == null ? path.toString() : path.getFileName().toString();
  }

  @NotNull
  private static String firstLine(@NotNull String detail) {
    for (String line : detail.split("\n")) {
      String trimmed = line.strip();
      if (!trimmed.isEmpty()) {
        return trimmed;
      }
    }
    return detail.strip();
  }

  @NotNull
  private static <T> String cappedList(@NotNull List<T> items, @NotNull Function<T, String> lineFor) {
    int max = Math.min(items.size(), MAX_LISTED_LINES);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < max; i++) {
      if (i > 0) {
        sb.append('\n');
      }
      sb.append(lineFor.apply(items.get(i)));
    }
    if (items.size() > MAX_LISTED_LINES) {
      sb.append("\n  …and ").append(items.size() - MAX_LISTED_LINES).append(" more");
    }
    return sb.toString();
  }
}
