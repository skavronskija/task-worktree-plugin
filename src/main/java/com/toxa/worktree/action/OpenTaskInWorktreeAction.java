package com.toxa.worktree.action;

import com.intellij.icons.AllIcons;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.ListSeparator;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.tasks.Task;
import com.intellij.tasks.TaskManager;
import com.intellij.tasks.TaskRepository;
import com.intellij.ui.ActiveComponent;
import com.intellij.ui.CaptionPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.popup.list.ListPopupImpl;
import com.intellij.ui.popup.list.PopupListElementRenderer;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.toxa.worktree.service.PrStatusService;
import com.toxa.worktree.service.PrStatusSupport;
import com.toxa.worktree.service.PrStatusSupport.PrStatus;
import com.toxa.worktree.service.WorktreeNaming;
import com.toxa.worktree.service.WorktreeService;
import com.toxa.worktree.settings.WorktreeSettings;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import java.awt.BorderLayout;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
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

  record ExternalWorktreeEntry(@NotNull Path path, @NotNull String branch,
                               @Nullable Path ownerRepoRoot) implements PickerEntry {
  }

  record CustomEntry() implements PickerEntry {
  }

  record TaskEntry(@NotNull Task task, @Nullable String repoLabel) implements PickerEntry {
  }

  /** Everything loaded up front for one picker session, so toggling "show all" rebuilds instantly. */
  private record PickerData(@NotNull Project project,
                            @NotNull GitRepository gitRepo,
                            @NotNull List<WorktreeService.WorktreeInfo> repoWorktrees,
                            @NotNull List<WorktreeService.ExternalWorktree> externalWorktrees,
                            @NotNull List<TaskEntry> taskEntries,
                            @NotNull Map<Path, PrStatus> prByPath,
                            @NotNull AtomicReference<ListPopup> currentPopup) {
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

    chooseGitRepository(project, gitRepo -> loadTasksAndShowPicker(project, gitRepo));
  }

  private void loadTasksAndShowPicker(@NotNull Project project, @NotNull GitRepository gitRepo) {
    ProgressManager.getInstance().run(
        new com.intellij.openapi.progress.Task.Backgroundable(project, "Loading tasks", true) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            List<WorktreeService.WorktreeInfo> worktrees = WorktreeService.listWorktrees(project, gitRepo);
            List<WorktreeService.ExternalWorktree> external = scanExternal(gitRepo, worktrees);
            List<TaskEntry> taskEntries = fetchAllTasks(project, indicator);
            PickerData data = new PickerData(project, gitRepo, worktrees, external, taskEntries,
                                             new ConcurrentHashMap<>(), new AtomicReference<>());
            ApplicationManager.getApplication().invokeLater(
                () -> {
                  showPicker(data, false, null);
                  loadPrStatuses(data);
                },
                project.getDisposed()
            );
          }
        });
  }

  @NotNull
  private static List<WorktreeService.ExternalWorktree> scanExternal(
      @NotNull GitRepository gitRepo,
      @NotNull List<WorktreeService.WorktreeInfo> repoWorktrees) {
    Path repoRoot = Paths.get(gitRepo.getRoot().getPath()).toAbsolutePath().normalize();
    Path repoParent = repoRoot.getParent();
    if (repoParent == null) {
      return List.of();
    }
    Set<Path> exclude = new HashSet<>();
    exclude.add(repoRoot);
    for (WorktreeService.WorktreeInfo w : repoWorktrees) {
      exclude.add(w.path());
    }
    return WorktreeService.scanWorktreesDirectory(resolveWorktreesParent(repoParent), exclude);
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

  private void showPicker(@NotNull PickerData data, boolean showAll, @Nullable Point location) {
    Project project = data.project();
    GitRepository gitRepo = data.gitRepo();
    Path repoRoot = Paths.get(gitRepo.getRoot().getPath()).toAbsolutePath().normalize();

    List<PickerEntry> entries = new ArrayList<>();
    for (WorktreeService.WorktreeInfo w : data.repoWorktrees()) {
      entries.add(new WorktreeEntry(w.path(), w.branch(), w.main()));
    }
    if (showAll) {
      for (WorktreeService.ExternalWorktree w : data.externalWorktrees()) {
        entries.add(new ExternalWorktreeEntry(w.path(), w.branch(), w.ownerRepoRoot()));
      }
    }
    int tasksSectionStart = entries.size();
    entries.add(new CustomEntry());
    entries.addAll(data.taskEntries());

    BaseListPopupStep<PickerEntry> step = new BaseListPopupStep<>("Select Task or Worktree", entries) {
      @Override
      public boolean isSpeedSearchEnabled() {
        return true;
      }

      @Override
      public boolean hasSubstep(PickerEntry value) {
        return value instanceof WorktreeEntry || value instanceof ExternalWorktreeEntry;
      }

      @Override
      public @NotNull String getTextFor(PickerEntry value) {
        if (value instanceof WorktreeEntry w) {
          String name = w.path().getFileName() == null ? w.path().toString() : w.path().getFileName().toString();
          String base = w.branch().isEmpty() ? name : name + "  (" + w.branch() + ")";
          return w.main() ? base + "  [main]" : base;
        }
        if (value instanceof ExternalWorktreeEntry w) {
          String name = w.path().getFileName() == null ? w.path().toString() : w.path().getFileName().toString();
          String base = w.branch().isEmpty() ? name : name + "  (" + w.branch() + ")";
          Path owner = w.ownerRepoRoot();
          boolean linkedWorktree = owner != null && !owner.equals(w.path()) && owner.getFileName() != null;
          return linkedWorktree ? base + "  [" + owner.getFileName() + "]" : base;
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
        if (value instanceof WorktreeEntry || value instanceof ExternalWorktreeEntry) {
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
        if (idx == 0 && (value instanceof WorktreeEntry || value instanceof ExternalWorktreeEntry)) {
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
          Path removalWorkDir = w.main() ? null : repoRoot;
          return worktreeActionStep(project, w.path(), w.branch(), removalWorkDir, gitRepo);
        }
        if (value instanceof ExternalWorktreeEntry w) {
          Path owner = w.ownerRepoRoot();
          // A full clone (owner == the folder itself) is a main working tree — git cannot remove it.
          Path removalWorkDir = owner != null && !owner.equals(w.path()) ? owner : null;
          return worktreeActionStep(project, w.path(), w.branch(), removalWorkDir, null);
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

    ListPopup popup = JBPopupFactory.getInstance().createListPopup(
        project,
        step,
        base -> new PrBadgeRenderer(((PopupListElementRenderer<?>) base).getPopup(), data.prByPath()));
    data.currentPopup().set(popup);

    installShowAllCheckbox(data, showAll, popup);
    DumbAwareAction.create(e -> toggleShowAll(data, showAll, popup))
                   .registerCustomShortcutSet(getShortcutSet(), popup.getContent(), popup);

    if (location != null) {
      popup.show(RelativePoint.fromScreen(location));
    } else {
      popup.showCenteredInCurrentWindow(project);
    }
  }

  /**
   * Puts the "Show all worktrees" checkbox into the popup header: title left-aligned, checkbox
   * right-aligned in the caption's EAST button slot. Falls back to a footer below the list if the
   * platform's title panel cannot be located.
   */
  private void installShowAllCheckbox(@NotNull PickerData data, boolean showAll, @NotNull ListPopup popup) {
    JCheckBox showAllBox = new JCheckBox("Show all worktrees", showAll);
    // The list must keep keyboard focus so navigation and speed search stay usable.
    showAllBox.setFocusable(false);
    showAllBox.setOpaque(false);
    String shortcut = KeymapUtil.getFirstKeyboardShortcutText(this);
    if (!shortcut.isEmpty()) {
      showAllBox.setToolTipText("Toggle with " + shortcut);
    }
    showAllBox.addActionListener(e -> toggleShowAll(data, showAll, popup));

    CaptionPanel caption = UIUtil.findComponentOfType(popup.getContent(), CaptionPanel.class);
    if (caption != null) {
      JLabel titleLabel = UIUtil.findComponentOfType(caption, JLabel.class);
      if (titleLabel != null) {
        titleLabel.setHorizontalAlignment(SwingConstants.LEFT);
      }
      caption.setButtonComponent(new ActiveComponent.Adapter() {
        @Override
        public @NotNull JComponent getComponent() {
          return showAllBox;
        }
      }, JBUI.Borders.emptyRight(4));
      return;
    }

    showAllBox.setBorder(JBUI.Borders.empty(4, 10));
    JPanel footer = new JPanel(new BorderLayout());
    footer.setOpaque(false);
    footer.setBorder(JBUI.Borders.customLineTop(JBColor.border()));
    footer.add(showAllBox, BorderLayout.WEST);
    popup.getContent().add(footer, BorderLayout.SOUTH);
  }

  private void toggleShowAll(@NotNull PickerData data, boolean currentShowAll, @NotNull ListPopup popup) {
    Point location = popup.isVisible() ? popup.getLocationOnScreen() : null;
    popup.cancel();
    showPicker(data, !currentShowAll, location);
  }

  /**
   * Fetches PR statuses off the EDT after the popup is already visible, then fills the shared map
   * and repaints the popup. The renderer reserves a fixed badge slot up front, so the popup is
   * already sized for badges and a repaint is enough to reveal them.
   */
  private void loadPrStatuses(@NotNull PickerData data) {
    if (!PrStatusSupport.isAvailable()) {
      return;
    }
    boolean hasCandidates =
        data.repoWorktrees().stream().anyMatch(w -> !w.main() && !w.branch().isEmpty())
        || data.externalWorktrees().stream().anyMatch(w -> !w.branch().isEmpty() && w.ownerRepoRoot() != null);
    if (!hasCandidates) {
      return;
    }
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      // Built on the pooled thread: external lookups read the owner repo's .git/config from disk.
      List<PrStatusSupport.BranchLookup> lookups = new ArrayList<>();
      for (WorktreeService.WorktreeInfo w : data.repoWorktrees()) {
        if (!w.main() && !w.branch().isEmpty()) {
          lookups.add(new PrStatusSupport.BranchLookup(w.path(), w.branch(), null));
        }
      }
      for (WorktreeService.ExternalWorktree w : data.externalWorktrees()) {
        if (w.branch().isEmpty() || w.ownerRepoRoot() == null) {
          continue;
        }
        String remoteUrl = WorktreeService.readRemoteUrl(w.ownerRepoRoot());
        if (remoteUrl != null) {
          lookups.add(new PrStatusSupport.BranchLookup(w.path(), w.branch(), remoteUrl));
        }
      }
      Map<Path, PrStatus> statuses = PrStatusService.fetch(data.project(), data.gitRepo(), lookups);
      if (statuses.isEmpty()) {
        return;
      }
      ApplicationManager.getApplication().invokeLater(() -> {
        data.prByPath().putAll(statuses);
        ListPopup current = data.currentPopup().get();
        if (current != null && !current.isDisposed() && current instanceof ListPopupImpl listPopup) {
          listPopup.getList().repaint();
        }
      }, data.project().getDisposed());
    });
  }

  /**
   * Open/Remove sub-step for a worktree row. {@code removalWorkDir} is where {@code git worktree
   * remove} must run — the current repo root for own worktrees, the owning repo root for external
   * ones; {@code null} means removal is not possible (main worktree, full clone, unknown owner) and
   * only Open is offered. {@code repoToUpdate} is non-null only for the current repo's worktrees.
   */
  private PopupStep<?> worktreeActionStep(@NotNull Project project,
                                          @NotNull Path path,
                                          @NotNull String branch,
                                          @Nullable Path removalWorkDir,
                                          @Nullable GitRepository repoToUpdate) {
    String title = path.getFileName() == null ? path.toString() : path.getFileName().toString();
    List<String> actions = removalWorkDir == null ? List.of(ACTION_OPEN) : List.of(ACTION_OPEN, ACTION_REMOVE);
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
          return doFinalStep(() -> openWorktree(project, path));
        }
        if (ACTION_REMOVE.equals(selectedValue) && removalWorkDir != null) {
          return doFinalStep(() -> confirmAndRemoveWorktree(project, removalWorkDir, repoToUpdate, path, branch));
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
    openWorktreeProject(project, worktreePath);
  }

  /**
   * Opens the worktree as a project from a clean, non-modal EDT context. Reusing the current window
   * disposes the current project synchronously; doing that from inside the worktree-creation task's
   * own modality is what froze the IDE, so the open is deferred to {@link ModalityState#nonModal()}.
   * The new directory is registered with the VFS first so the copied {@code .idea} is picked up.
   */
  private void openWorktreeProject(@NotNull Project projectToClose, @NotNull Path worktreePath) {
    Runnable open = () -> {
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(worktreePath);
      ProjectUtil.openOrImport(worktreePath, projectToClose, false);
    };
    Application application = ApplicationManager.getApplication();
    if (application.isDispatchThread()) {
      open.run();
    } else {
      application.invokeLater(open, ModalityState.nonModal(), projectToClose.getDisposed());
    }
  }

  private void confirmAndRemoveWorktree(@NotNull Project project,
                                        @NotNull Path removalWorkDir,
                                        @Nullable GitRepository repoToUpdate,
                                        @NotNull Path path,
                                        @NotNull String branch) {
    int answer = Messages.showYesNoDialog(
        project,
        "Remove worktree at " + path + "?\n\nThe branch '" + branch
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
            WorktreeService.Result result =
                WorktreeService.removeWorktree(project, removalWorkDir, repoToUpdate, path, false);
            if (!result.success()) {
              String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
              boolean offerForce = detail.toLowerCase().contains("use --force")
                                   || detail.toLowerCase().contains("contains modified")
                                   || detail.toLowerCase().contains("locked");
              if (offerForce) {
                ApplicationManager.getApplication().invokeLater(
                    () -> retryRemoveWithForce(project, removalWorkDir, repoToUpdate, path, detail),
                    project.getDisposed()
                );
              } else {
                notifyError(project, "git worktree remove failed (exit " + result.exitCode() + "): " + detail.trim());
              }
              return;
            }
            notifyInfo(project, "Removed worktree " + path.getFileName());
          }
        });
  }

  private void retryRemoveWithForce(@NotNull Project project,
                                    @NotNull Path removalWorkDir,
                                    @Nullable GitRepository repoToUpdate,
                                    @NotNull Path path,
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
            WorktreeService.Result result =
                WorktreeService.removeWorktree(project, removalWorkDir, repoToUpdate, path, true);
            if (!result.success()) {
              String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
              notifyError(project, "git worktree remove --force failed (exit " + result.exitCode() + "): " + detail.trim());
              return;
            }
            notifyInfo(project, "Force-removed worktree " + path.getFileName());
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
        WorktreeNaming.suggestBranchName(task, repoName),
        WorktreeNaming.suggestFolderName(task, repoName)
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
    Map<String, git4idea.GitRemoteBranch> remoteByName = new LinkedHashMap<>();
    gitRepo.getBranches().getRemoteBranches().stream()
           .sorted((a, b) -> a.getName().compareTo(b.getName()))
           .forEach(b -> remoteByName.putIfAbsent(b.getName(), b));

    List<String> allBranches = new ArrayList<>(localBranches.size() + remoteByName.size());
    allBranches.addAll(localBranches);
    allBranches.addAll(remoteByName.keySet());

    git4idea.GitLocalBranch current = gitRepo.getCurrentBranch();
    String defaultBase = current != null ? current.getName() : "";

    CreateWorktreeDialog dialog = new CreateWorktreeDialog(
        project, windowTitle, defaultBranch, defaultFolder, defaultBase, worktreesParent, allBranches);
    if (!dialog.showAndGet()) {
      return;
    }
    String dialogBranch = dialog.getBranch();
    String folderName = dialog.getFolder();
    Path worktreePath = worktreesParent.resolve(folderName);

    String branchName;
    String baseRef;
    if (dialog.isNewBranch()) {
      branchName = dialogBranch;
      baseRef = dialog.getBaseRef();
    } else {
      git4idea.GitRemoteBranch pickedRemote = remoteByName.get(dialogBranch);
      branchName = pickedRemote == null ? dialogBranch : pickedRemote.getNameForRemoteOperations();
      baseRef = pickedRemote == null ? null : pickedRemote.getName();
    }

    if (Files.isDirectory(worktreePath)) {
      openWorktreeProject(project, worktreePath);
      return;
    }

    ProgressManager.getInstance().run(
        new com.intellij.openapi.progress.Task.Backgroundable(project, "Creating worktree " + branchName, true) {
          private boolean created;

          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            WorktreeService.Result result;
            try {
              result = WorktreeService.createWorktree(project, gitRepo, worktreePath, branchName, baseRef);
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
            created = true;
          }

          @Override
          public void onSuccess() {
            if (created) {
              openWorktreeProject(project, worktreePath);
            }
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

  private void chooseGitRepository(@NotNull Project project, @NotNull Consumer<GitRepository> onChosen) {
    List<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
    if (repos.isEmpty()) {
      notifyError(project, "No git repository found in this project.");
      return;
    }
    if (repos.size() == 1) {
      onChosen.accept(repos.get(0));
      return;
    }
    BaseListPopupStep<GitRepository> step = new BaseListPopupStep<>("Select Git Repository", repos) {
      @Override
      public boolean isSpeedSearchEnabled() {
        return true;
      }

      @Override
      public @NotNull String getTextFor(GitRepository value) {
        return value.getRoot().getPath();
      }

      @Override
      public PopupStep<?> onChosen(GitRepository value, boolean finalChoice) {
        return doFinalStep(() -> onChosen.accept(value));
      }
    };
    JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project);
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
