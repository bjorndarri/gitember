package com.az.gitember.ui;

import com.az.gitember.data.Project;
import com.az.gitember.data.ScmPlotCommit;
import com.az.gitember.data.ScmRevisionInformation;
import com.az.gitember.data.SignatureStatus;
import com.az.gitember.dialog.InteractiveRebaseDialog;
import com.az.gitember.handler.CreateTagHandler;
import com.az.gitember.handler.InteractiveRebaseHandler;
import com.az.gitember.service.Context;
import com.az.gitember.service.GitemberUtil;
import is.codion.common.model.component.table.FilterTableModel.TableColumns;
import is.codion.common.model.worker.ProgressWorker;
import is.codion.common.reactive.state.State;
import is.codion.common.reactive.value.Value;
import is.codion.swing.common.model.component.table.SwingFilterTableModel;
import is.codion.swing.common.ui.component.table.FilterTable;
import is.codion.swing.common.ui.component.table.FilterTableColumn;
import is.codion.swing.common.ui.control.Control;
import is.codion.swing.common.ui.control.Controls;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revplot.PlotLane;
import org.eclipse.jgit.revwalk.RevCommit;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static is.codion.swing.common.ui.component.Components.*;


public class HistoryPanel extends JPanel {

    private static final Logger log = Logger.getLogger(HistoryPanel.class.getName());
    private static final int ROW_HEIGHT = 24;
    /** The width of the signature icon in the author column, which opens the signature dialog when clicked. */
    private static final int SIGNATURE_ICON_WIDTH = 18;

    private static final String MESSAGE = "Graph / Message";
    private static final String DATE    = "Date";
    private static final String AUTHOR  = "Author";

    private final SwingFilterTableModel<HistoryRow, String> model;
    private final FilterTable<HistoryRow, String> table;
    private final CommitDetailPanel detailPanel;
    private final StatusBar statusBar;
    private final CommitGraphRenderer graphRenderer = new CommitGraphRenderer();

    // Search components (merged into main toolbar when entire history is shown)
    private final Value<String> search = Value.nonNull("");
    private final JTextField    searchField;
    private final Timer         searchDebounce = new Timer(400, e -> performSearch());
    private boolean             useLucene      = false;

    // The commit menu states, computed as the menu is shown
    private final State commitSelected   = State.state();
    private final State commitUnpushed   = State.state();
    private final State commitUndoable   = State.state();
    private final State commitRewordable = State.state();
    private final State commitNoted      = State.state();
    private final Value<String> noteCaption = Value.nonNull("Add note…");

    /** Last Lucene search results: revision SHA → set of matched file names. */
    private Map<String, Set<String>> lastSearchResults = new HashMap<>();

    /** Parameters of the most recent loadHistory call, used to refresh after mutating actions. */
    private String  lastTreeName   = null;
    private boolean lastAllHistory = true;

    private final PropertyChangeListener historyRefreshListener;
    private final PropertyChangeListener repoPathListener;

    public HistoryPanel(StatusBar statusBar) {
        this(statusBar, false);
    }

    public HistoryPanel(StatusBar statusBar, boolean disableDetails) {
        this.statusBar = statusBar;
        setLayout(new BorderLayout());

        searchField = stringField()
                .link(search)
                .columns(20)
                .preferredSize(new Dimension(150, 25))
                .minimumSize(new Dimension(100, 25))
                .maximumSize(new Dimension(150, 25))
                .hint("Search commits (message, author, SHA, file name…)")
                .build();
        // Auto-search: trigger 400 ms after the user stops typing (min 3 chars)
        searchDebounce.setRepeats(false);
        search.addListener(searchDebounce::restart);

        model = SwingFilterTableModel.builder()
                .columns(new HistoryColumns())
                .build();
        detailPanel = new CommitDetailPanel(statusBar);
        model.selection().item().addConsumer(this::showDetails);

        table = FilterTable.builder()
                .model(model)
                .columns(this::configureColumn)
                .cellRenderer(MESSAGE, String.class, renderer -> renderer
                        .renderer(new GraphCellRenderer()))
                .cellRenderer(AUTHOR, String.class, renderer -> renderer
                        .customizer((filterTable, row, identifier, component) -> ((JLabel) component)
                                .setIcon(row != null && row.signed() ? CommitSignatureDialog.statusIcon(SignatureStatus.SIGNED, 12) : null)))
                .selectionMode(ListSelectionModel.SINGLE_SELECTION)
                .rowHeight(ROW_HEIGHT)
                .autoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN)
                .showVerticalLines(false)
                .intercellSpacing(new Dimension(0, 0))
                .sortable(false)
                .popupMenu(t -> commitMenu())
                .mouseListener(new SignatureClick())
                .build();

