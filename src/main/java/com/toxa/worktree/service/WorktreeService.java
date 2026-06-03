package com.toxa.worktree.service;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.ide.RecentProjectsManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.toxa.worktree.settings.WorktreeSettings;
import git4idea.GitLocalBranch;
import git4idea.config.GitExecutableManager;
import git4idea.repo.GitRepository;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
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
  private static final String GIT_DIR = ".git";

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
      RecentProjectsManager.getInstance().removePath(worktreePath.toString());
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
      Path sourceRoot = Path.of(repo.getRoot().getPath());
      if (WorktreeSettings.getInstance().isCopyProjectConfig()) {
        copyIdeaConfig(sourceRoot, targetPath);
      }
      copyAdditionalFiles(sourceRoot, targetPath, WorktreeSettings.getInstance().getAdditionalCopyPatterns());
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

  private static void copyAdditionalFiles(@NotNull Path sourceRoot,
                                          @NotNull Path targetRoot,
                                          @NotNull List<String> patterns) {
    for (String raw : patterns) {
      String pattern = raw == null ? "" : raw.trim();
      if (pattern.isEmpty()) {
        continue;
      }
      try {
        copyByPattern(sourceRoot, targetRoot, pattern);
      } catch (IOException e) {
        LOG.warn("Failed to copy additional files for pattern '" + pattern + "' to worktree at " + targetRoot, e);
      }
    }
  }

  private static void copyByPattern(@NotNull Path sourceRoot,
                                    @NotNull Path targetRoot,
                                    @NotNull String pattern) throws IOException {
    if (!isGlob(pattern)) {
      Path source = sourceRoot.resolve(pattern).normalize();
      if (source.startsWith(sourceRoot) && Files.exists(source)) {
        copyMatched(source, sourceRoot, targetRoot);
      }
      return;
    }

    Path walkRoot = sourceRoot.resolve(literalPrefix(pattern)).normalize();
    if (!walkRoot.startsWith(sourceRoot) || !Files.exists(walkRoot)) {
      return;
    }
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    Files.walkFileTree(walkRoot, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        return isGitDir(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (matcher.matches(sourceRoot.relativize(file))) {
          copyFile(file, targetRoot.resolve(sourceRoot.relativize(file)));
        }
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void copyMatched(@NotNull Path source,
                                  @NotNull Path sourceRoot,
                                  @NotNull Path targetRoot) throws IOException {
    if (!Files.isDirectory(source)) {
      copyFile(source, targetRoot.resolve(sourceRoot.relativize(source)));
      return;
    }
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        if (isGitDir(dir)) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        Files.createDirectories(targetRoot.resolve(sourceRoot.relativize(dir)));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        copyFile(file, targetRoot.resolve(sourceRoot.relativize(file)));
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void copyFile(@NotNull Path source, @NotNull Path dest) throws IOException {
    Path parent = dest.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
  }

  private static boolean isGitDir(@NotNull Path dir) {
    Path name = dir.getFileName();
    return name != null && GIT_DIR.equals(name.toString());
  }

  private static boolean isGlob(@NotNull String pattern) {
    return pattern.indexOf('*') >= 0
           || pattern.indexOf('?') >= 0
           || pattern.indexOf('[') >= 0
           || pattern.indexOf('{') >= 0;
  }

  @NotNull
  private static String literalPrefix(@NotNull String pattern) {
    String[] segments = pattern.replace('\\', '/').split("/");
    StringBuilder prefix = new StringBuilder();
    for (String segment : segments) {
      if (isGlob(segment)) {
        break;
      }
      if (prefix.length() > 0) {
        prefix.append('/');
      }
      prefix.append(segment);
    }
    return prefix.toString();
  }
}
