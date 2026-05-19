package ie.distilled.worktree.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import git4idea.validators.GitRefNameValidator;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CreateWorktreeDialog extends DialogWrapper {

  private final JBTextField branchField = new JBTextField();
  private final JBTextField folderField = new JBTextField();
  private final JBLabel pathPreview = new JBLabel();
  private final Path worktreesParent;

  public CreateWorktreeDialog(@NotNull Project project,
                              @NotNull String windowTitle,
                              @NotNull String defaultBranch,
                              @NotNull String defaultFolder,
                              @NotNull Path worktreesParent) {
    super(project);
    this.worktreesParent = worktreesParent;
    setTitle(windowTitle);
    branchField.setText(defaultBranch);
    folderField.setText(defaultFolder);
    folderField.addKeyListener(new KeyAdapter() {
      @Override
      public void keyReleased(KeyEvent e) {
        updatePathPreview();
      }
    });
    updatePathPreview();
    pathPreview.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
    init();
  }

  private void updatePathPreview() {
    String folder = folderField.getText() == null ? "" : folderField.getText().trim();
    pathPreview.setText(folder.isEmpty() ? " " : worktreesParent.resolve(folder).toString());
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel panel = FormBuilder.createFormBuilder()
                              .addLabeledComponent("Branch:", branchField)
                              .addLabeledComponent("Folder:", folderField)
                              .addLabeledComponent("Path:", pathPreview)
                              .getPanel();
    Dimension natural = panel.getPreferredSize();
    panel.setPreferredSize(new Dimension(natural.width * 3, natural.height));
    return panel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return folderField;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    String branch = getBranch();
    if (branch.isEmpty()) {
      return new ValidationInfo("Branch name is required", branchField);
    }
    if (!GitRefNameValidator.getInstance().checkInput(branch)) {
      return new ValidationInfo("Invalid branch name", branchField);
    }
    String folder = getFolder();
    if (folder.isEmpty()) {
      return new ValidationInfo("Folder name is required", folderField);
    }
    if (folder.contains("/") || folder.contains("\\")) {
      return new ValidationInfo("Folder name must not contain path separators", folderField);
    }
    return null;
  }

  @NotNull
  public String getBranch() {
    return branchField.getText() == null ? "" : branchField.getText().trim();
  }

  @NotNull
  public String getFolder() {
    return folderField.getText() == null ? "" : folderField.getText().trim();
  }
}
