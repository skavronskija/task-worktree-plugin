package com.toxa.worktree.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import git4idea.validators.GitRefNameValidator;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CreateWorktreeDialog extends DialogWrapper {

  private final JBRadioButton newBranchRadio = new JBRadioButton("New branch", true);
  private final JBRadioButton existingBranchRadio = new JBRadioButton("Existing branch");
  private final JBTextField branchField = new JBTextField();
  private final JComboBox<String> existingBranchCombo = new JComboBox<>();
  private final DefaultComboBoxModel<String> existingBranchModel = new DefaultComboBoxModel<>();
  private final List<String> allBranches;
  private final JBTextField folderField = new JBTextField();
  private final JBLabel pathPreview = new JBLabel();
  private final Path worktreesParent;
  private boolean folderFollowsBranch;
  private boolean settingFolderProgrammatically;

  public CreateWorktreeDialog(@NotNull Project project,
                              @NotNull String windowTitle,
                              @NotNull String defaultBranch,
                              @NotNull String defaultFolder,
                              @NotNull Path worktreesParent,
                              @NotNull List<String> existingBranches) {
    super(project);
    this.worktreesParent = worktreesParent;
    this.allBranches = List.copyOf(existingBranches);
    setTitle(windowTitle);

    this.folderFollowsBranch = defaultFolder.isEmpty();
    branchField.setText(defaultBranch);
    folderField.setText(defaultFolder);
    folderField.addKeyListener(new KeyAdapter() {
      @Override
      public void keyReleased(KeyEvent e) {
        if (!settingFolderProgrammatically) {
          folderFollowsBranch = false;
        }
        updatePathPreview();
      }
    });
    branchField.addKeyListener(new KeyAdapter() {
      @Override
      public void keyReleased(KeyEvent e) {
        if (newBranchRadio.isSelected()) {
          syncFolderFromBranch();
        }
      }
    });

    for (String b : allBranches) {
      existingBranchModel.addElement(b);
    }
    existingBranchCombo.setModel(existingBranchModel);
    existingBranchCombo.setEditable(true);
    existingBranchCombo.setSelectedItem(null);
    existingBranchCombo.addActionListener(e -> {
      if (existingBranchRadio.isSelected()) {
        syncFolderFromBranch();
      }
    });
    JTextField comboEditor = (JTextField) existingBranchCombo.getEditor().getEditorComponent();
    comboEditor.setText("");
    comboEditor.addKeyListener(new KeyAdapter() {
      @Override
      public void keyReleased(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN
            || code == KeyEvent.VK_ENTER || code == KeyEvent.VK_ESCAPE
            || code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT
            || code == KeyEvent.VK_TAB || code == KeyEvent.VK_HOME
            || code == KeyEvent.VK_END) {
          return;
        }
        applyBranchFilter();
        if (existingBranchRadio.isSelected()) {
          syncFolderFromBranch();
        }
      }
    });
    if (allBranches.isEmpty()) {
      existingBranchRadio.setEnabled(false);
    }

    ButtonGroup group = new ButtonGroup();
    group.add(newBranchRadio);
    group.add(existingBranchRadio);
    newBranchRadio.addItemListener(e -> updateMode());
    existingBranchRadio.addItemListener(e -> updateMode());
    updateMode();

    updatePathPreview();
    pathPreview.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
    init();
  }

  private void updateMode() {
    boolean useNew = newBranchRadio.isSelected();
    branchField.setEnabled(useNew);
    existingBranchCombo.setEnabled(!useNew && !allBranches.isEmpty());
    syncFolderFromBranch();
  }

  private void syncFolderFromBranch() {
    if (!folderFollowsBranch) {
      return;
    }
    String branch = getBranch();
    String cleaned = branch.replace('/', '-').replace('\\', '-');
    if (folderField.getText().equals(cleaned)) {
      return;
    }
    settingFolderProgrammatically = true;
    try {
      folderField.setText(cleaned);
    } finally {
      settingFolderProgrammatically = false;
    }
    updatePathPreview();
  }

  private void applyBranchFilter() {
    JTextField editor = (JTextField) existingBranchCombo.getEditor().getEditorComponent();
    String text = editor.getText();
    String needle = text.toLowerCase(Locale.ROOT);
    int caret = editor.getCaretPosition();

    existingBranchModel.removeAllElements();
    for (String b : allBranches) {
      if (b.toLowerCase(Locale.ROOT).contains(needle)) {
        existingBranchModel.addElement(b);
      }
    }
    editor.setText(text);
    try {
      editor.setCaretPosition(Math.min(caret, text.length()));
    } catch (IllegalArgumentException ignore) {
    }

    if (existingBranchModel.getSize() > 0) {
      if (!existingBranchCombo.isPopupVisible()) {
        existingBranchCombo.showPopup();
      }
    } else if (existingBranchCombo.isPopupVisible()) {
      existingBranchCombo.hidePopup();
    }
  }

  private void updatePathPreview() {
    String folder = folderField.getText() == null ? "" : folderField.getText().trim();
    pathPreview.setText(folder.isEmpty() ? " " : worktreesParent.resolve(folder).toString());
  }

  @Override
  protected JComponent createCenterPanel() {
    return FormBuilder.createFormBuilder()
                      .addLabeledComponent(newBranchRadio, branchField)
                      .addLabeledComponent(existingBranchRadio, existingBranchCombo)
                      .addLabeledComponent("Folder:", folderField)
                      .addLabeledComponent("Path:", pathPreview)
                      .getPanel();
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return branchField.getText().isEmpty() ? branchField : folderField;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    if (newBranchRadio.isSelected()) {
      String branch = getBranch();
      if (branch.isEmpty()) {
        return new ValidationInfo("Branch name is required", branchField);
      }
      if (!GitRefNameValidator.getInstance().checkInput(branch)) {
        return new ValidationInfo("Invalid branch name", branchField);
      }
    } else {
      String branch = getBranch();
      if (branch.isEmpty()) {
        return new ValidationInfo("Choose an existing branch", existingBranchCombo);
      }
      if (!allBranches.contains(branch)) {
        return new ValidationInfo("Branch '" + branch + "' does not exist", existingBranchCombo);
      }
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
    if (newBranchRadio.isSelected()) {
      return branchField.getText() == null ? "" : branchField.getText().trim();
    }
    Object editorItem = existingBranchCombo.getEditor().getItem();
    return editorItem == null ? "" : editorItem.toString().trim();
  }

  @NotNull
  public String getFolder() {
    return folderField.getText() == null ? "" : folderField.getText().trim();
  }
}
