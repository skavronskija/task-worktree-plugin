package ie.distilled.worktree.action;

import com.intellij.icons.AllIcons;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListSeparator;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.tasks.Task;
import com.intellij.tasks.TaskManager;
import com.intellij.tasks.TaskRepository;
import ie.distilled.worktree.service.WorktreeNaming;
import ie.distilled.worktree.service.WorktreeService;
import ie.distilled.worktree.settings.WorktreeSettings;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OpenTaskInWorktreeAction extends AnAction {

  private static final Logger LOG = Logger.getInstance(OpenTaskInWorktreeAction.class);
  private static final String NOTIFICATION_GROUP = "Task Worktree";
  private static final String ACTION_OPEN = "Open";
  private static final String ACTION_REMOVE = "Remove";
  private static final int TASKS_PER_REPO = 50;

  sealed interface PickerEntry {
  }

  record WorktreeEntry(@NotNull Path path, @NotNull String branch, boolean main) implements PickerEntry {
  }

  record CustomEntry() implements PickerEntry {
  }

  record TaskEntry(@NotNull Task task, @Nullable String repoLabel) implements PickerEntry {
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    boolean enabled = project != null
                      && !GitRepositoryManager.getInstance(project).getRepositories().isEmpty();
    e.getPresentation().setEnabledAndVisible(enabled);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      return;
    }

    GitRepository gitRepo = chooseGitRepository(project);
    if (gitRepo == null) {
      return;
    }

    ProgressManager.getInstance().run(
        new com.intellij.openapi.progress.Task.Backgroundable(project, "Loading tasks", true) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            List<WorktreeService.WorktreeInfo> worktrees = WorktreeService.listWorktrees(project, gitRepo);
            List<TaskEntry> taskEntries = fetchAllTasks(project, indicator);
            ApplicationManager.getApplication().invokeLater(
                () -> showPicker(project, gitRepo, worktrees, taskEntries),
                project.getDisposed()
            );
          }
        });
  }

  @NotNull
  private List<TaskEntry> fetchAllTasks(@NotNull Project project, @NotNull ProgressIndicator indicator) {
    TaskRepository[] repos = TaskManager.getManager(project).getAllRepositories();
    boolean multipleRepos = repos.length > 1;
    List<TaskEntry> result = new ArrayList<>();
    for (TaskRepository repo : repos) {
      indicator.checkCanceled();
      try {
        Task[] tasks = repo.getIssues(null, 0, TASKS_PER_REPO, false, indicator);
        String label = multipleRepos ? repo.getPresentableName() : null;
        for (Task task : tasks) {
          result.add(new TaskEntry(task, label));
        }
      } catch (Exception ex) {
        LOG.warn("Failed to load tasks from " + repo.getPresentableName(), ex);
        notifyError(project, "Failed to load tasks from " + repo.getPresentableName() + ": " + ex.getMessage());
      }
    }
    return result;
  }

  private void showPicker(@NotNull Project project,
                          @NotNull GitRepository gitRepo,
                          @NotNull List<WorktreeService.WorktreeInfo> worktrees,
                          @NotNull List<TaskEntry> taskEntries) {
    List<PickerEntry> entries = new ArrayList<>();
    for (WorktreeService.WorktreeInfo w : worktrees) {
      entries.add(new WorktreeEntry(w.path(), w.branch(), w.main()));
    }
    int tasksSectionStart = entries.size();
    entries.add(new CustomEntry());
    entries.addAll(taskEntries);

    BaseListPopupStep<PickerEntry> step = new BaseListPopupStep<>("Select Task or Worktree", entries) {
      @Override
      public boolean isSpeedSearchEnabled() {
        return true;
      }

      @Override
      public boolean hasSubstep(PickerEntry value) {
        return value instanceof WorktreeEntry;
      }

      @Override
      public @NotNull String getTextFor(PickerEntry value) {
        if (value instanceof WorktreeEntry w) {
          String name = w.path().getFileName() == null ? w.path().toString() : w.path().getFileName().toString();
          String base = w.branch().isEmpty() ? name : name + "  (" + w.branch() + ")";
          return w.main() ? base + "  [main]" : base;
        }
        if (value instanceof CustomEntry) {
          return "Custom worktree…";
        }
        if (value instanceof TaskEntry t) {
          String base = t.task().getPresentableId() + "  " + t.task().getSummary();
          return t.repoLabel() == null ? base : base + "  [" + t.repoLabel() + "]";
        }
        return "";
      }

      @Override
      public Icon getIconFor(PickerEntry value) {
        if (value instanceof WorktreeEntry) {
          return AllIcons.Nodes.Folder;
        }
        if (value instanceof CustomEntry) {
          return AllIcons.General.Add;
        }
        if (value instanceof TaskEntry t) {
          return t.task().getIcon();
        }
        return null;
      }

      @Override
      public ListSeparator getSeparatorAbove(PickerEntry value) {
        int idx = entries.indexOf(value);
        if (idx == 0 && value instanceof WorktreeEntry) {
          return new ListSeparator("Existing Worktrees");
        }
        if (idx == tasksSectionStart) {
          return new ListSeparator("Tasks");
        }
        return null;
      }

      @Override
      public PopupStep<?> onChosen(PickerEntry value, boolean finalChoice) {
        if (value instanceof WorktreeEntry w) {
          return worktreeActionStep(project, gitRepo, w);
        }
        if (value instanceof CustomEntry) {
          return doFinalStep(() -> createCustomWorktree(project, gitRepo));
        }
        if (value instanceof TaskEntry t) {
          return doFinalStep(() -> createWorktreeFor(project, gitRepo, t.task()));
        }
        return FINAL_CHOICE;
      }
    };

    JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project);
  }

  private PopupStep<?> worktreeActionStep(@NotNull Project project,
                                          @NotNull GitRepository gitRepo,
                                          @NotNull WorktreeEntry worktree) {
    String title = worktree.path().getFileName() == null
                   ? worktree.path().toString()
                   : worktree.path().getFileName().toString();
    List<String> actions = worktree.main() ? List.of(ACTION_OPEN) : List.of(ACTION_OPEN, ACTION_REMOVE);
    return new BaseListPopupStep<>(title, actions) {
      @Override
      public Icon getIconFor(String value) {
        if (ACTION_OPEN.equals(value)) {
          return AllIcons.Actions.MenuOpen;
        }
        if (ACTION_REMOVE.equals(value)) {
          return AllIcons.Actions.GC;
        }
        return null;
      }

      @Override
      public PopupStep<?> onChosen(String selectedValue, boolean finalChoice) {
        if (ACTION_OPEN.equals(selectedValue)) {
          return doFinalStep(() -> openWorktree(project, worktree.path()));
        }
        if (ACTION_REMOVE.equals(selectedValue)) {
          return doFinalStep(() -> confirmAndRemoveWorktree(project, gitRepo, worktree));
        }
        return FINAL_CHOICE;
      }
    };
  }

  private void openWorktree(@NotNull Project project, @NotNull Path worktreePath) {
    if (!Files.isDirectory(worktreePath)) {
      notifyError(project, "Worktree directory no longer exists: " + worktreePath);
      return;
    }
    ProjectUtil.openOrImport(worktreePath, project, false);
  }

  private void confirmAndRemoveWorktree(@NotNull Project project,
                                        @NotNull GitRepository gitRepo,
                                        @NotNull WorktreeEntry worktree) {
    int answer = Messages.showYesNoDialog(
        project,
        "Remove worktree at " + worktree.path() + "?\n\nThe branch '" + worktree.branch()
            + "' will be preserved. This deletes the worktree directory.",
        "Remove Worktree",
        Messages.getQuestionIcon()
    );
    if (answer != Messages.YES) {
      return;
    }
    ProgressManager.getInstance().run(
        new com.intellij.openapi.progress.Task.Backgroundable(project, "Removing worktree", true) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            WorktreeService.Result result = WorktreeService.removeWorktree(project, gitRepo, worktree.path(), false);
            if (!result.success()) {
              String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
              boolean offerForce = detail.toLowerCase().contains("use --force")
                                   || detail.toLowerCase().contains("contains modified")
                                   || detail.toLowerCase().contains("locked");
              if (offerForce) {
                ApplicationManager.getApplication().invokeLater(
                    () -> retryRemoveWithForce(project, gitRepo, worktree, detail),
                    project.getDisposed()
                );
              } else {
                notifyError(project, "git worktree remove failed (exit " + result.exitCode() + "): " + detail.trim());
              }
              return;
            }
            notifyInfo(project, "Removed worktree " + worktree.path().getFileName());
          }
        });
  }

  private void retryRemoveWithForce(@NotNull Project project,
                                    @NotNull GitRepository gitRepo,
                                    @NotNull WorktreeEntry worktree,
                                    @NotNull String reason) {
    int answer = Messages.showYesNoDialog(
        project,
        "Worktree removal failed:\n" + reason.trim() + "\n\nForce removal with --force?",
        "Force Remove Worktree",
        Messages.getWarningIcon()
    );
    if (answer != Messages.YES) {
      return;
    }
    ProgressManager.getInstance().run(
        new com.intellij.openapi.progress.Task.Backgroundable(project, "Force-removing worktree", true) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            WorktreeService.Result result = WorktreeService.removeWorktree(project, gitRepo, worktree.path(), true);
            if (!result.success()) {
              String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
              notifyError(project, "git worktree remove --force failed (exit " + result.exitCode() + "): " + detail.trim());
              return;
            }
            notifyInfo(project, "Force-removed worktree " + worktree.path().getFileName());
          }
        });
  }

  private void createWorktreeFor(@NotNull Project project,
                                 @NotNull GitRepository gitRepo,
                                 @NotNull Task task) {
    String repoName = Paths.get(gitRepo.getRoot().getPath()).getFileName().toString();
    promptAndCreateWorktree(
        project,
        gitRepo,
        "Create Worktree for " + task.getPresentableId() + "  " + task.getSummary(),
        WorktreeNaming.suggestBranchName(project, task),
        repoName + "-" + task.getPresentableId()
    );
  }

  private void createCustomWorktree(@NotNull Project project, @NotNull GitRepository gitRepo) {
    promptAndCreateWorktree(project, gitRepo, "Create Custom Worktree", "", "");
  }

  private void promptAndCreateWorktree(@NotNull Project project,
                                       @NotNull GitRepository gitRepo,
                                       @NotNull String windowTitle,
                                       @NotNull String defaultBranch,
                                       @NotNull String defaultFolder) {
    VirtualFile root = gitRepo.getRoot();
    Path repoPath = Paths.get(root.getPath());
    Path parent = repoPath.getParent();
    if (parent == null) {
      notifyError(project, "Repository root has no parent directory: " + repoPath);
      return;
    }
    Path worktreesParent = resolveWorktreesParent(parent);

    List<String> localBranches = gitRepo.getBranches().getLocalBranches().stream()
                                        .map(git4idea.GitLocalBranch::getName)
                                        .sorted()
                                        .toList();

    CreateWorktreeDialog dialog = new CreateWorktreeDialog(
        project, windowTitle, defaultBranch, defaultFolder, worktreesParent, localBranches);
    if (!dialog.showAndGet()) {
      return;
    }
    String branchName = dialog.getBranch();
    String folderName = dialog.getFolder();
    Path worktreePath = worktreesParent.resolve(folderName);

    if (Files.isDirectory(worktreePath)) {
      ProjectUtil.openOrImport(worktreePath, project, false);
      return;
    }

    ProgressManager.getInstance().run(
        new com.intellij.openapi.progress.Task.Backgroundable(project, "Creating worktree " + branchName, true) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            WorktreeService.Result result;
            try {
              result = WorktreeService.createWorktree(project, gitRepo, worktreePath, branchName, null);
            } catch (IllegalStateException pre) {
              notifyError(project, pre.getMessage());
              return;
            } catch (Throwable t) {
              LOG.warn("Unexpected error creating worktree", t);
              notifyError(project, "Failed to create worktree: " + t.getMessage());
              return;
            }
            if (!result.success()) {
              String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
              notifyError(project, "git worktree add failed (exit " + result.exitCode() + "): " + detail.trim());
              return;
            }
            ApplicationManager.getApplication().invokeLater(
                () -> ProjectUtil.openOrImport(worktreePath, project, false),
                project.getDisposed()
            );
          }
        });
  }

  @NotNull
  private static Path resolveWorktreesParent(@NotNull Path repoParent) {
    String configured = WorktreeSettings.getInstance().getBaseDirectory().trim();
    if (configured.isEmpty()) {
      return repoParent.resolve("worktrees");
    }
    Path configuredPath = Paths.get(configured);
    return configuredPath.isAbsolute() ? configuredPath : repoParent.resolve(configuredPath);
  }

  @Nullable
  @SuppressWarnings("deprecation")
  private GitRepository chooseGitRepository(@NotNull Project project) {
    List<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
    if (repos.isEmpty()) {
      notifyError(project, "No git repository found in this project.");
      return null;
    }
    if (repos.size() == 1) {
      return repos.get(0);
    }
    String[] options = repos.stream().map(r -> r.getRoot().getPath()).toArray(String[]::new);
    int idx = Messages.showChooseDialog(
        project,
        "Choose the git repository in which to create the worktree:",
        "Select Git Repository",
        null,
        options,
        options[0]
    );
    return idx < 0 ? null : repos.get(idx);
  }

  private void notifyError(@NotNull Project project, @NotNull String message) {
    Notifications.Bus.notify(
        NotificationGroupManager.getInstance()
                                .getNotificationGroup(NOTIFICATION_GROUP)
                                .createNotification(message, NotificationType.ERROR),
        project
    );
  }

  private void notifyInfo(@NotNull Project project, @NotNull String message) {
    Notifications.Bus.notify(
        NotificationGroupManager.getInstance()
                                .getNotificationGroup(NOTIFICATION_GROUP)
                                .createNotification(message, NotificationType.INFORMATION),
        project
    );
  }
}
