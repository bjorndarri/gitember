package com.az.gitember.ui;

import com.az.gitember.data.ScmReflogEntry;
import com.az.gitember.service.Context;
import com.az.gitember.service.GitemberUtil;
import com.az.gitember.ui.misc.Util;
import is.codion.common.model.component.table.FilterTableModel.TableColumns;
import is.codion.common.model.worker.ProgressWorker;
import is.codion.common.reactive.value.Value;
import is.codion.swing.common.model.component.table.SwingFilterTableModel;
import is.codion.swing.common.ui.Utilities;
import is.codion.swing.common.ui.component.table.FilterTable;
import is.codion.swing.common.ui.control.Control;
import is.codion.swing.common.ui.control.Controls;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import static is.codion.swing.common.ui.component.Components.*;

/**
 * Lists Git reflog entries and offers recover actions for deleted branches,
 * hard resets, deleted commits, and dropped stashes.
 */
public class ReflogPanel extends JPanel {

    private static final Logger log = Logger.getLogger(ReflogPanel.class.getName());

    private static final String SELECTOR = "Selector";
    private static final String ACTION   = "Action";
    private static final String COMMIT   = "Commit";
    private static final String WHEN     = "When";
    private static final String MESSAGE  = "Message";

    private final StatusBar statusBar;
    private final SwingFilterTableModel<ScmReflogEntry, String> model;
    private final FilterTable<ScmReflogEntry, String> table;
    private final JButton refreshBtn;
    private final JTextField searchField;
    private final Value<String> filter = Value.nonNull("");

    public ReflogPanel(StatusBar statusBar) {
        this.statusBar = statusBar;
        setLayout(new BorderLayout());

        refreshBtn = Util.createButton("Refresh", "Reload reflog", FontAwesomeSolid.SYNC);
        refreshBtn.addActionListener(e -> reload());

        searchField = stringField()
                .columns(15)
                .preferredSize(new Dimension(150, 25))
                .minimumSize(new Dimension(100, 25))
                .maximumSize(new Dimension(150, 25))
                .hint("Filter reflog...")
                .link(filter)
                .build();

        model = SwingFilterTableModel.builder()
                .columns(new ReflogColumns())
                .items(() -> Context.getGitRepoService().getReflogEntries())
                .onRefreshException(ex -> {
                    log.log(Level.WARNING, "Cannot load reflog", ex);
                    statusBar.setStatus("Cannot load reflog: " + ex);
                })
                .build();
        filter.addConsumer(text -> model.items().included().predicate().set(filterPredicate(text)));

        Controls menuControls = Controls.builder()
                .control(Control.builder()
                        .command(() -> recoverDeletedBranch(selected()))
                        .caption("Recover deleted branch...")
                        .enabled(model.selection().present()))
                .control(Control.builder()
                        .command(() -> recoverHardReset(selected()))
                        .caption("Recover hard reset...")
                        .enabled(model.selection().present()))
                .control(Control.builder()
                        .command(() -> recoverDeletedCommit(selected()))
                        .caption("Recover deleted commit...")
                        .enabled(model.selection().present()))
                .control(Control.builder()
                        .command(() -> recoverStash(selected()))
                        .caption("Recover stash...")
                        .enabled(model.selection().present()))
                .separator()
                .control(Control.builder()
                        .command(() -> copySha(selected()))
                        .caption("Copy commit SHA")
                        .enabled(model.selection().present()))
                .build();

        table = FilterTable.builder()
                .model(model)
                .columns(column -> column.preferredWidth(switch (column.identifier()) {
                    case SELECTOR, WHEN -> 140;
                    case ACTION, COMMIT -> 80;
                    default -> 420;
                }))
                .name("reflogTable")
                .selectionMode(ListSelectionModel.SINGLE_SELECTION)
                .rowHeight(24)
                .showGrid(false)
                .autoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN)
                .sortable(false)
                .popupControls(t -> menuControls)
                .build();

