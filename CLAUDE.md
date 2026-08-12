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

Single end-user entry point: the `TaskWorktree.OpenTaskInWorktree` action (Tools menu, VCS menu, Alt+Shift+P). One action class, one dialog, one service, plus two small helpers (`WorktreeRemover`, `WorktreeNotifications`) — kept intentionally flat.

Flow when the action fires:

1. `OpenTaskInWorktreeAction.actionPerformed` picks a `GitRepository` from `GitRepositoryManager` (prompts if multiple).
2. A background `Task.Backgroundable` calls `WorktreeService.listWorktrees` and iterates every configured `TaskRepository` (`TaskManager.getAllRepositories()`) via `repo.getIssues(null, TASKS_PER_REPO, 0)` — task source is whatever the user configured under Settings | Tools | Tasks (TargetProcess, Jira, YouTrack, GitHub, …). The plugin is task-server-agnostic by design.
3. EDT shows a `JBPopupFactory` list popup of `PickerEntry` (sealed) — existing `WorktreeEntry`s above `TaskEntry`s, with separators. Choosing a worktree opens a sub-step (Open / Remove); choosing a task opens `CreateWorktreeDialog`.
4. Selecting several rows (shift+arrows, cmd/ctrl+click) and confirming removes them all — see the multi-selection invariant below.
5. On confirm, `WorktreeService.createWorktree` shells out to `git worktree add` via `GeneralCommandLine` + `CapturingProcessHandler`, using `GitExecutableManager.getPathToGit(project)`. On success, `.idea/` is copied from the main repo root into the new worktree so the IDE opens with the same project config, then `openWorktreeProject` opens it. The open is deferred to the task's `onSuccess()` and run via `invokeLater(..., ModalityState.nonModal(), ...)` before calling `ProjectUtil.openOrImport`; opening from inside the creation task's own modality froze the IDE when the user chose to reuse the current window (which disposes the current project synchronously).

Key invariants worth knowing before editing:

