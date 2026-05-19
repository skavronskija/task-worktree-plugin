# Task Worktree

IntelliJ plugin that creates git worktrees from IntelliJ tasks — or any branch you choose — and opens them as projects in a single step. Works with any task server configured in IntelliJ's bundled Tasks plugin (TargetProcess, Jira, YouTrack, GitHub, …).

## Install

**From JetBrains Marketplace** — search for *Task Worktree* under **Settings | Plugins | Marketplace**.

**From disk** — download `task-worktree-plugin-<version>.zip` from the [releases](https://github.com/skavronskija/task-worktree-plugin/releases) or build it yourself, then **Settings | Plugins | ⚙ | Install Plugin from Disk…**.

Compatible with IntelliJ IDEA 2025.1+ (`sinceBuild = 251`). Requires the bundled Tasks and Git4Idea plugins (enabled by default).

## Use

Default shortcut: **Alt+Shift+P** (also reachable from **Tools | Open Task in Worktree…** and **VCS | Open Task in Worktree…**).

The picker is two sections, both filterable by typing:

1. **Existing Worktrees** — open or remove each one. The main repo worktree shows a `[main]` tag and offers only *Open* (it cannot be removed from the UI).
2. **Tasks** — first entry is **Custom worktree…** for ad-hoc creation; the rest are open tasks from every configured task server. Choosing any of them opens a *Create Worktree* dialog.

The Create dialog lets you:

- Pick **New branch** (default; suggested name comes from the task) or **Existing branch** — the existing-branch combo is filterable as you type.
- Edit the folder name (the path preview updates live).

After creation, the new worktree opens as a new IntelliJ project. The plugin can also copy the source repo's `.idea/` into the new worktree so the IDE opens with project settings intact (configurable; on by default).

When you remove a worktree from the picker, its entry is also removed from IntelliJ's **Recent Projects** list automatically.

## Settings

**Settings | Tools | Task Worktree** (application-level — applies to every project):

- **Base worktrees directory** — where new worktrees are created. Blank means `<repo parent>/worktrees`. Absolute paths are used as-is; relative paths resolve against the repo parent.
- **Copy project configuration (`.idea`) to new worktrees** — on by default.
- **Clean Up Obsolete Recent Projects** — bulk action: scans the Recent Projects list, lists entries whose paths no longer exist on disk, asks for confirmation, and removes them. Fills the gap that IntelliJ doesn't ship a "purge greyed-out only" command.

## Build from source

Toolchain: Java 21, Kotlin 2.1.0, IntelliJ Platform Gradle Plugin 2.7.1, target IC 2025.1.4.1.

```bash
./gradlew buildPlugin       # → build/distributions/task-worktree-plugin-<version>.zip
./gradlew runIde            # sandbox IDE with the plugin installed
./gradlew verifyPlugin      # JetBrains plugin compatibility verifier
```

The version comes from `<version>` in `src/main/resources/META-INF/plugin.xml`. To override for a one-off build: `./gradlew -PbuildVersion=0.1.0-rc1 buildPlugin`.

## License

TBD.
