package com.az.gitember.dialog;

import com.az.gitember.data.WorktreeInfo;
import com.az.gitember.service.Context;
import com.az.gitember.ui.misc.Util;
import is.codion.common.model.component.table.FilterTableModel.TableColumns;
import is.codion.common.model.worker.ProgressWorker;
import is.codion.common.reactive.state.ObservableState;
import is.codion.common.reactive.state.State;
import is.codion.swing.common.model.component.table.SwingFilterTableModel;
import is.codion.swing.common.ui.component.table.FilterTable;
import is.codion.swing.common.ui.control.Control;
import is.codion.swing.common.ui.control.Controls;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static is.codion.swing.common.ui.component.Components.*;

/**
 * Dialog for managing Git worktrees.
 * Shows a table of all linked worktrees and provides Add / Remove / Open / Prune actions.
 */
public class WorktreesDialog extends JDialog {

    private static final Logger log = Logger.getLogger(WorktreesDialog.class.getName());

    private static final String PATH   = "Path";
    private static final String BRANCH = "Branch";
    private static final String HEAD   = "HEAD";
    private static final String STATUS = "Status";

    private final SwingFilterTableModel<WorktreeInfo, String> model = SwingFilterTableModel.builder()
            .columns(new WorktreeColumns())
            .items(WorktreesDialog::listWorktrees)
            .onRefreshException(ex -> log.log(Level.WARNING, "Failed to list worktrees", ex))
            .build();
    private final State busy = State.state();
    private final State mainSelected = State.state();

    /** Set to the worktree the user wants to open; {@code null} if none chosen. */
    private WorktreeInfo selectedToOpen;

    public WorktreesDialog(Frame parent) {
        super(parent, "Worktrees", true);
        setSize(700, 350);
        setLocationRelativeTo(parent);
        setMinimumSize(new Dimension(500, 250));

        model.selection().item().addConsumer(wt -> mainSelected.set(wt != null && wt.isMain()));
        ObservableState selected = State.and(model.selection().present(), busy.not());

        Control add = Control.builder()
                .command(this::onAdd)
                .caption("Add…")
                .enabled(busy.not())
                .build();
        Control remove = Control.builder()
                .command(this::onRemove)
                .caption("Remove")
                .enabled(State.and(selected, mainSelected.not()))
                .build();
        Control open = Control.builder()
                .command(this::onOpen)
                .caption("Open")
                .enabled(selected)
                .build();
        Control prune = Control.builder()
                .command(this::onPrune)
                .caption("Prune")
                .enabled(busy.not())
                .build();

        getContentPane().setLayout(new BorderLayout(4, 4));
        getContentPane().add(scrollPane()
                .view(FilterTable.builder()
                        .model(model)
                        .columns(column -> column.preferredWidth(switch (column.identifier()) {
                            case PATH -> 280;
                            case BRANCH -> 160;
                            case HEAD -> 80;
                            default -> 60;
                        }))
                        .selectionMode(ListSelectionModel.SINGLE_SELECTION)
                        .autoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS)
                        .build())
                .build(), BorderLayout.CENTER);
        getContentPane().add(borderLayoutPanel()
                .layout(new BorderLayout())
                // the space of the status bar, whose label is not shown
                .border(BorderFactory.createEmptyBorder(0, 0, 6, 0))
                .north(flowLayoutPanel(FlowLayout.LEFT)
                        .add(buttonPanel()
                                .controls(Controls.builder()
                                        .control(add)
                                        .control(remove)
                                        .control(open))))
                                        // .control(prune)
                .east(flowLayoutPanel(FlowLayout.RIGHT)
                        .add(button()
                                .control(Control.builder()
                                        .command(this::dispose)
                                        .caption("Close"))))
                .build(), BorderLayout.SOUTH);

        Util.bindEscapeToDispose(this);
        model.items().refresh();
    }

    private static Collection<WorktreeInfo> listWorktrees() {
        try {
            return Context.getGitRepoService().listWorktrees();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void onAdd() {
        WorktreeCreateDialog dlg = new WorktreeCreateDialog(this, new ArrayList<>(model.items().get()));
        dlg.setVisible(true);
        if (dlg.isConfirmed()) {
            String path       = dlg.getPath();
            String branchName = dlg.getBranch();
            boolean newBranch = dlg.isNewBranch();

            ProgressWorker.builder()
                    .task(() -> Context.getGitRepoService().addWorktree(path, branchName, newBranch))
                    .onWorking(busy::set)
                    .onSuccess(model.items()::refresh)
                    .onException(ex -> failed("Add worktree failed", "Cannot add worktree", ex))
                    .execute();
        }
    }

    private void onRemove() {
        WorktreeInfo wt = model.selection().item().get();
        if (wt == null) return;
        if (wt.isMain()) {
            JOptionPane.showMessageDialog(this,
                    "The main worktree cannot be removed.",
                    "Remove Worktree", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int choice = JOptionPane.showConfirmDialog(this,
                "Remove worktree at:\n" + wt.getPath() + "\n\nForce remove if there are local changes?",
                "Remove Worktree",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);

        if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) return;
        boolean force = (choice == JOptionPane.YES_OPTION);

        ProgressWorker.builder()
                .task(() -> Context.getGitRepoService().removeWorktree(wt.getPath(), force))
                .onWorking(busy::set)
                .onSuccess(model.items()::refresh)
                .onException(ex -> failed("Remove worktree failed", "Cannot remove worktree", ex))
                .execute();
    }

    private void onOpen() {
        WorktreeInfo wt = model.selection().item().get();
        if (wt == null) return;
        selectedToOpen = wt;
        dispose();
    }

    private void onPrune() {
        ProgressWorker.builder()
                .task(() -> Context.getGitRepoService().pruneWorktrees())
                .onWorking(busy::set)
                .onSuccess(model.items()::refresh)
                .onException(ex -> log.log(Level.WARNING, "Prune worktrees failed", ex))
                .execute();
    }

    private void failed(String logMessage, String errorMessage, Exception ex) {
        log.log(Level.WARNING, logMessage, ex);
        JOptionPane.showMessageDialog(this,
                errorMessage + ":\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
    }

    /** The worktree the user chose to open, or {@code null}. */
    public WorktreeInfo getSelectedToOpen() {
        return selectedToOpen;
    }

    // ── Table columns ─────────────────────────────────────────────────────────

    private static final class WorktreeColumns implements TableColumns<WorktreeInfo, String> {

        private static final List<String> COLUMNS = List.of(PATH, BRANCH, HEAD, STATUS);

        @Override
        public List<String> identifiers() {
            return COLUMNS;
        }

        @Override
        public Class<?> type(String column) {
            return String.class;
        }

        @Override
        public Object value(WorktreeInfo wt, String column) {
            return switch (column) {
                case PATH   -> wt.getPath();
                case BRANCH -> wt.getBranch() != null ? wt.getBranch() : "(detached)";
                case HEAD   -> wt.getShortHead();
                case STATUS -> wt.isMain()     ? "main"
                             : wt.isLocked()   ? "locked"
                             : wt.isPrunable() ? "prunable"
                             :                   "";
                default -> "";
            };
        }
    }
}
