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
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
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
  private final JComboBox<String> baseCombo = new JComboBox<>();
  private final DefaultComboBoxModel<String> baseModel = new DefaultComboBoxModel<>();
  private final List<String> allBranches;
  private final JBTextField folderField = new JBTextField();
  private final JBLabel pathPreview = new JBLabel();
  private final Path worktreesParent;
  private final boolean branchPrefilled;
  private boolean folderFollowsBranch;
  private boolean settingFolderProgrammatically;

  public CreateWorktreeDialog(@NotNull Project project,
                              @NotNull String windowTitle,
                              @NotNull String defaultBranch,
                              @NotNull String defaultFolder,
                              @NotNull String defaultBase,
                              @NotNull Path worktreesParent,
                              @NotNull List<String> existingBranches) {
    super(project);
    this.worktreesParent = worktreesParent;
    this.allBranches = List.copyOf(existingBranches);
    this.branchPrefilled = !defaultBranch.isEmpty();
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

    new BranchComboFilter(existingBranchCombo, existingBranchModel, "", () -> {
      if (existingBranchRadio.isSelected()) {
        syncFolderFromBranch();
      }
    });
    if (allBranches.isEmpty()) {
      existingBranchRadio.setEnabled(false);
    }

    new BranchComboFilter(baseCombo, baseModel, defaultBase, () -> {
    });

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

  /**
   * Wires speed-filter behavior onto an editable combo: typing narrows the list to matching branches,
   * starting to type over a committed selection replaces it instead of appending, and {@code Esc}
   * while editing restores the last committed value rather than closing the dialog.
   */
  private final class BranchComboFilter {

    private final JComboBox<String> combo;
    private final DefaultComboBoxModel<String> model;
    private final JTextField editor;
    private final Runnable onChange;
    private String committedText;
    private boolean replaceOnType = true;
    private boolean filtering;

    BranchComboFilter(@NotNull JComboBox<String> combo,
                      @NotNull DefaultComboBoxModel<String> model,
                      @NotNull String initial,
                      @NotNull Runnable onChange) {
      this.combo = combo;
      this.model = model;
      this.editor = (JTextField) combo.getEditor().getEditorComponent();
      this.onChange = onChange;
      this.committedText = initial;

      for (String b : allBranches) {
        model.addElement(b);
      }
      combo.setModel(model);
      combo.setEditable(true);
      if (initial.isEmpty()) {
        combo.setSelectedItem(null);
      }
      editor.setText(initial);

      combo.addActionListener(e -> {
        if (filtering) {
          return;
        }
        committedText = editorText();
        replaceOnType = true;
        onChange.run();
      });

      editor.addFocusListener(new FocusAdapter() {
        @Override
        public void focusGained(FocusEvent e) {
          replaceOnType = true;
          editor.selectAll();
        }
      });

      editor.addKeyListener(new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent e) {
          if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            if (!editorText().equals(committedText)) {
              restoreCommitted();
              e.consume();
            }
            return;
          }
          if (replaceOnType && isReplaceTrigger(e)) {
            replaceOnType = false;
            editor.selectAll();
          }
        }

        @Override
        public void keyReleased(KeyEvent e) {
          if (isNavigationKey(e.getKeyCode())) {
            return;
          }
          applyFilter();
          onChange.run();
        }
      });
    }

    private String editorText() {
      Object item = combo.getEditor().getItem();
      return item == null ? "" : item.toString();
    }

    private void restoreCommitted() {
      filtering = true;
      try {
        model.removeAllElements();
        for (String b : allBranches) {
          model.addElement(b);
        }
        editor.setText(committedText);
        editor.selectAll();
        replaceOnType = true;
      } finally {
        filtering = false;
      }
      if (combo.isPopupVisible()) {
        combo.hidePopup();
      }
      onChange.run();
    }

    private void applyFilter() {
      String text = editor.getText();
      String needle = text.toLowerCase(Locale.ROOT);
      int caret = editor.getCaretPosition();

      filtering = true;
      try {
        model.removeAllElements();
        for (String b : allBranches) {
          if (b.toLowerCase(Locale.ROOT).contains(needle)) {
            model.addElement(b);
          }
        }
        editor.setText(text);
      } finally {
        filtering = false;
      }
      try {
        editor.setCaretPosition(Math.min(caret, text.length()));
      } catch (IllegalArgumentException ignore) {
      }

      if (model.getSize() > 0) {
        if (!combo.isPopupVisible()) {
          combo.showPopup();
        }
      } else if (combo.isPopupVisible()) {
        combo.hidePopup();
      }
    }
  }

  private static boolean isNavigationKey(int code) {
    return code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN
           || code == KeyEvent.VK_ENTER || code == KeyEvent.VK_ESCAPE
           || code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT
           || code == KeyEvent.VK_TAB || code == KeyEvent.VK_HOME
           || code == KeyEvent.VK_END;
  }

  private static boolean isReplaceTrigger(@NotNull KeyEvent e) {
    if (e.isActionKey() || e.isControlDown() || e.isMetaDown() || e.isAltDown()) {
      return false;
    }
    return switch (e.getKeyCode()) {
      case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH,
           KeyEvent.VK_META, KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE,
           KeyEvent.VK_TAB -> false;
      default -> true;
    };
  }

  private void updateMode() {
    boolean useNew = newBranchRadio.isSelected();
    branchField.setEnabled(useNew);
    baseCombo.setEnabled(useNew && !allBranches.isEmpty());
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

  private void updatePathPreview() {
    String folder = folderField.getText() == null ? "" : folderField.getText().trim();
    pathPreview.setText(folder.isEmpty() ? " " : worktreesParent.resolve(folder).toString());
  }

  @Override
  protected JComponent createCenterPanel() {
    return FormBuilder.createFormBuilder()
                      .addLabeledComponent(newBranchRadio, branchField)
                      .addLabeledComponent("Base:", baseCombo)
                      .addLabeledComponent(existingBranchRadio, existingBranchCombo)
                      .addLabeledComponent("Folder:", folderField)
                      .addLabeledComponent("Path:", pathPreview)
                      .getPanel();
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    if (branchField.getText().isEmpty()) {
      return branchField;
    }
    return branchPrefilled ? baseCombo : folderField;
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
      String base = getBaseRef();
      if (base.isEmpty()) {
        return new ValidationInfo("Base is required", baseCombo);
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

  public boolean isNewBranch() {
    return newBranchRadio.isSelected();
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
  public String getBaseRef() {
    Object editorItem = baseCombo.getEditor().getItem();
    return editorItem == null ? "" : editorItem.toString().trim();
  }

  @NotNull
  public String getFolder() {
    return folderField.getText() == null ? "" : folderField.getText().trim();
  }
}
