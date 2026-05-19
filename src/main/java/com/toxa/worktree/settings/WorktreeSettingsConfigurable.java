package com.toxa.worktree.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public class WorktreeSettingsConfigurable implements Configurable {

  private TextFieldWithBrowseButton baseDirField;
  private JBCheckBox copyConfigCheckbox;
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

    panel = FormBuilder.createFormBuilder()
                       .addLabeledComponent("Base worktrees directory:", baseDirField)
                       .addComponentToRightColumn(hint)
                       .addComponent(copyConfigCheckbox)
                       .addComponentFillVertically(new JPanel(), 0)
                       .getPanel();

    reset();
    return panel;
  }

  @Override
  public boolean isModified() {
    WorktreeSettings s = WorktreeSettings.getInstance();
    return !baseDirField.getText().trim().equals(s.getBaseDirectory())
           || copyConfigCheckbox.isSelected() != s.isCopyProjectConfig();
  }

  @Override
  public void apply() {
    WorktreeSettings s = WorktreeSettings.getInstance();
    s.setBaseDirectory(baseDirField.getText().trim());
    s.setCopyProjectConfig(copyConfigCheckbox.isSelected());
  }

  @Override
  public void reset() {
    WorktreeSettings s = WorktreeSettings.getInstance();
    baseDirField.setText(s.getBaseDirectory());
    copyConfigCheckbox.setSelected(s.isCopyProjectConfig());
  }

  @Override
  public void disposeUIResources() {
    panel = null;
    baseDirField = null;
    copyConfigCheckbox = null;
  }
}
