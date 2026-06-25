package com.toxa.worktree.service;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import git4idea.repo.GitRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.github.api.GHGQLRequests;
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates;
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor;
import org.jetbrains.plugins.github.api.GithubServerPath;
import org.jetbrains.plugins.github.api.data.graphql.query.GHGQLSearchQueryResponse;
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestShort;
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestState;
import org.jetbrains.plugins.github.authentication.GHAccountsUtil;
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount;
import org.jetbrains.plugins.github.util.GHCompatibilityUtil;
import org.jetbrains.plugins.github.util.GHGitRepositoryMapping;
import org.jetbrains.plugins.github.util.GHHostedRepositoriesManager;

import com.intellij.collaboration.api.data.GraphQLRequestPagination;

/**
 * Looks up the GitHub pull-request state for worktree branches via the bundled GitHub plugin's API.
 *
 * <p>Every reference to {@code org.jetbrains.plugins.github.*} lives in this class, which is only
 * touched after {@link #isAvailable()} confirms the optional GitHub plugin is enabled — so a
 * disabled or absent plugin never triggers a {@code NoClassDefFoundError}. The lookup is silent:
 * any missing prerequisite (not a GitHub repo, no account, no token, API failure) yields an empty
 * result rather than an error.
 */
public final class PrStatusService {

  private static final Logger LOG = Logger.getInstance(PrStatusService.class);
  private static final String GH_PROBE_CLASS = "org.jetbrains.plugins.github.util.GHHostedRepositoriesManager";
  private static final int MAX_BRANCHES = 30;

  public enum PrStatus {
    DRAFT, OPEN, MERGED, CLOSED
  }

  private PrStatusService() {
  }

  /**
   * Whether the optional GitHub plugin is enabled. Probes for one of its classes on this plugin's
   * classloader instead of querying the plugin manager: an optional dependency's classes are only
   * visible here while the plugin is loaded, so this both avoids unstable plugin-management API and
   * precisely tracks the enabled state.
   */
  public static boolean isAvailable() {
    try {
      Class.forName(GH_PROBE_CLASS, false, PrStatusService.class.getClassLoader());
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * Maps each given branch to its pull-request status. Branches without a PR are absent from the
   * map. Returns an empty map when the repo is not on GitHub, no GitHub credentials are configured,
   * or the lookup fails. Safe to call from a background thread.
   */
  @NotNull
  public static Map<String, PrStatus> fetch(@NotNull Project project,
                                            @NotNull GitRepository gitRepo,
                                            @NotNull Collection<String> branches) {
    if (branches.isEmpty()) {
      return Map.of();
    }
    try {
      GHRepositoryCoordinates coordinates = findCoordinates(project, gitRepo);
      if (coordinates == null) {
        return Map.of();
      }
      GithubServerPath server = coordinates.getServerPath();
      String token = findToken(project, server);
      if (token == null || token.isBlank()) {
        return Map.of();
      }
      GithubApiRequestExecutor executor = GithubApiRequestExecutor.Factory.getInstance().create(server, token);
      String ownerName = coordinates.getRepositoryPath().toString();

      Map<String, PrStatus> result = new LinkedHashMap<>();
      int looked = 0;
      for (String branch : branches) {
        if (branch.isBlank() || looked++ >= MAX_BRANCHES) {
          continue;
        }
        PrStatus status = lookupBranch(executor, server, ownerName, branch);
        if (status != null) {
          result.put(branch, status);
        }
      }
      return result;
    } catch (Throwable t) {
      LOG.debug("PR status lookup failed", t);
      return Map.of();
    }
  }

  private static GHRepositoryCoordinates findCoordinates(@NotNull Project project,
                                                         @NotNull GitRepository gitRepo) {
    GHHostedRepositoriesManager manager = project.getService(GHHostedRepositoriesManager.class);
    if (manager == null) {
      return null;
    }
    Set<GHGitRepositoryMapping> mappings = manager.getKnownRepositoriesState().getValue();
    GHGitRepositoryMapping fallback = null;
    for (GHGitRepositoryMapping mapping : mappings) {
      if (fallback == null) {
        fallback = mapping;
      }
      if (gitRepo.equals(mapping.getGitRepository())) {
        return mapping.getRepository();
      }
    }
    return fallback == null ? null : fallback.getRepository();
  }

  private static String findToken(@NotNull Project project, @NotNull GithubServerPath server) {
    Set<GithubAccount> accounts = GHAccountsUtil.getAccounts();
    if (accounts.isEmpty()) {
      return null;
    }
    GithubAccount match = null;
    for (GithubAccount account : accounts) {
      if (server.equals(account.getServer())) {
        match = account;
        break;
      }
    }
    if (match == null) {
      match = accounts.iterator().next();
    }
    return GHCompatibilityUtil.getOrRequestToken(match, project);
  }

  private static PrStatus lookupBranch(@NotNull GithubApiRequestExecutor executor,
                                       @NotNull GithubServerPath server,
                                       @NotNull String ownerName,
                                       @NotNull String branch) {
    try {
      String query = "repo:" + ownerName + " is:pr head:" + branch;
      GHGQLSearchQueryResponse<GHPullRequestShort> response = executor.execute(
          GHGQLRequests.PullRequest.INSTANCE.search(server, query, GraphQLRequestPagination.Companion.getDEFAULT()));
      java.util.List<GHPullRequestShort> nodes = response.getNodes();
      if (nodes.isEmpty()) {
        return null;
      }
      return toStatus(nodes.get(0));
    } catch (Throwable t) {
      LOG.debug("PR status lookup failed for branch " + branch, t);
      return null;
    }
  }

  private static PrStatus toStatus(@NotNull GHPullRequestShort pr) {
    GHPullRequestState state = pr.getState();
    if (state == GHPullRequestState.MERGED) {
      return PrStatus.MERGED;
    }
    if (state == GHPullRequestState.CLOSED) {
      return PrStatus.CLOSED;
    }
    return pr.isDraft() ? PrStatus.DRAFT : PrStatus.OPEN;
  }
}
