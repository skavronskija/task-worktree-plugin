package com.toxa.worktree.settings;

import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.RecentProjectsManagerBase;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class WorktreeSettingsConfigurable implements Configurable {

  private static final int PREVIEW_LIMIT = 20;

  private static final String VARIABLES_HINT = """
      <html>Pattern variables:<br>
      &nbsp;&nbsp;${id} - task ID<br>
      &nbsp;&nbsp;${number} - task number<br>
      &nbsp;&nbsp;${type} - task type (e.g. bug, feature)<br>
      &nbsp;&nbsp;${summary} - task summary<br>
      &nbsp;&nbsp;${project} - current project/repo name<br>
      <br>Leave a pattern blank to use the task id.</html>""";

  private static final String COPY_PATTERNS_HINT = """
      <html>Copy un-tracked files from the main repo into each new worktree.<br>
      Paths are relative to the repo root; glob syntax is supported<br>
      (e.g. <code>.env</code>, <code>config/**</code>, <code>*.local.properties</code>).<br>
      Directories are copied recursively; patterns matching nothing are skipped.</html>""";

  private TextFieldWithBrowseButton baseDirField;
  private JBCheckBox copyConfigCheckbox;
  private JBTextField branchPatternField;
  private JBTextField worktreePatternField;
  private JBTable typeMappingTable;
  private DefaultTableModel typeMappingModel;
  private JBTable copyPatternTable;
  private DefaultTableModel copyPatternModel;
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

    JBLabel hint = hintLabel("Leave blank to use <repo parent>/worktrees. Relative paths resolve against the repo parent.");

    branchPatternField = new JBTextField();
    worktreePatternField = new JBTextField();

    JBLabel variablesHint = hintLabel(VARIABLES_HINT);

    JButton cleanupButton = new JButton("Clean Up Obsolete Recent Projects");
    cleanupButton.addActionListener(e -> cleanUpObsoleteRecentProjects());

    JBLabel mappingHint = hintLabel("Remap resolved ${type} values, e.g. map \"other\" to \"feature\".");

    JBLabel copyPatternHint = hintLabel(COPY_PATTERNS_HINT);

    panel = FormBuilder.createFormBuilder()
                       .addLabeledComponent("Base worktrees directory:", baseDirField)
                       .addComponentToRightColumn(hint)
                       .addLabeledComponent("Branch name pattern:", branchPatternField)
                       .addLabeledComponent("Worktree folder name pattern:", worktreePatternField)
                       .addComponent(copyConfigCheckbox)
                       .addSeparator()
                       .addComponent(variablesHint)
                       .addSeparator()
                       .addComponent(leftLabel("Task type mappings:"))
                       .addComponent(mappingHint)
                       .addComponent(createTypeMappingPanel())
                       .addSeparator()
                       .addComponent(leftLabel("Additional files to copy:"))
                       .addComponent(copyPatternHint)
                       .addComponentFillVertically(createCopyPatternPanel(), 0)
                       .addSeparator()
                       .addComponent(cleanupButton)
                       .getPanel();

    reset();
    return panel;
  }

  @NotNull
  private static JBLabel leftLabel(@NotNull String text) {
    JBLabel label = new JBLabel(text);
    label.setHorizontalAlignment(SwingConstants.LEFT);
    label.setAlignmentX(0f);
    return label;
  }

  @NotNull
  private static JBLabel hintLabel(@NotNull String text) {
    JBLabel label = leftLabel(text);
    label.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
    return label;
  }

  private JComponent createTypeMappingPanel() {
    typeMappingModel = new DefaultTableModel(new Object[]{"Task type", "Mapped to"}, 0);
    typeMappingTable = new JBTable(typeMappingModel);
    typeMappingTable.setVisibleRowCount(4);
    return ToolbarDecorator.createDecorator(typeMappingTable)
                           .setAddAction(b -> {
                             stopTableEditing();
                             typeMappingModel.addRow(new Object[]{"", ""});
                           })
                           .setRemoveAction(b -> {
                             stopTableEditing();
                             int row = typeMappingTable.getSelectedRow();
                             if (row >= 0) {
                               typeMappingModel.removeRow(row);
                             }
                           })
                           .createPanel();
  }

  private JComponent createCopyPatternPanel() {
    copyPatternModel = new DefaultTableModel(new Object[]{"Path / glob pattern"}, 0);
    copyPatternTable = new JBTable(copyPatternModel);
    copyPatternTable.setVisibleRowCount(4);
    return ToolbarDecorator.createDecorator(copyPatternTable)
                           .setAddAction(b -> {
                             stopTableEditing();
                             copyPatternModel.addRow(new Object[]{""});
                             editLastRow(copyPatternTable);
                           })
                           .setRemoveAction(b -> {
                             stopTableEditing();
                             int row = copyPatternTable.getSelectedRow();
                             if (row >= 0) {
                               copyPatternModel.removeRow(row);
                             }
                           })
                           .createPanel();
  }

  private static void editLastRow(@NotNull JBTable table) {
    int row = table.getRowCount() - 1;
    if (row >= 0) {
      table.editCellAt(row, 0);
      table.changeSelection(row, 0, false, false);
      if (table.getEditorComponent() != null) {
        table.getEditorComponent().requestFocusInWindow();
      }
    }
  }

  // Reads the current value of a cell, preferring the live editor value when the cell is being
  // edited. Crucially this never stops the editor: stopping it from isModified() (which the
  // settings dialog polls on a timer) would drop focus mid-typing.
  @NotNull
  private static String cellValue(@NotNull JBTable table, @NotNull DefaultTableModel model, int row, int column) {
    if (table.isEditing() && table.getEditingRow() == row && table.getEditingColumn() == column) {
      Object editorValue = table.getCellEditor().getCellEditorValue();
      return editorValue == null ? "" : editorValue.toString().trim();
    }
    Object value = model.getValueAt(row, column);
    return value == null ? "" : value.toString().trim();
  }

  private void stopTableEditing() {
    if (typeMappingTable != null && typeMappingTable.isEditing()) {
      typeMappingTable.getCellEditor().stopCellEditing();
    }
    if (copyPatternTable != null && copyPatternTable.isEditing()) {
      copyPatternTable.getCellEditor().stopCellEditing();
    }
  }

  @NotNull
  private List<String> readCopyPatterns() {
    List<String> result = new ArrayList<>();
    for (int row = 0; row < copyPatternModel.getRowCount(); row++) {
      String value = cellValue(copyPatternTable, copyPatternModel, row, 0);
      if (!value.isEmpty() && !result.contains(value)) {
        result.add(value);
      }
    }
    return result;
  }

  @NotNull
  private Map<String, String> readTypeMappings() {
    Map<String, String> result = new LinkedHashMap<>();
    for (int row = 0; row < typeMappingModel.getRowCount(); row++) {
      String key = cellValue(typeMappingTable, typeMappingModel, row, 0);
      String value = cellValue(typeMappingTable, typeMappingModel, row, 1);
      if (!key.isEmpty() && !value.isEmpty()) {
        result.put(key, value);
      }
    }
    return result;
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
           || !worktreePatternField.getText().trim().equals(s.getWorktreeNamePattern())
           || !readTypeMappings().equals(s.getTaskTypeMappings())
           || !readCopyPatterns().equals(s.getAdditionalCopyPatterns());
  }

  @Override
  public void apply() {
    WorktreeSettings s = WorktreeSettings.getInstance();
    s.setBaseDirectory(baseDirField.getText().trim());
    s.setCopyProjectConfig(copyConfigCheckbox.isSelected());
    s.setBranchNamePattern(branchPatternField.getText().trim());
    s.setWorktreeNamePattern(worktreePatternField.getText().trim());
    s.setTaskTypeMappings(readTypeMappings());
    s.setAdditionalCopyPatterns(readCopyPatterns());
  }

  @Override
  public void reset() {
    WorktreeSettings s = WorktreeSettings.getInstance();
    baseDirField.setText(s.getBaseDirectory());
    copyConfigCheckbox.setSelected(s.isCopyProjectConfig());
    branchPatternField.setText(s.getBranchNamePattern());
    worktreePatternField.setText(s.getWorktreeNamePattern());
    stopTableEditing();
    typeMappingModel.setRowCount(0);
    for (Map.Entry<String, String> entry : s.getTaskTypeMappings().entrySet()) {
      typeMappingModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
    }
    copyPatternModel.setRowCount(0);
    for (String pattern : s.getAdditionalCopyPatterns()) {
      copyPatternModel.addRow(new Object[]{pattern});
    }
  }

  @Override
  public void disposeUIResources() {
    panel = null;
    baseDirField = null;
    copyConfigCheckbox = null;
    branchPatternField = null;
    worktreePatternField = null;
    typeMappingTable = null;
    typeMappingModel = null;
    copyPatternTable = null;
    copyPatternModel = null;
  }
}
