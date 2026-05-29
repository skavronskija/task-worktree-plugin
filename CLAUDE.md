# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

Gradle wrapper drives everything. Toolchain: Java 21, Kotlin 2.1.0, IntelliJ Platform Gradle Plugin 2.7.1, target IDE `IC 2025.1.4.1` (`sinceBuild = 251`).

- `./gradlew build` — compile and assemble.
- `./gradlew runIde` — launch a sandbox IntelliJ with the plugin installed (slow first run; downloads the IC distribution into `.intellijPlatform/`).
- `./gradlew buildPlugin` — produce the distributable zip under `build/distributions/`.
- `./gradlew verifyPlugin` — run JetBrains' plugin verifier against the configured `sinceBuild`.
- `./gradlew -PbuildVersion=<x.y.z> buildPlugin` — pin the version; otherwise `version` defaults to a `yyyyMMdd-HHmm` timestamp computed at configuration time (see `build.gradle.kts`).

There is no test source set; do not invent `./gradlew test` instructions.

## Architecture

Single end-user entry point: the `TaskWorktree.OpenTaskInWorktree` action (Tools menu, VCS menu, Alt+Shift+P). One action class, one dialog, one service — kept intentionally flat.

Flow when the action fires:

1. `OpenTaskInWorktreeAction.actionPerformed` picks a `GitRepository` from `GitRepositoryManager` (prompts if multiple).
2. A background `Task.Backgroundable` calls `WorktreeService.listWorktrees` and iterates every configured `TaskRepository` (`TaskManager.getAllRepositories()`) via `repo.getIssues(null, TASKS_PER_REPO, 0)` — task source is whatever the user configured under Settings | Tools | Tasks (TargetProcess, Jira, YouTrack, GitHub, …). The plugin is task-server-agnostic by design.
3. EDT shows a `JBPopupFactory` list popup of `PickerEntry` (sealed) — existing `WorktreeEntry`s above `TaskEntry`s, with separators. Choosing a worktree opens a sub-step (Open / Remove); choosing a task opens `CreateWorktreeDialog`.
4. On confirm, `WorktreeService.createWorktree` shells out to `git worktree add` via `GeneralCommandLine` + `CapturingProcessHandler`, using `GitExecutableManager.getPathToGit(project)`. On success, `.idea/` is copied from the main repo root into the new worktree so the IDE opens with the same project config, then `openWorktreeProject` opens it. The open is deferred to the task's `onSuccess()` and run via `invokeLater(..., ModalityState.nonModal(), ...)` before calling `ProjectUtil.openOrImport`; opening from inside the creation task's own modality froze the IDE when the user chose to reuse the current window (which disposes the current project synchronously).

Key invariants worth knowing before editing:

- **Worktrees live in a sibling `worktrees/` directory** next to the repo root (`repoPath.getParent().resolve("worktrees")`). Folder name comes from the configurable "Worktree folder name pattern" (`WorktreeSettings.getWorktreeNamePattern`), defaulting to `${id}` (the task id).
- **Branch reuse vs. create**: `createWorktree` checks `repo.getBranches().findLocalBranch(branchName)`. If it exists, `git worktree add <path> <branch>`. Otherwise `git worktree add -b <branch> <path> <base>`. The base is chosen in `CreateWorktreeDialog` ("Base:" filterable combo, active only in New branch mode, any local/remote ref), defaulting to the repo's current branch; `createWorktree` falls back to the current branch (or `HEAD`) only when `baseRef` is null/blank. This lets a new worktree be branched off e.g. `master` without switching to the master worktree.
- **Branch and folder naming** go through `WorktreeNaming.suggestBranchName` / `suggestFolderName`, which render the configurable patterns (`WorktreeSettings.getBranchNamePattern` / `getWorktreeNamePattern`) substituting `${id}`, `${number}`, `${summary}`, and `${project}` (repo name). Both patterns default to `${id}`; a blank pattern falls back to `task.getPresentableId()`. Branch names then run through `GitRefNameValidator.cleanUpBranchName`.
- **Removal UX has a force fallback**: `removeWorktree(..., force=false)` first; on failure, if stderr contains `use --force`, `contains modified`, or `locked`, the user is offered a `--force` retry.
- **Worktree listing parses `git worktree list --porcelain`** and filters out the main repo root by absolute-normalized path comparison.
- **Notifications** all go through the `Task Worktree` notification group registered in `plugin.xml`. Use `notifyInfo` / `notifyError` rather than `Messages.show*` for non-blocking feedback.
- `update()` runs on `ActionUpdateThread.BGT` and gates the action on having ≥1 git repo — keep it cheap; don't add task-server calls there.

## Plugin descriptor

`src/main/resources/META-INF/plugin.xml` depends on `com.intellij.modules.platform`, `com.intellij.tasks`, and `Git4Idea`. Those three plugins must remain in both the descriptor and `build.gradle.kts` `intellijPlatform { bundledPlugin(...) }` block — they are what makes `com.intellij.tasks.*` and `git4idea.*` APIs resolvable.

## Conventions

- Java 21, records and sealed types are used (`PickerEntry`, `WorktreeInfo`, `Result`). Keep new model types in the same style.
- All git invocations go through `GeneralCommandLine` + `CapturingProcessHandler` with explicit `withWorkDirectory(repoRoot)` and UTF-8 — do not introduce a second pathway (e.g. `git4idea.commands.Git`) without a reason.
- Long-running operations belong in `Task.Backgroundable`; UI updates back on EDT via `ApplicationManager.invokeLater(..., project.getDisposed())`.
