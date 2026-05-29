package com.toxa.worktree.settings;

import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.RecentProjectsManagerBase;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public class WorktreeSettingsConfigurable implements Configurable {

  private static final int PREVIEW_LIMIT = 20;

  private static final String VARIABLES_HINT =
      "<html>Pattern variables:<br>"
      + "&nbsp;&nbsp;${id} - task ID<br>"
      + "&nbsp;&nbsp;${number} - task number<br>"
      + "&nbsp;&nbsp;${summary} - task summary<br>"
      + "&nbsp;&nbsp;${project} - current project/repo name<br>"
      + "<br>Leave a pattern blank to use the task id.</html>";

  private TextFieldWithBrowseButton baseDirField;
  private JBCheckBox copyConfigCheckbox;
  private JBTextField branchPatternField;
  private JBTextField worktreePatternField;
  private JPanel panel;

  @Override
  public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
    return "Task Worktree";
  }

  @Override
  public @Nullable JComponent createComponent() {
    baseDirField = new TextFieldWithBrowseButton();
    baseDirField.addBrowseFolderListener(
        null,
        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                                    .withTitle("Select Base Worktrees Directory")
                                    .withDescription("Leave blank to use <repo parent>/worktrees")
    );

    copyConfigCheckbox = new JBCheckBox("Copy project configuration (.idea) to new worktrees");

    JBLabel hint = new JBLabel("Leave blank to use <repo parent>/worktrees. Relative paths resolve against the repo parent.");
    hint.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);

    branchPatternField = new JBTextField();
    worktreePatternField = new JBTextField();

    JBLabel variablesHint = new JBLabel(VARIABLES_HINT);
    variablesHint.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);

    JButton cleanupButton = new JButton("Clean Up Obsolete Recent Projects");
    cleanupButton.addActionListener(e -> cleanUpObsoleteRecentProjects());

    panel = FormBuilder.createFormBuilder()
                       .addLabeledComponent("Base worktrees directory:", baseDirField)
                       .addComponentToRightColumn(hint)
                       .addLabeledComponent("Branch name pattern:", branchPatternField)
                       .addLabeledComponent("Worktree folder name pattern:", worktreePatternField)
                       .addComponent(copyConfigCheckbox)
                       .addSeparator()
                       .addComponent(variablesHint)
                       .addSeparator()
                       .addComponent(cleanupButton)
                       .addComponentFillVertically(new JPanel(), 0)
                       .getPanel();

    reset();
    return panel;
  }

  private void cleanUpObsoleteRecentProjects() {
    RecentProjectsManager manager = RecentProjectsManager.getInstance();
    if (!(manager instanceof RecentProjectsManagerBase base)) {
      Messages.showErrorDialog(
          "Unable to access recent projects list (unexpected manager type).",
          "Task Worktree"
      );
      return;
    }
    List<String> obsolete = base.getRecentPaths().stream()
                                .filter(p -> !Files.isDirectory(Path.of(p)))
                                .toList();
    if (obsolete.isEmpty()) {
      Messages.showInfoMessage("No obsolete recent projects found.", "Task Worktree");
      return;
    }

    String preview = obsolete.stream()
                             .limit(PREVIEW_LIMIT)
                             .reduce((a, b) -> a + "\n" + b)
                             .orElse("");
    if (obsolete.size() > PREVIEW_LIMIT) {
      preview += "\n… and " + (obsolete.size() - PREVIEW_LIMIT) + " more";
    }

    int answer = Messages.showYesNoDialog(
        panel,
        "Remove " + obsolete.size() + " obsolete recent project(s)?\n\n" + preview,
        "Clean Up Recent Projects",
        Messages.getQuestionIcon()
    );
    if (answer != Messages.YES) {
      return;
    }
    for (String p : obsolete) {
      manager.removePath(p);
    }
    Messages.showInfoMessage(
        "Removed " + obsolete.size() + " obsolete recent project entry(s).",
        "Task Worktree"
    );
  }

  @Override
  public boolean isModified() {
    WorktreeSettings s = WorktreeSettings.getInstance();
    return !baseDirField.getText().trim().equals(s.getBaseDirectory())
           || copyConfigCheckbox.isSelected() != s.isCopyProjectConfig()
           || !branchPatternField.getText().trim().equals(s.getBranchNamePattern())
           || !worktreePatternField.getText().trim().equals(s.getWorktreeNamePattern());
  }

  @Override
  public void apply() {
    WorktreeSettings s = WorktreeSettings.getInstance();
    s.setBaseDirectory(baseDirField.getText().trim());
    s.setCopyProjectConfig(copyConfigCheckbox.isSelected());
    s.setBranchNamePattern(branchPatternField.getText().trim());
    s.setWorktreeNamePattern(worktreePatternField.getText().trim());
  }

  @Override
  public void reset() {
    WorktreeSettings s = WorktreeSettings.getInstance();
    baseDirField.setText(s.getBaseDirectory());
    copyConfigCheckbox.setSelected(s.isCopyProjectConfig());
    branchPatternField.setText(s.getBranchNamePattern());
    worktreePatternField.setText(s.getWorktreeNamePattern());
  }

  @Override
  public void disposeUIResources() {
    panel = null;
    baseDirField = null;
    copyConfigCheckbox = null;
    branchPatternField = null;
    worktreePatternField = null;
  }
}