        add(scrollPane()
                .view(table)
                .build(), BorderLayout.CENTER);
    }

    public JButton getRefreshBtn() {
        return refreshBtn;
    }

    public JTextField getSearchField() {
        return searchField;
    }

    public void dispose() {
        model.items().clear();
    }

    public void reload() {
        statusBar.setStatus("Loading reflog...");
        model.items().refresh(entries ->
                statusBar.setStatus("Reflog: " + model.items().included().size() + " entries"));
    }

    private ScmReflogEntry selected() {
        return model.selection().item().get();
    }

    private void copySha(ScmReflogEntry entry) {
        String sha = entry.recoveryCommitId();
        if (sha != null && !sha.isBlank()) {
            Utilities.setClipboard(sha);
            statusBar.setStatus("Copied " + entry.getShortNewId());
        }
    }

    private void recoverDeletedBranch(ScmReflogEntry entry) {
        String suggested = entry.suggestedBranchName();
        String name = JOptionPane.showInputDialog(this,
                "Recreate local branch at " + shortOrEmpty(entry.recoveryCommitId()) + ":",
                suggested);
        if (name != null && !name.isBlank()) {
            runRecover("Recover deleted branch", () -> {
                Context.getGitRepoService().recoverDeletedBranch(name.trim(), entry.recoveryCommitId());
                Context.updateBranches();
                return "Recovered branch '" + name.trim() + "'";
            });
        }
    }

    private void recoverHardReset(ScmReflogEntry entry) {
        String sha = entry.recoveryCommitId();
        int ok = JOptionPane.showConfirmDialog(this,
                "Hard-reset HEAD and the working tree to " + shortOrEmpty(sha) + "?\n"
                        + "Uncommitted changes will be lost.",
                "Recover hard reset",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.OK_OPTION) {
            runRecover("Recover hard reset", () -> {
                Context.getGitRepoService().recoverHardReset(sha, null);
                Context.updateWorkingBranch();
                Context.updateStatus(null, true);
                return "Reset to " + shortOrEmpty(sha);
            });
        }
    }

    private void recoverDeletedCommit(ScmReflogEntry entry) {
        String sha = entry.getNewId();
        String suggested = "recovered-" + (sha != null && sha.length() >= 7 ? sha.substring(0, 7) : "commit");
        String name = JOptionPane.showInputDialog(this,
                "Create a branch pointing at commit " + shortOrEmpty(sha) + ":",
                suggested);
        if (name != null && !name.isBlank()) {
            runRecover("Recover deleted commit", () -> {
                Context.getGitRepoService().recoverDeletedCommit(name.trim(), sha);
                Context.updateBranches();
                return "Created branch '" + name.trim() + "' at " + shortOrEmpty(sha);
            });
        }
    }

    private void recoverStash(ScmReflogEntry entry) {
        String sha = entry.getNewId();
        int ok = JOptionPane.showConfirmDialog(this,
                "Restore this commit onto the stash list?\n" + shortOrEmpty(sha),
                "Recover stash",
                JOptionPane.OK_CANCEL_OPTION);
        if (ok == JOptionPane.OK_OPTION) {
            runRecover("Recover stash", () -> {
                Context.getGitRepoService().recoverStash(sha);
                Context.updateStash();
                return "Stash restored at " + shortOrEmpty(sha);
            });
        }
    }

    private void runRecover(String title, ProgressWorker.ResultTask<String> action) {
        statusBar.setStatus(title + "...");
        statusBar.showProgress(true);
        ProgressWorker.builder()
                .task(action)
                .onDone(statusBar::clearProgress)
                .onResult(msg -> {
                    statusBar.setStatus(msg);
                    reload();
                })
                .onException(ex -> {
                    log.log(Level.WARNING, title + " failed", ex);
                    statusBar.setStatus(title + " failed");
                    JOptionPane.showMessageDialog(this,
                            title + " failed:\n" + ex,
                            "Error", JOptionPane.ERROR_MESSAGE);
                })
                .execute();
    }

    private static String shortOrEmpty(String sha) {
        String shortId = "";
        if (sha != null && sha.length() >= 7) {
            shortId = sha.substring(0, 7);
        }
        return shortId;
    }

    private static java.util.function.Predicate<ScmReflogEntry> filterPredicate(String text) {
        String filter = text.trim().toLowerCase(Locale.ROOT);

        return filter.isEmpty() ? null : entry -> matches(entry, filter);
    }

    private static boolean matches(ScmReflogEntry e, String f) {
        return contains(e.getSelector(), f)
                || contains(e.getKind(), f)
                || contains(e.getComment(), f)
                || contains(e.getNewId(), f)
                || contains(e.getOldId(), f)
                || contains(e.getWhoName(), f);
    }

    private static boolean contains(String value, String f) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(f);
    }

    private static final class ReflogColumns implements TableColumns<ScmReflogEntry, String> {

        private static final List<String> COLUMNS = List.of(SELECTOR, ACTION, COMMIT, WHEN, MESSAGE);

        @Override
        public List<String> identifiers() {
            return COLUMNS;
        }

        @Override
        public Class<?> type(String column) {
            return String.class;
        }

        @Override
        public Object value(ScmReflogEntry e, String column) {
            return switch (column) {
                case SELECTOR -> e.getSelector();
                case ACTION   -> e.getKind();
                case COMMIT   -> e.getShortNewId();
                case WHEN     -> e.getWhen() != null ? GitemberUtil.formatDate(e.getWhen()) : "";
                case MESSAGE  -> e.getComment();
                default -> "";
            };
        }
    }
}
