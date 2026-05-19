package ie.distilled.worktree.service;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import ie.distilled.worktree.settings.WorktreeSettings;
import git4idea.GitLocalBranch;
import git4idea.config.GitExecutableManager;
import git4idea.repo.GitRepository;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class WorktreeService {

  private static final Logger LOG = Logger.getInstance(WorktreeService.class);
  private static final String IDEA_DIR = ".idea";

  public record Result(boolean success, @NotNull String stdout, @NotNull String stderr, int exitCode) {
  }

  public record WorktreeInfo(@NotNull Path path, @NotNull String branch, boolean main) {
  }

  private WorktreeService() {
  }

  @NotNull
  public static List<WorktreeInfo> listWorktrees(@NotNull Project project, @NotNull GitRepository repo) {
    String gitExecutable = GitExecutableManager.getInstance().getPathToGit(project);
    GeneralCommandLine commandLine = new GeneralCommandLine(gitExecutable, "worktree", "list", "--porcelain")
        .withWorkDirectory(new File(repo.getRoot().getPath()))
        .withCharset(StandardCharsets.UTF_8);
    ProcessOutput output;
    try {
      Process process = commandLine.createProcess();
      output = new CapturingProcessHandler(process, StandardCharsets.UTF_8,
                                           commandLine.getCommandLineString()).runProcess();
    } catch (ExecutionException e) {
      LOG.warn("Failed to list git worktrees", e);
      return List.of();
    }
    if (output.getExitCode() != 0) {
      LOG.warn("git worktree list exited with " + output.getExitCode() + ": " + output.getStderr());
      return List.of();
    }

    Path mainRoot = Path.of(repo.getRoot().getPath()).toAbsolutePath().normalize();
    List<WorktreeInfo> result = new ArrayList<>();
    Path currentPath = null;
    String currentBranch = "";
    boolean currentIsMain = false;
    boolean firstEntry = true;
    for (String line : output.getStdout().split("\\r?\\n")) {
      if (line.startsWith("worktree ")) {
        currentPath = Path.of(line.substring("worktree ".length())).toAbsolutePath().normalize();
        currentBranch = "";
        currentIsMain = firstEntry;
        firstEntry = false;
      } else if (line.startsWith("branch refs/heads/")) {
        currentBranch = line.substring("branch refs/heads/".length());
      } else if (line.isEmpty() && currentPath != null) {
        if (!currentPath.equals(mainRoot)) {
          result.add(new WorktreeInfo(currentPath, currentBranch, currentIsMain));
        }
        currentPath = null;
        currentBranch = "";
        currentIsMain = false;
      }
    }
    if (currentPath != null && !currentPath.equals(mainRoot)) {
      result.add(new WorktreeInfo(currentPath, currentBranch, currentIsMain));
    }
    return result;
  }

  @NotNull
  public static Result removeWorktree(@NotNull Project project,
                                      @NotNull GitRepository repo,
                                      @NotNull Path worktreePath,
                                      boolean force) {
    String gitExecutable = GitExecutableManager.getInstance().getPathToGit(project);
    List<String> args = new ArrayList<>();
    args.add(gitExecutable);
    args.add("worktree");
    args.add("remove");
    if (force) {
      args.add("--force");
    }
    args.add(worktreePath.toString());
    GeneralCommandLine commandLine = new GeneralCommandLine(args)
        .withWorkDirectory(new File(repo.getRoot().getPath()))
        .withCharset(StandardCharsets.UTF_8);
    ProcessOutput output;
    try {
      Process process = commandLine.createProcess();
      output = new CapturingProcessHandler(process, StandardCharsets.UTF_8,
                                           commandLine.getCommandLineString()).runProcess();
    } catch (ExecutionException e) {
      return new Result(false, "", e.getMessage() == null ? "" : e.getMessage(), -1);
    }
    Result result = new Result(output.getExitCode() == 0, output.getStdout(), output.getStderr(), output.getExitCode());
    if (result.success()) {
      repo.update();
    }
    return result;
  }

  @NotNull
  public static Result createWorktree(@NotNull Project project,
                                      @NotNull GitRepository repo,
                                      @NotNull Path targetPath,
                                      @NotNull String branchName,
                                      @Nullable String baseRef) throws ExecutionException {
    if (Files.exists(targetPath)) {
      throw new IllegalStateException("Worktree path already exists: " + targetPath);
    }

    String gitExecutable = GitExecutableManager.getInstance().getPathToGit(project);
    GitLocalBranch existing = repo.getBranches().findLocalBranch(branchName);
    GeneralCommandLine commandLine;
    if (existing != null) {
      commandLine = new GeneralCommandLine(
          gitExecutable, "worktree", "add", targetPath.toString(), branchName
      );
    } else {
      String resolvedBase = baseRef;
      if (resolvedBase == null || resolvedBase.isBlank()) {
        GitLocalBranch current = repo.getCurrentBranch();
        resolvedBase = current != null ? current.getName() : "HEAD";
      }
      commandLine = new GeneralCommandLine(
          gitExecutable, "worktree", "add", "-b", branchName, targetPath.toString(), resolvedBase
      );
    }
    commandLine.withWorkDirectory(new File(repo.getRoot().getPath()))
               .withCharset(StandardCharsets.UTF_8);

    Process process = commandLine.createProcess();
    ProcessOutput output = new CapturingProcessHandler(process, StandardCharsets.UTF_8,
                                                      commandLine.getCommandLineString()).runProcess();

    Result result = new Result(output.getExitCode() == 0, output.getStdout(), output.getStderr(), output.getExitCode());
    if (result.success()) {
      if (WorktreeSettings.getInstance().isCopyProjectConfig()) {
        copyIdeaConfig(Path.of(repo.getRoot().getPath()), targetPath);
      }
      repo.update();
    }
    return result;
  }

  private static void copyIdeaConfig(@NotNull Path sourceRoot, @NotNull Path targetRoot) {
    Path source = sourceRoot.resolve(IDEA_DIR);
    if (!Files.isDirectory(source)) {
      return;
    }
    Path target = targetRoot.resolve(IDEA_DIR);
    try {
      Files.walkFileTree(source, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
          Path destDir = target.resolve(source.relativize(dir));
          Files.createDirectories(destDir);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Path destFile = target.resolve(source.relativize(file));
          Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      LOG.warn("Failed to copy " + IDEA_DIR + " to worktree at " + targetRoot, e);
    }
  }
}
