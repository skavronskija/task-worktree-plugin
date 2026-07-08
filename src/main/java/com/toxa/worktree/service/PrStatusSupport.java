package com.toxa.worktree.service;

import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * GitHub-free gate and shared model for PR-status integration.
 *
 * <p>This class deliberately references <strong>no</strong> {@code org.jetbrains.plugins.github.*}
 * type: the GitHub plugin is an optional dependency, so its classes are only on this plugin's
 * classloader while it is enabled. Loading {@link PrStatusService} (which does reference those
 * types) when the plugin is disabled fails with {@code NoClassDefFoundError}, so the availability
 * gate must live here, apart from the class it guards. Callers check {@link #isAvailable()} first
 * and only then touch {@link PrStatusService}.
 */
public final class PrStatusSupport {

  private static final String GH_PROBE_CLASS = "org.jetbrains.plugins.github.util.GHHostedRepositoriesManager";

  public enum PrStatus {
    DRAFT, OPEN, MERGED, CLOSED
  }

  /**
   * One PR-status lookup request. {@code remoteUrl == null} means the branch belongs to the current
   * project's repository (coordinates come from the IDE's GitHub mappings); otherwise coordinates
   * are parsed from the given git remote URL of the worktree's owner repository.
   */
  public record BranchLookup(@NotNull Path worktreePath, @NotNull String branch, @Nullable String remoteUrl) {
  }

  private PrStatusSupport() {
  }

  /**
   * Whether the optional GitHub plugin is enabled. Probes for one of its classes on this plugin's
   * classloader instead of querying the plugin manager: an optional dependency's classes are only
   * visible here while the plugin is loaded, so this both avoids unstable plugin-management API and
   * precisely tracks the enabled state.
   */
  public static boolean isAvailable() {
    try {
      Class.forName(GH_PROBE_CLASS, false, PrStatusSupport.class.getClassLoader());
      return true;
    } catch (Throwable t) {
      return false;
    }
  }
}
