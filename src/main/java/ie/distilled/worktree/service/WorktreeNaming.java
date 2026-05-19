package ie.distilled.worktree.service;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.tasks.Task;
import com.intellij.tasks.TaskManager;
import com.intellij.tasks.impl.TaskManagerImpl;
import git4idea.validators.GitRefNameValidator;
import org.jetbrains.annotations.NotNull;

public final class WorktreeNaming {

  private static final Logger LOG = Logger.getInstance(WorktreeNaming.class);

  private WorktreeNaming() {
  }

  @NotNull
  public static String suggestBranchName(@NotNull Project project, @NotNull Task task) {
    String suggested;
    try {
      TaskManager manager = TaskManager.getManager(project);
      if (manager instanceof TaskManagerImpl impl) {
        suggested = impl.suggestBranchName(task);
      } else {
        suggested = task.getPresentableId();
      }
    } catch (Throwable t) {
      LOG.warn("Failed to use TaskManagerImpl.suggestBranchName, falling back to presentable id", t);
      suggested = task.getPresentableId();
    }

    if (suggested == null || suggested.isBlank()) {
      suggested = task.getPresentableId();
    }

    return GitRefNameValidator.getInstance().cleanUpBranchName(suggested);
  }
}