- **Worktrees live in a sibling `worktrees/` directory** next to the repo root (`repoPath.getParent().resolve("worktrees")`). Folder name comes from the configurable "Worktree folder name pattern" (`WorktreeSettings.getWorktreeNamePattern`), defaulting to `${id}` (the task id).
- **Branch reuse vs. create**: `createWorktree` checks `repo.getBranches().findLocalBranch(branchName)`. If it exists, `git worktree add <path> <branch>`. Otherwise `git worktree add -b <branch> <path> <base>`. The base is chosen in `CreateWorktreeDialog` ("Base:" filterable combo, active only in New branch mode, any local/remote ref), defaulting to the repo's current branch; `createWorktree` falls back to the current branch (or `HEAD`) only when `baseRef` is null/blank. This lets a new worktree be branched off e.g. `master` without switching to the master worktree.
- **Branch and folder naming** go through `WorktreeNaming.suggestBranchName` / `suggestFolderName`, which render the configurable patterns (`WorktreeSettings.getBranchNamePattern` / `getWorktreeNamePattern`) substituting `${id}`, `${number}`, `${type}` (task type, lower-cased, then remapped via the configurable `WorktreeSettings.getTaskTypeMappings()` table — e.g. `other`→`feature`), `${summary}`, and `${project}` (repo name). Both patterns default to `${id}`; a blank pattern falls back to `task.getPresentableId()`. Branch names then run through `GitRefNameValidator.cleanUpBranchName`.
- **Removal UX has a force fallback**: `removeWorktree(..., force=false)` first; on failure, if stderr contains `use --force`, `contains modified`, or `locked`, the user is offered a `--force` retry. All of this lives in `WorktreeRemover`, the single removal engine for one *or many* worktrees: confirm → sequential background removal → one batched force-retry dialog for every entry git refused → one summary notification. Only the dialog wording branches on `size() == 1`. Declining the force retry is treated as a choice, not a failure, so nothing is reported for it.
- **Multi-selection**: the picker step is a `MultiSelectionListPopupStep<PickerEntry>` (not `BaseListPopupStep`), which is what makes `ListPopupImpl` install `MULTIPLE_INTERVAL_SELECTION` and suppress hover-reselect / click-to-execute while a selection modifier is held — shift+arrows and cmd/ctrl+click then work with no extra code. That base class makes `onChosen(T, boolean)` and `hasSubstep(T)` **final**, so the list-form overrides are the only entry points. A single selection dispatches exactly as before; two or more open `bulkRemoveStep`, a sub-step offering only Remove, since Open makes no sense for many rows. `hasSubstep` must return true for the multi case or the sub-step is unreachable by keyboard: right arrow routes through `handleRightKeyPressed` → `handleSelect(false, e)`, and `_handleSelect` returns early when `!hasSubstep && !handleFinalChoices`. The platform passes `ContainerUtil.getOnlyItem(selectedValues)` — i.e. `null` — as the sub-popup's parent value for a multi-selection; that is safe, `ListPopupImpl`'s 4-arg constructor only null-checks the step. `OpenTaskInWorktreeAction.asRemovable` is the one definition of "removable" (returns `null` for the main worktree, a full clone, an unknown owner, task and custom rows) and feeds both the single and bulk paths; non-removable rows are silently dropped from a multi-selection. The `WorktreeRow` sealed sub-interface covers the two on-disk row records so the picker does not have to test both types everywhere.
- **Worktree listing parses `git worktree list --porcelain`** and filters out the main repo root by absolute-normalized path comparison.
- **"Show all worktrees" toggle**: the picker popup has a checkbox in its header — title left-aligned, checkbox right-aligned via `CaptionPanel.setButtonComponent` (avoid `TitlePanel.getLabel()`, it is `@ApiStatus.Internal`) — also toggled by re-pressing the action's own shortcut while the popup is open (registered via `registerCustomShortcutSet(getShortcutSet(), popup.getContent(), popup)`). When checked, entries additionally include `WorktreeService.scanWorktreesDirectory(...)` results: worktrees of *other* repos found in the configured worktrees dir, discovered by parsing `.git` files (`gitdir:` pointer + `HEAD`) with no git process. External entries are a separate sealed record (`ExternalWorktreeEntry`); their Remove runs `git worktree remove` with the owning repo root as the work dir (full clones are Open-only). PR badges cover external worktrees too: the status map is keyed by worktree **path** (branch names can collide across repos), and `PrStatusService.fetch` takes `PrStatusSupport.BranchLookup`s — `remoteUrl == null` resolves via the IDE's GitHub mappings, otherwise coordinates are parsed from the owner repo's remote URL (read from `.git/config` by `WorktreeService.readRemoteUrl`, parsed via `GitHostingUrlUtil.getUriFromRemoteUrl` — `GithubUrlUtil.getHostFromUrl` is deprecated-for-removal). Both lists load once per session in the background task; toggling rebuilds the popup on the EDT from preloaded `PickerData`, ephemeral and unchecked on every fresh open.
- **Notifications** all go through the `Task Worktree` notification group registered in `plugin.xml`. Use `WorktreeNotifications.notifyInfo` / `notifyError` rather than `Messages.show*` for non-blocking feedback.
- `update()` runs on `ActionUpdateThread.BGT` and gates the action on having ≥1 git repo — keep it cheap; don't add task-server calls there.

## Plugin descriptor

`src/main/resources/META-INF/plugin.xml` depends on `com.intellij.modules.platform`, `com.intellij.tasks`, and `Git4Idea`. Those three plugins must remain in both the descriptor and `build.gradle.kts` `intellijPlatform { bundledPlugin(...) }` block — they are what makes `com.intellij.tasks.*` and `git4idea.*` APIs resolvable.

## Conventions

- Java 21, records and sealed types are used (`PickerEntry`, `WorktreeInfo`, `Result`). Keep new model types in the same style.
- All git invocations go through `GeneralCommandLine` + `CapturingProcessHandler` with explicit `withWorkDirectory(repoRoot)` and UTF-8 — do not introduce a second pathway (e.g. `git4idea.commands.Git`) without a reason.
- Long-running operations belong in `Task.Backgroundable`; UI updates back on EDT via `ApplicationManager.invokeLater(..., project.getDisposed())`.