        // ── Layout ────────────────────────────────────────────────────────

        if (disableDetails) {
            add(scrollPane()
                    .view(table)
                    .build(), BorderLayout.CENTER);
        } else {
            add(splitPane()
                    .orientation(JSplitPane.VERTICAL_SPLIT)
                    .topComponent(scrollPane()
                            .view(table)
                            .build())
                    .bottomComponent(detailPanel)
                    .dividerLocation(400)
                    .resizeWeight(0.65)
                    .build(), BorderLayout.CENTER);
        }

        historyRefreshListener = evt ->
                SwingUtilities.invokeLater(() -> {
                    if (lastTreeName != null) {
                        loadHistory(lastTreeName, lastAllHistory);
                    }
                });
        repoPathListener = evt -> resetViewState();
        Context.addPropertyChangeListener(Context.PROP_HISTORY_REFRESH, historyRefreshListener);
        Context.addPropertyChangeListener(Context.PROP_REPOSITORY_PATH, repoPathListener);
    }

    /**
     * Drops Context listeners and commit data so this panel can be collected
     * when the history view is no longer visible.
     */
    public void dispose() {
        Context.removePropertyChangeListener(Context.PROP_HISTORY_REFRESH, historyRefreshListener);
        Context.removePropertyChangeListener(Context.PROP_REPOSITORY_PATH, repoPathListener);
        searchDebounce.stop();
        model.items().clear();
        lastSearchResults = new HashMap<>();
        lastTreeName = null;
        detailPanel.showRevision(null);
    }

    /** Clears history-view state specific to the previously active repository. */
    public void resetViewState() {
        lastTreeName = null;
        lastAllHistory = true;
    }

    private void configureColumn(FilterTableColumn.Builder<String> column) {
        switch (column.identifier()) {
            case MESSAGE -> column.preferredWidth(800)
                    .minWidth(800);
            case DATE -> column.preferredWidth(180)
                    .minWidth(180)
                    .maxWidth(200);
            case AUTHOR -> column.preferredWidth(170)
                    .maxWidth(220);
            default -> {}
        }
    }

    private void showDetails(HistoryRow row) {
        if (row != null) {
            ScmRevisionInformation revision = row.revision();
            detailPanel.showRevision(revision);
            if (revision != null && !lastSearchResults.isEmpty()) {
                detailPanel.setMatchedFiles(lastSearchResults.get(revision.getRevisionFullName()));
            }
        }
    }

    // ── Search logic ──────────────────────────────────────────────────────────

    public void refreshLuceneState() {
        boolean indexed = Context.getCurrentProject()
                .map(Project::isIndexed)
                .orElse(false);
        if (indexed) {
            try {
                indexed = Context.getGitRepoService().getSearchService().hasIndex();
            } catch (Exception ignored) {
                indexed = false;
            }
        }
        useLucene = indexed;
        if (indexed) {
            searchField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0, 150, 0), 2),
                    BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        } else {
            searchField.setBorder(UIManager.getBorder("TextField.border"));
        }
    }

    @SuppressWarnings("rawtypes")
    private void performSearch() {
        String term = search.get().trim();
        if (term.length() < 3) {
            lastSearchResults = new HashMap<>();
            detailPanel.setMatchedFiles(null);
            model.items().included().predicate().clear();
            statusBar.setStatus(model.items().size() + " commits loaded");
            return;
        }

        boolean useLucene = this.useLucene;
        List<PlotCommit> base = new ArrayList<>();
        for (HistoryRow row : model.items().get()) {
            if (row.commit() != null) {
                base.add(row.commit());
            }
        }

        if (base.isEmpty()) {
            statusBar.setStatus("No commits loaded.");
            return;
        }

        statusBar.setStatus("Searching…");

        ProgressWorker.builder()
                .task(() -> Context.getGitRepoService().search(base, term, useLucene))
                .onResult(this::showSearchResults)
                .onException(ex -> {
                    log.log(Level.WARNING, "Search failed", ex);
                    statusBar.setStatus("Search error: " + ex);
                })
                .execute();
    }

    private void showSearchResults(Map<String, Set<String>> results) {
        lastSearchResults = results;
        model.items().included().predicate().set(row -> results.containsKey(row.sha()));
        int found = model.items().included().size();
        statusBar.setStatus(found + " commit" + (found != 1 ? "s" : "") + " found");
        // Update highlight for currently selected commit
        HistoryRow selected = model.selection().item().get();
        if (selected != null) {
            detailPanel.setMatchedFiles(results.get(selected.sha()));
        }
    }

    public JTextField getSearchField() { return searchField; }

    public void loadHistory(String treeName, boolean allHistory) {
        loadHistory(treeName, allHistory, null);
    }

    /** Loads all history and, once loaded, selects the commit whose SHA starts with {@code sha}. */
    public void loadHistoryAndSelect(String sha) {
        loadHistory(null, true, () -> selectCommitBySha(sha));
    }

    /**
     * Selects the row whose commit SHA starts with {@code sha} and scrolls to it.
     * Works in both full-history (PlotCommit) and file-history (revision) modes.
     */
    public void selectCommitBySha(String sha) {
        if (sha == null || sha.isBlank()) return;
        model.items().included().get().stream()
                .filter(row -> row.sha() != null && row.sha().startsWith(sha))
                .findFirst()
                .ifPresentOrElse(model.selection().item()::set,
                        () -> log.fine("Commit with SHA prefix '" + sha + "' not found in loaded history"));
    }

    private void loadHistory(String treeName, boolean allHistory, Runnable afterLoad) {
        lastTreeName   = treeName;
        lastAllHistory = allHistory;
        if (!allHistory) {
            search.clear();
        }

        model.items().clear();
        model.items().included().predicate().clear();
        detailPanel.showRevision(null);
        statusBar.setStatus("Loading history...");
        statusBar.showProgress(true);
        if (allHistory) refreshLuceneState();

        ProgressWorker.builder()
                .task(() -> loadCommits(treeName, allHistory))
                .onDone(statusBar::clearProgress)
                .onResult(rows -> {
                    model.items().set(rows);
                    statusBar.setStatus(rows.size() + " commits loaded");
                    refreshLuceneState();
                    if (afterLoad != null) {
                        afterLoad.run();
                    } else if (!rows.isEmpty()) {
                        model.selection().index().set(0);
                    }
                })
                .onException(e -> {
                    log.log(Level.WARNING, "Failed to load history", e);
                    statusBar.setStatus("Failed to load history");
                })
                .execute();
    }

    @SuppressWarnings("unchecked")
    private static List<HistoryRow> loadCommits(String treeName, boolean allHistory) {
        Context.updatePlotCommitList(treeName, allHistory, null);
        List<HistoryRow> rows = new ArrayList<>();
        for (var pc : Context.getPlotCommitList()) {
            rows.add(HistoryRow.ofCommit((PlotCommit<PlotLane>) pc));
        }
        return rows;
    }

    /**
     * Load history for a specific file.
     */
    public void loadFileHistory(String fileName) {
        model.items().clear();
        detailPanel.showRevision(null);
        statusBar.setStatus("Loading file history...");
        statusBar.showProgress(true);

        ProgressWorker.builder()
                .task(() -> Context.getGitRepoService().getFileHistory(fileName))
                .onDone(statusBar::clearProgress)
                .onResult(revisions -> {
                    model.items().set(revisions.stream()
                            .map(HistoryRow::ofRevision)
                            .toList());
                    statusBar.setStatus(revisions.size() + " revisions loaded for " + fileName);
                    if (!revisions.isEmpty()) {
                        model.selection().index().set(0);
                    }
                })
                .onException(e -> {
                    log.log(Level.WARNING, "Failed to load file history", e);
                    statusBar.setStatus("Failed to load file history");
                })
                .execute();
    }

    // ── Commit context menu ───────────────────────────────────────────────

    private JPopupMenu commitMenu() {
        return menu()
                .controls(Controls.builder()
                        .control(Control.builder()
                                .command(() -> checkout(selectedCommit()))
                                .caption("Checkout")
                                .enabled(commitSelected))
                        .control(Control.builder()
                                .command(() -> checkoutAs(selectedCommit()))
                                .caption("Checkout as…")
                                .enabled(commitSelected))
                        .separator()
                        .control(Control.builder()
                                .command(() -> createTag(selectedCommit()))
                                .caption("Create tag …")
                                .enabled(commitSelected))
                        .separator()
                        .control(Control.builder()
                                .command(() -> cherryPick(selectedCommit()))
                                .caption("Cherry-pick…")
                                .enabled(commitSelected))
                        .separator()
                        .control(Control.builder()
                                .command(() -> undo(selectedCommit()))
                                .caption("Undo commit…")
                                .description("Remove this unpushed commit from the current branch (local undo)")
                                .enabled(commitUndoable))
                        .control(Control.builder()
                                .command(() -> revert(selectedCommit()))
                                .caption("Revert commit…")
                                .enabled(commitSelected))
                        .control(Control.builder()
                                .command(() -> reset(selectedCommit()))
                                .caption("Reset to commit…")
                                .enabled(commitSelected))
                        .separator()
                        .control(Control.builder()
                                .command(() -> editNote(selectedCommit()))
                                .caption(noteCaption)
                                .description("Add or edit a Git note on this commit (does not change the SHA)")
                                .enabled(commitSelected))
                        .control(Control.builder()
                                .command(() -> removeNote(selectedCommit()))
                                .caption("Remove note")
                                .description("Remove the Git note from this commit")
                                .enabled(commitNoted))
                        .separator()
                        .control(Control.builder()
                                .command(() -> changeMessage(selectedCommit()))
                                .caption("Change commit message…")
                                .description("Edit the commit message of this unpushed commit")
                                .enabled(commitRewordable))
                        .control(Control.builder()
                                .command(() -> interactiveRebase(selectedCommit()))
                                .caption("Interactive Rebase onto here…")
                                .description("Interactively rebase all commits from HEAD down to (but not including) this commit")
                                .enabled(commitUnpushed))
                        .build())
                .popupMenuListener(new CommitMenuListener())
                .buildPopupMenu();
    }

    private void updateCommitStates() {
        PlotCommit<PlotLane> commit = selectedCommit();
        boolean unpushed = commit != null && isUnpushed(commit);
        boolean hasParent = commit != null && commit.getParentCount() > 0;
        commitSelected.set(commit != null);
        commitUnpushed.set(unpushed);
        commitRewordable.set(unpushed && hasParent);
        commitUndoable.set(unpushed && hasParent && isUndoable(commit));
        commitNoted.set(commit != null && hasNote(commit));
        noteCaption.set(commitNoted.is() ? "Edit note…" : "Add note…");
    }

    private static boolean isUnpushed(PlotCommit<PlotLane> commit) {
        boolean unpushed = false;
        try {
            unpushed = Context.getGitRepoService().isCommitUnpushed(commit.getName());
        } catch (Exception ignored) {}
        return unpushed;
    }

    private static boolean isUndoable(PlotCommit<PlotLane> commit) {
        boolean undoable;
        try {
            undoable = Context.getGitRepoService().isCommitOnHeadBranch(commit.getName())
                    && (Context.getGitRepoService().isHeadCommit(commit.getName())
                    || commit.getParentCount() == 1);
        } catch (Exception ignored) {
            undoable = false;
        }
        return undoable;
    }

    private static boolean hasNote(PlotCommit<PlotLane> commit) {
        boolean hasNote = false;
        try {
            String note = Context.getGitRepoService().getCommitNote(commit.getName());
            hasNote = note != null && !note.isBlank();
        } catch (Exception ignored) {}
        return hasNote;
    }

    // ── Context-menu helpers ──────────────────────────────────────────────

    private PlotCommit<PlotLane> selectedCommit() {
        HistoryRow row = model.selection().item().get();
        return row != null ? row.commit() : null;
    }

    private void checkout(PlotCommit<PlotLane> commit) {
        runCommitAction("Checkout", () -> {
            Context.getGitRepoService().checkoutRevCommit(commit, null);
            Context.updateBranches();
            Context.updateWorkingBranch();
            return "Checked out " + commit.name().substring(0, 7);
        }, true);
    }

    private void checkoutAs(PlotCommit<PlotLane> commit) {
        String branchName = JOptionPane.showInputDialog(
                SwingUtilities.getWindowAncestor(this),
                "New branch name:", "Checkout as Branch",
                JOptionPane.PLAIN_MESSAGE);
        if (branchName != null && !branchName.isBlank()) {
            runCommitAction("Checkout as branch", () -> {
                Context.getGitRepoService().checkoutRevCommit(commit, branchName.trim(), null);
                Context.updateBranches();
                Context.updateWorkingBranch();
                return "Created and checked out branch '" + branchName.trim() + "'";
            }, true);
        }
    }

    private void createTag(PlotCommit<PlotLane> commit) {
        CreateTagHandler.showAndExecute(
                SwingUtilities.getWindowAncestor(this), statusBar, commit.getName());
    }

    private void cherryPick(PlotCommit<PlotLane> commit) {
        int ok = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                "Cherry-pick commit " + commit.name().substring(0, 7) + "?",
                "Cherry-pick", JOptionPane.OK_CANCEL_OPTION);
        if (ok == JOptionPane.OK_OPTION) {
            runCommitAction("Cherry-pick", () -> {
                Context.getGitRepoService().cherryPick((RevCommit) commit);
                return "Cherry-picked " + commit.name().substring(0, 7);
            });
        }
    }

    private void revert(PlotCommit<PlotLane> commit) {
        int ok = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                "Revert commit " + commit.name().substring(0, 7) + "?",
                "Revert commit", JOptionPane.OK_CANCEL_OPTION);
        if (ok == JOptionPane.OK_OPTION) {
            runCommitAction("Revert", () -> {
                Context.getGitRepoService().revertCommit((RevCommit) commit, null);
                return "Reverted " + commit.name().substring(0, 7);
            }, true);
        }
    }

    private void reset(PlotCommit<PlotLane> commit) {
        String[] modes = {"Soft", "Mixed", "Hard"};
        String choice = (String) JOptionPane.showInputDialog(
                SwingUtilities.getWindowAncestor(this),
                "Reset mode:", "Reset to Commit",
                JOptionPane.PLAIN_MESSAGE, null, modes, "Mixed");
        if (choice != null) {
            ResetCommand.ResetType mode = switch (choice) {
                case "Soft"  -> ResetCommand.ResetType.SOFT;
                case "Hard"  -> ResetCommand.ResetType.HARD;
                default      -> ResetCommand.ResetType.MIXED;
            };
            runCommitAction("Reset", () -> {
                Context.getGitRepoService().resetBranch((RevCommit) commit, mode, null);
                return "Reset (" + choice + ") to " + commit.name().substring(0, 7);
            }, true);
        }
    }

    private void changeMessage(PlotCommit<PlotLane> commit) {
        if (commit.getParentCount() == 0) {
            JOptionPane.showMessageDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "Cannot change the message of the initial (root) commit.",
                    "Change Commit Message", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // The plot walk drops commit bodies, so the message is read back on demand.
        String currentMessage = Context.getGitRepoService().getFullMessage(commit);
        JTextArea textArea = textArea()
                .value(currentMessage != null ? currentMessage : "")
                .rowsColumns(6, 50)
                .lineWrap(true)
                .wrapStyleWord(true)
                .build();
        int choice = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                scrollPane()
                        .view(textArea)
                        .preferredSize(new Dimension(520, 150))
                        .build(), "Change Commit Message",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        String newMessage = textArea.getText().trim();
        if (newMessage.isEmpty()) return;
        if (newMessage.equals(currentMessage != null ? currentMessage.trim() : "")) return;

        String parentSha = commit.getParent(0).getName();
        runCommitAction("Change commit message", () -> {
            List<RevCommit> range = Context.getGitRepoService().getCommitsInRange(parentSha);
            List<InteractiveRebaseDialog.RebaseStep> steps = new ArrayList<>();
            for (RevCommit c : range) {
                boolean isTarget = c.getName().equals(commit.getName());
                steps.add(new InteractiveRebaseDialog.RebaseStep(
                        isTarget ? InteractiveRebaseDialog.RebaseAction.REWORD
                                 : InteractiveRebaseDialog.RebaseAction.PICK,
                        c.getName(),
                        isTarget ? newMessage : c.getShortMessage()));
            }
            RebaseResult result = Context.getGitRepoService().interactiveRebase(parentSha, steps);
            Context.updateAll();
            if (!result.getStatus().isSuccessful()) {
                throw new RuntimeException("Rebase failed with status: " + result.getStatus());
            }
            return "Commit message updated";
        }, true);
    }

    private void interactiveRebase(PlotCommit<PlotLane> commit) {
        String fullSha    = commit.getName();
        String displaySha = fullSha.substring(0, 7);
        // Pass a callback so history reloads only after the rebase completes
        InteractiveRebaseHandler.showAndExecute(
                SwingUtilities.getWindowAncestor(this),
                fullSha, displaySha,
                () -> loadHistory(lastTreeName, lastAllHistory));
    }

    private void undo(PlotCommit<PlotLane> commit) {
        if (commit.getParentCount() == 0) {
            JOptionPane.showMessageDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "Cannot undo the initial (root) commit.",
                    "Undo commit", JOptionPane.WARNING_MESSAGE);
        } else {
            boolean dirty = Context.getGitRepoService().hasWorkingCopyChanges();
            boolean proceed = true;
            if (dirty) {
                int ok = JOptionPane.showConfirmDialog(
                        SwingUtilities.getWindowAncestor(this),
                        "You have some changes in progress and undoing commit might result in some changes being lost.\nDo you want to continue?",
                        "Undo commit",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                proceed = ok == JOptionPane.YES_OPTION;
            } else {
                int ok = JOptionPane.showConfirmDialog(
                        SwingUtilities.getWindowAncestor(this),
                        "Undo unpushed commit " + commit.name().substring(0, 7) + "?\n"
                                + "The commit is removed from this branch; its changes stay in the working copy.",
                        "Undo commit",
                        JOptionPane.OK_CANCEL_OPTION);
                proceed = ok == JOptionPane.OK_OPTION;
            }
            if (proceed) {
                runCommitAction("Undo commit", () -> {
                    Context.getGitRepoService().undoUnpushedCommit(commit);
                    Context.updateAll();
                    return "Undid commit " + commit.name().substring(0, 7);
                }, true);
            }
        }
    }

    private void editNote(PlotCommit<PlotLane> commit) {
        String sha = commit.getName();
        String existing = Context.getGitRepoService().getCommitNote(sha);
        boolean editing = existing != null && !existing.isBlank();
        JTextArea textArea = textArea()
                .value(existing != null ? existing : "")
                .rowsColumns(8, 50)
                .lineWrap(true)
                .wrapStyleWord(true)
                .build();
        String title = editing ? "Edit Note" : "Add Note";
        int choice = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                scrollPane()
                        .view(textArea)
                        .preferredSize(new Dimension(520, 180))
                        .build(), title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            String text = textArea.getText();
            if (text == null || text.isBlank()) {
                if (editing) {
                    runCommitAction("Remove note", () -> {
                        Context.getGitRepoService().removeCommitNote(sha);
                        return "Note removed from " + sha.substring(0, 7);
                    }, false, true);
                }
            } else {
                runCommitAction(editing ? "Edit note" : "Add note", () -> {
                    Context.getGitRepoService().addCommitNote(sha, text);
                    return (editing ? "Note updated on " : "Note added to ") + sha.substring(0, 7);
                }, false, true);
            }
        }
    }

    private void removeNote(PlotCommit<PlotLane> commit) {
        String sha = commit.getName();
        int ok = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                "Remove the note from commit " + sha.substring(0, 7) + "?",
                "Remove note", JOptionPane.OK_CANCEL_OPTION);
        if (ok == JOptionPane.OK_OPTION) {
            runCommitAction("Remove note", () -> {
                Context.getGitRepoService().removeCommitNote(sha);
                return "Note removed from " + sha.substring(0, 7);
            }, false, true);
        }
    }

    private void refreshSelectedCommitDetails() {
        HistoryRow row = model.selection().item().get();
        if (row != null) {
            detailPanel.showRevision(row.revision());
        }
    }

    /** Runs a git action on a background thread, showing status bar progress. */
    private void runCommitAction(String label, ProgressWorker.ResultTask<String> action) {
        runCommitAction(label, action, false, false);
    }

    private void runCommitAction(String label, ProgressWorker.ResultTask<String> action, boolean refreshAfter) {
        runCommitAction(label, action, refreshAfter, false);
    }

    private void runCommitAction(String label, ProgressWorker.ResultTask<String> action, boolean refreshAfter,
                                 boolean refreshDetails) {
        statusBar.setStatus(label + "…");
        statusBar.showProgress(true);
        ProgressWorker.builder()
                .task(action)
                .onDone(statusBar::clearProgress)
                .onResult(message -> {
                    statusBar.setStatus(message);
                    if (refreshAfter) {
                        loadHistory(lastTreeName, lastAllHistory);
                    } else if (refreshDetails) {
                        refreshSelectedCommitDetails();
                    }
                })
                .onException(ex -> commitActionFailed(label, ex))
                .execute();
    }

    private void commitActionFailed(String label, Exception ex) {
        log.log(Level.WARNING, label + " failed", ex);
        CheckoutConflictException conflict = null;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof CheckoutConflictException cce) {
                conflict = cce;
                break;
            }
        }
        String message;
        if (conflict != null && !conflict.getConflictingPaths().isEmpty()) {
            message = "Checkout blocked by local changes in:\n\n"
                    + String.join("\n", conflict.getConflictingPaths());
            statusBar.setStatus(label + " failed: checkout conflict in "
                    + conflict.getConflictingPaths().size() + " file(s)");
        } else {
            message = ex.getMessage();
            statusBar.setStatus(label + " failed: " + ex.getMessage());
        }
        JOptionPane.showMessageDialog(
                SwingUtilities.getWindowAncestor(this),
                message, label + " Error",
                JOptionPane.ERROR_MESSAGE);
        ProgressWorker.builder()
                .task(() -> {
                    Context.updateBranches();
                    Context.updateWorkingBranch();
                })
                .execute();
    }

    /** Computes the commit menu states as the menu is shown, since they take git lookups. */
    private final class CommitMenuListener implements PopupMenuListener {

        @Override
        public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            updateCommitStates();
        }

        @Override
        public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

        @Override
        public void popupMenuCanceled(PopupMenuEvent e) {}
    }

    /** Opens the signature dialog when the signature icon of a signed commit is clicked. */
    private final class SignatureClick extends MouseAdapter {

        @Override
        public void mouseClicked(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            int column = table.columnAtPoint(e.getPoint());
            if (row >= 0 && column >= 0 && AUTHOR.equals(table.columns().getColumn(column).identifier())) {
                HistoryRow historyRow = model.items().included().get(row);
                Rectangle cell = table.getCellRect(row, column, false);
                if (historyRow.signed() && e.getX() - cell.x <= SIGNATURE_ICON_WIDTH) {
                    CommitSignatureDialog.open(SwingUtilities.getWindowAncestor(HistoryPanel.this),
                            historyRow.revision(), table::repaint);
                }
            }
        }
    }

    // Custom cell renderer: graph canvas on the left, commit message label on the right.
    // The table's cell renderer sets the row colours on the panel, the canvas is transparent.
    private class GraphCellRenderer extends JPanel implements TableCellRenderer {

        private final GraphCanvas graphCanvas = new GraphCanvas();
        private final JLabel messageLabel = new JLabel();

        GraphCellRenderer() {
            setLayout(new BorderLayout(4, 0));
            messageLabel.setVerticalAlignment(SwingConstants.CENTER);
            add(graphCanvas, BorderLayout.WEST);
            add(messageLabel, BorderLayout.CENTER);
            setOpaque(true);
        }

        @Override
        public void setForeground(Color foreground) {
            super.setForeground(foreground);
            // null while JPanel's constructor installs the look and feel colours
            if (messageLabel != null) {
                messageLabel.setForeground(foreground);
            }
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            messageLabel.setFont(table.getFont());
            // file-history rows have no commit, no graph
            graphCanvas.setCommit(model.items().included().get(row).commit());
            messageLabel.setText(value != null ? value.toString() : "");

            doLayout();
            return this;
        }
    }

    // Paints only the graph portion (lines, dots, ref labels) for a single commit row
    private class GraphCanvas extends JComponent {

        private PlotCommit<PlotLane> commit;

        void setCommit(PlotCommit<PlotLane> commit) {
            this.commit = commit;
            if (commit != null) {
                // Quick off-screen pass to measure how wide the graph portion is.
                // The image is 1 px wide so no real pixels are written; only the
                // graphWidth side-effect from drawText() is captured.
                BufferedImage probe = new BufferedImage(1, ROW_HEIGHT, BufferedImage.TYPE_INT_ARGB);
                Graphics2D mg = probe.createGraphics();
                mg.setFont(table.getFont());
                int w = graphRenderer.render(mg, commit, ROW_HEIGHT);
                mg.dispose();
                setPreferredSize(new Dimension(Math.max(w + 4, 20), ROW_HEIGHT));
            } else {
                setPreferredSize(new Dimension(0, ROW_HEIGHT));
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (commit == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            graphRenderer.render(g2, commit, getHeight());
            g2.dispose();
        }
    }

    /** A history table row, a commit of the commit history or a revision of a file history. */
    private record HistoryRow(PlotCommit<PlotLane> commit, ScmRevisionInformation fileRevision) {

        static HistoryRow ofCommit(PlotCommit<PlotLane> commit) {
            return new HistoryRow(commit, null);
        }

        static HistoryRow ofRevision(ScmRevisionInformation revision) {
            return new HistoryRow(null, revision);
        }

        /** The revision, adapted from the commit in the commit history. */
        ScmRevisionInformation revision() {
            return commit != null ? Context.getGitRepoService().adapt(commit, null) : fileRevision;
        }

        String sha() {
            return commit != null ? commit.getName() : fileRevision.getRevisionFullName();
        }

        boolean signed() {
            return commit != null ? ScmPlotCommit.signedOf(commit) : fileRevision.isSigned();
        }

        String message() {
            String message = commit != null ? ScmPlotCommit.shortMessageOf(commit) : fileRevision.getShortMessage();
            return message != null ? message : "";
        }

        String date() {
            if (commit != null) {
                return GitemberUtil.formatDate(GitemberUtil.intToDate(commit.getCommitTime()));
            }
            return fileRevision.getDate() != null ? GitemberUtil.formatDate(fileRevision.getDate()) : "";
        }

        String author() {
            String author = commit != null ? ScmPlotCommit.authorNameOf(commit) : fileRevision.getAuthorName();
            return author != null ? author : "";
        }
    }

    private static final class HistoryColumns implements TableColumns<HistoryRow, String> {

        private static final List<String> COLUMNS = List.of(MESSAGE, DATE, AUTHOR);

        @Override
        public List<String> identifiers() {
            return COLUMNS;
        }

        @Override
        public Class<?> type(String column) {
            return String.class;
        }

        @Override
        public Object value(HistoryRow row, String column) {
            return switch (column) {
                case MESSAGE -> row.message();
                case DATE    -> row.date();
                case AUTHOR  -> row.author();
                default -> "";
            };
        }
    }
}
