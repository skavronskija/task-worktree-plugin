package com.toxa.worktree.action;

import com.intellij.ui.JBColor;
import com.intellij.ui.RoundedLineBorder;
import com.intellij.ui.popup.list.ListPopupImpl;
import com.intellij.ui.popup.list.PopupListElementRenderer;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.toxa.worktree.action.OpenTaskInWorktreeAction.ExternalWorktreeEntry;
import com.toxa.worktree.action.OpenTaskInWorktreeAction.PickerEntry;
import com.toxa.worktree.action.OpenTaskInWorktreeAction.WorktreeEntry;
import com.toxa.worktree.service.PrStatusSupport.PrStatus;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import org.jetbrains.annotations.NotNull;

/**
 * Renders the worktree/task picker rows exactly like the platform default, plus an outlined PR
 * status badge ({@code DRAFT} / {@code OPEN} / {@code MERGED} / {@code CLOSED}) placed directly
 * after the row title: {@code <title> <badge>}. The {@code >} expansion arrow is left untouched at
 * the far right. Statuses come from a map populated before the popup is shown, so badges are present
 * from the first layout pass; rows without a known status show a blank placeholder.
 */
final class PrBadgeRenderer extends PopupListElementRenderer<PickerEntry> {

  private static final JBColor MERGED_COLOR = new JBColor(0x8957E5, 0xA371F7);
  private static final JBColor OPEN_COLOR = new JBColor(0x1A7F37, 0x3FB950);
  private static final JBColor DRAFT_COLOR = new JBColor(0x6E7781, 0x8B949E);
  private static final JBColor CLOSED_COLOR = new JBColor(0xCF222E, 0xF85149);

  private final Map<Path, PrStatus> statusByPath;
  private JLabel badgeLabel;

  PrBadgeRenderer(@NotNull ListPopupImpl popup, @NotNull Map<Path, PrStatus> statusByPath) {
    super(popup);
    this.statusByPath = statusByPath;
  }

  @Override
  protected JComponent createItemComponent() {
    JComponent component = super.createItemComponent();
    installBadge();
    return component;
  }

  @Override
  protected void customizeComponent(JList<? extends PickerEntry> list,
                                    PickerEntry value,
                                    boolean isSelected) {
    super.customizeComponent(list, value, isSelected);
    if (badgeLabel == null) {
      return;
    }
    PrStatus status = statusFor(value);
    if (status == null) {
      clearBadge();
    } else {
      applyStyle(status);
    }
  }

  private PrStatus statusFor(PickerEntry value) {
    if (value instanceof WorktreeEntry w && !w.main()) {
      return statusByPath.get(w.path());
    }
    if (value instanceof ExternalWorktreeEntry w) {
      return statusByPath.get(w.path());
    }
    return null;
  }

  /**
   * Adds the badge, right-aligned, into the expanding center region of the row — leaving the title
   * in its original left slot and the {@code >} arrow at the far right, so the row reads
   * {@code <title> .... <badge>} then {@code >}.
   */
  private void installBadge() {
    if (badgeLabel != null || myTextLabel == null) {
      return;
    }
    Container parent = myTextLabel.getParent();
    if (parent == null || !(parent.getLayout() instanceof BorderLayout layout)) {
      return;
    }

    badgeLabel = new JLabel();
    badgeLabel.setFont(JBFont.small());
    badgeLabel.setOpaque(false);
    badgeLabel.setHorizontalAlignment(SwingConstants.CENTER);

    // Reserve a fixed slot sized to the widest badge so the popup is laid out with room for the
    // badge from the first paint — the status arrives asynchronously, and without a reserved slot
    // the badge would have no space and stay clipped until a row is highlighted.
    Dimension reserved = new Dimension();
    for (PrStatus s : PrStatus.values()) {
      applyStyle(s);
      Dimension size = badgeLabel.getPreferredSize();
      reserved.width = Math.max(reserved.width, size.width);
      reserved.height = Math.max(reserved.height, size.height);
    }
    badgeLabel.setPreferredSize(reserved);
    badgeLabel.setMinimumSize(reserved);
    badgeLabel.setMaximumSize(reserved);
    clearBadge();

    // Replace the (unused) secondary-label panel in the center region with a wrapper that pins the
    // badge to its right edge, so the badge is right-aligned just before the arrow.
    Component center = layout.getLayoutComponent(BorderLayout.CENTER);
    if (center != null) {
      parent.remove(center);
    }
    JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.setOpaque(false);
    wrapper.add(badgeLabel, BorderLayout.EAST);
    parent.add(wrapper, BorderLayout.CENTER);
  }

  private void clearBadge() {
    badgeLabel.setText("");
    badgeLabel.setBorder(JBUI.Borders.empty());
  }

  private void applyStyle(@NotNull PrStatus status) {
    JBColor color = switch (status) {
      case MERGED -> MERGED_COLOR;
      case OPEN -> OPEN_COLOR;
      case DRAFT -> DRAFT_COLOR;
      case CLOSED -> CLOSED_COLOR;
    };
    badgeLabel.setText(status.name());
    badgeLabel.setForeground(color);
    Border outline = new RoundedLineBorder(color, JBUI.scale(8), JBUI.scale(1));
    Border padding = BorderFactory.createCompoundBorder(outline, JBUI.Borders.empty(0, 5));
    badgeLabel.setBorder(BorderFactory.createCompoundBorder(JBUI.Borders.empty(1, 8, 1, 0), padding));
  }
}
