package com.toxa.worktree.service;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import git4idea.remote.hosting.GitHostingUrlUtil;
import git4idea.repo.GitRepository;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.github.api.GHGQLRequests;
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates;
import org.jetbrains.plugins.github.api.GHRepositoryPath;
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
import org.jetbrains.plugins.github.util.GithubUrlUtil;

import com.intellij.collaboration.api.data.GraphQLRequestPagination;
import com.toxa.worktree.service.PrStatusSupport.BranchLookup;
import com.toxa.worktree.service.PrStatusSupport.PrStatus;

/**
 * Looks up the GitHub pull-request state for worktree branches via the bundled GitHub plugin's API.
 *
 * <p>Every reference to {@code org.jetbrains.plugins.github.*} lives in this class. Because the
 * GitHub plugin is an optional dependency, its classes — and therefore this class — must only ever
 * be loaded after {@link PrStatusSupport#isAvailable()} confirms the plugin is enabled; otherwise
 * loading this class fails with {@code NoClassDefFoundError}. The gate deliberately lives in the
 * GitHub-free {@link PrStatusSupport} so the check itself never loads this class. The lookup is
 * silent: any missing prerequisite (not a GitHub repo, no account, no token, API failure) yields an
 * empty result rather than an error.
 */
public final class PrStatusService {

  private static final Logger LOG = Logger.getInstance(PrStatusService.class);
  private static final int MAX_BRANCHES = 30;

  private PrStatusService() {
  }

  /**
   * Maps each lookup's worktree path to the pull-request status of its branch. Lookups with
   * {@code remoteUrl == null} resolve against the current project's repository; the rest resolve
   * against the GitHub repo parsed from their remote URL. Worktrees without a PR are absent from
   * the map. Missing prerequisites for any repo (not on GitHub, no credentials, parse failure)
   * silently skip that repo's lookups. Safe to call from a background thread.
   */
  @NotNull
  public static Map<Path, PrStatus> fetch(@NotNull Project project,
                                          @NotNull GitRepository gitRepo,
                                          @NotNull List<BranchLookup> lookups) {
    try {
      // Nullable values are cached too, so a repo that fails to resolve is only attempted once.
      Map<String, GHRepositoryCoordinates> coordinatesByUrl = new LinkedHashMap<>();
      Map<GithubServerPath, GithubApiRequestExecutor> executorByServer = new LinkedHashMap<>();
      GHRepositoryCoordinates currentRepoCoordinates = null;
      boolean currentRepoResolved = false;

      Map<Path, PrStatus> result = new LinkedHashMap<>();
      int looked = 0;
      for (BranchLookup lookup : lookups) {
        if (lookup.branch().isBlank() || looked >= MAX_BRANCHES) {
          continue;
        }
        GHRepositoryCoordinates coordinates;
        if (lookup.remoteUrl() == null) {
          if (!currentRepoResolved) {
            currentRepoCoordinates = findCoordinates(project, gitRepo);
            currentRepoResolved = true;
          }
          coordinates = currentRepoCoordinates;
        } else {
          if (!coordinatesByUrl.containsKey(lookup.remoteUrl())) {
            coordinatesByUrl.put(lookup.remoteUrl(), parseCoordinates(lookup.remoteUrl()));
          }
          coordinates = coordinatesByUrl.get(lookup.remoteUrl());
        }
        if (coordinates == null) {
          continue;
        }
        GithubServerPath server = coordinates.getServerPath();
        if (!executorByServer.containsKey(server)) {
          executorByServer.put(server, createExecutor(project, server));
        }
        GithubApiRequestExecutor executor = executorByServer.get(server);
        if (executor == null) {
          continue;
        }
        looked++;
        PrStatus status = lookupBranch(executor, server, coordinates.getRepositoryPath().toString(), lookup.branch());
        if (status != null) {
          result.put(lookup.worktreePath(), status);
        }
      }
      return result;
    } catch (Throwable t) {
      LOG.debug("PR status lookup failed", t);
      return Map.of();
    }
  }

  @Nullable
  private static GHRepositoryCoordinates parseCoordinates(@NotNull String remoteUrl) {
    try {
      GHRepositoryPath repoPath = GithubUrlUtil.getUserAndRepositoryFromRemoteUrl(remoteUrl);
      if (repoPath == null) {
        return null;
      }
      URI uri = GitHostingUrlUtil.getUriFromRemoteUrl(remoteUrl);
      String host = uri == null ? null : uri.getHost();
      if (host == null || host.isBlank()) {
        return null;
      }
      GithubServerPath server = GithubServerPath.DEFAULT_HOST.equalsIgnoreCase(host)
                                ? GithubServerPath.DEFAULT_SERVER
                                : new GithubServerPath(host);
      return new GHRepositoryCoordinates(server, repoPath);
    } catch (Throwable t) {
      LOG.debug("Cannot parse GitHub coordinates from remote " + remoteUrl, t);
      return null;
    }
  }

  @Nullable
  private static GithubApiRequestExecutor createExecutor(@NotNull Project project,
                                                         @NotNull GithubServerPath server) {
    String token = findToken(project, server);
    if (token == null || token.isBlank()) {
      LOG.info("PR status lookup skipped for " + server + ": no GitHub token available");
      return null;
    }
    return GithubApiRequestExecutor.Factory.getInstance().create(server, token);
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
      LOG.info("PR status lookup skipped: no GitHub accounts configured in this IDE");
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
