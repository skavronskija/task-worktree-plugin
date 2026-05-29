package com.toxa.worktree.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.APP)
@State(name = "TaskWorktreeSettings", storages = @Storage("task-worktree.xml"))
public final class WorktreeSettings implements PersistentStateComponent<WorktreeSettings.State> {

  public static final String DEFAULT_BRANCH_NAME_PATTERN = "${id}";
  public static final String DEFAULT_WORKTREE_NAME_PATTERN = "${id}";

  public static final class State {
    public String baseDirectory = "";
    public boolean copyProjectConfig = true;
    public String branchNamePattern = DEFAULT_BRANCH_NAME_PATTERN;
    public String worktreeNamePattern = DEFAULT_WORKTREE_NAME_PATTERN;
  }

  private State state = new State();

  public static WorktreeSettings getInstance() {
    return ApplicationManager.getApplication().getService(WorktreeSettings.class);
  }

  @Override
  public @NotNull State getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    XmlSerializerUtil.copyBean(state, this.state);
  }

  @NotNull
  public String getBaseDirectory() {
    return state.baseDirectory == null ? "" : state.baseDirectory;
  }

  public void setBaseDirectory(@NotNull String value) {
    state.baseDirectory = value;
  }

  public boolean isCopyProjectConfig() {
    return state.copyProjectConfig;
  }

  public void setCopyProjectConfig(boolean value) {
    state.copyProjectConfig = value;
  }

  @NotNull
  public String getBranchNamePattern() {
    return state.branchNamePattern == null ? DEFAULT_BRANCH_NAME_PATTERN : state.branchNamePattern;
  }

  public void setBranchNamePattern(@NotNull String value) {
    state.branchNamePattern = value;
  }

  @NotNull
  public String getWorktreeNamePattern() {
    return state.worktreeNamePattern == null ? DEFAULT_WORKTREE_NAME_PATTERN : state.worktreeNamePattern;
  }

  public void setWorktreeNamePattern(@NotNull String value) {
    state.worktreeNamePattern = value;
  }
}
