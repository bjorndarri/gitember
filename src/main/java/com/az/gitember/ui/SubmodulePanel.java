package com.az.gitember.ui;

import com.az.gitember.data.Submodule;
import com.az.gitember.handler.AddSubmoduleHandler;
import com.az.gitember.handler.CommitSubmoduleHandler;
import com.az.gitember.handler.InitSubmodulesHandler;
import com.az.gitember.handler.RemoveSubmoduleHandler;
import com.az.gitember.handler.UpdateSubmodulesHandler;
import com.az.gitember.service.Context;
import com.az.gitember.ui.misc.Util;
import is.codion.common.model.component.table.FilterTableModel.TableColumns;
import is.codion.common.model.worker.ProgressWorker;
import is.codion.common.reactive.state.ObservableState;
import is.codion.swing.common.model.component.table.SwingFilterTableModel;
import is.codion.swing.common.ui.component.table.FilterTable;
import is.codion.swing.common.ui.control.Control;
import is.codion.swing.common.ui.control.Controls;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static is.codion.swing.common.ui.component.Components.*;

/**
 * Panel that shows all Git submodules with their status,
 * and provides Update / Sync / Refresh actions.
 */
public class SubmodulePanel extends JPanel {

    private static final String PATH      = "Path";
    private static final String STATUS    = "Status";
    private static final String INDEX_SHA = "Index SHA";
    private static final String HEAD_SHA  = "HEAD SHA";
    private static final String URL       = "URL";

    private final StatusBar statusBar;
    private final SwingFilterTableModel<Submodule, String> model = SwingFilterTableModel.builder()
            .columns(new SubmoduleColumns())
            .build();

    public SubmodulePanel(StatusBar statusBar) {
        this.statusBar = statusBar;

        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ── Toolbar ───────────────────────────────────────────────────────────
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);

        JButton addBtn = Util.createButton("Add…", "Clone a repository and register it as a submodule", FontAwesomeSolid.FOLDER_OPEN);
        addBtn.addActionListener(e -> AddSubmoduleHandler.showAndExecute(this));

        JButton initBtn = Util.createButton("Init","Run git submodule init for all submodules", FontAwesomeSolid.STAR);
        initBtn.addActionListener(e -> new InitSubmodulesHandler(this).execute());

        //JButton updateBtn = Util.createButton("Update All", "Run git submodule init + update for all submodules");
        //updateBtn.addActionListener(e -> new UpdateSubmodulesHandler(this).execute());

        JButton recursiveBtn = Util.createButton("Update","Recursive update this repository and nested submodules");
        recursiveBtn.addActionListener(e -> new UpdateSubmodulesHandler(this, true).execute());

        JButton syncBtn = Util.createButton("Sync URLs","Run git submodule sync — update recorded remote URLs from .gitmodules");
        syncBtn.addActionListener(e -> syncSubmodules());

        JButton refreshBtn = Util.createButton("Refresh","Re-read submodule status from disk", FontAwesomeSolid.SYNC);
        refreshBtn.addActionListener(e -> Context.updateSubmodules());

        toolbar.add(addBtn);
        toolbar.add(initBtn);
        //toolbar.add(updateBtn);
        toolbar.add(recursiveBtn);
        toolbar.addSeparator();
        toolbar.add(syncBtn);
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(refreshBtn);

        // ── Table ─────────────────────────────────────────────────────────────
        ObservableState selected = model.selection().present();
        FilterTable<Submodule, String> table = FilterTable.builder()
                .model(model)
                .columns(column -> column.preferredWidth(switch (column.identifier()) {
                    case PATH -> 180;
                    case STATUS -> 80;
                    case INDEX_SHA, HEAD_SHA -> 90;
                    default -> 320;
                }))
                // Colour the Status column by status value
                .cellRenderer(STATUS, Submodule.Status.class, renderer -> renderer
                        .formatter(SubmodulePanel::statusLabel)
                        .foreground((t, submodule, identifier, status) -> statusColor(status)))
                .rowHeight(22)
                .selectionMode(ListSelectionModel.SINGLE_SELECTION)
                .autoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS)
                .columnReordering(false)
                .sortable(false)
                .popupControls(t -> submoduleControls(this, model.selection().item()::get, selected))
                .doubleClick(Control.builder()
                        .command(this::openSelectedSubmodule)
                        .build())
                .build();

        add(toolbar, BorderLayout.NORTH);
        add(scrollPane()
                .view(table)
                .build(), BorderLayout.CENTER);
    }

    /** Called from Context PROP_SUBMODULES listener to refresh the table. */
    public void setSubmodules(List<Submodule> submodules) {
        model.items().set(submodules != null ? submodules : Collections.emptyList());
    }

    /** Public entry point so the menu item in MainFrame can trigger sync without selecting the panel first. */
    public void syncSubmoduleUrls() {
        syncSubmodules();
    }

    private void openSelectedSubmodule() {
        Submodule selected = model.selection().item().get();
        if (selected != null) {
            openSubmodule(this, selected);
        }
    }

    public static JPopupMenu createSubmoduleMenu(Component parent, Submodule selected) {
        return menu()
                .controls(submoduleControls(parent, () -> selected, null))
                .buildPopupMenu();
    }

    /**
     * @param parent the parent component
     * @param submodule supplies the submodule the actions apply to
     * @param enabled enables the actions applying to the submodule, null for always enabled
     * @return the submodule actions
     */
    private static Controls submoduleControls(Component parent, Supplier<Submodule> submodule, ObservableState enabled) {
        return Controls.builder()
                .control(Control.builder()
                        .command(() -> new InitSubmodulesHandler(parent, submodule.get().getPath()).execute())
                        .caption("Initialize")
                        .enabled(enabled))
                .control(Control.builder()
                        .command(() -> new UpdateSubmodulesHandler(parent, false, submodule.get().getPath()).execute())
                        .caption("Update")
                        .enabled(enabled))
                .control(Control.builder()
                        .command(() -> new UpdateSubmodulesHandler(parent, true).execute())
                        .caption("Recursive update"))
                .separator()
                .control(Control.builder()
                        .command(() -> CommitSubmoduleHandler.showAndExecute(parent, submodule.get()))
                        .caption("Commit submodule change…")
                        .enabled(enabled))
                .control(Control.builder()
                        .command(() -> showSubmoduleDiff(parent, submodule.get()))
                        .caption("Show diff")
                        .enabled(enabled))
                .control(Control.builder()
                        .command(() -> showSubmoduleStatus(parent, submodule.get()))
                        .caption("Show status")
                        .enabled(enabled))
                .separator()
                .control(Control.builder()
                        .command(() -> openSubmodule(parent, submodule.get()))
                        .caption("Open")
                        .enabled(enabled))
                .control(Control.builder()
                        .command(() -> RemoveSubmoduleHandler.showAndExecute(parent, submodule.get()))
                        .caption("Remove…")
                        .enabled(enabled))
                .build();
    }

    public static void showSubmoduleDiff(Component parent, Submodule selected) {
        try {
            String diff = Context.getGitRepoService().getSubmoduleDiff(selected.getPath());
            JTextArea area = new JTextArea(diff, 14, 72);
            area.setEditable(false);
            area.setFont(SyntaxStyleUtil.monoFont());
            JOptionPane.showMessageDialog(parent, new JScrollPane(area),
                    "Submodule Diff — " + selected.getPath(), JOptionPane.PLAIN_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent,
                    "Cannot compute submodule diff:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void showSubmoduleStatus(Component parent, Submodule selected) {
        String text = "Path: " + selected.getPath() + "\n"
                + "Name: " + selected.getName() + "\n"
                + "URL: " + selected.getUrl() + "\n"
                + "Status: " + selected.getStatus() + "\n"
                + "Index SHA: " + selected.getIndexSha() + "\n"
                + "HEAD SHA: " + selected.getHeadSha();
        JTextArea area = new JTextArea(text, 8, 56);
        area.setEditable(false);
        JOptionPane.showMessageDialog(parent, new JScrollPane(area),
                "Submodule Status — " + selected.getPath(), JOptionPane.INFORMATION_MESSAGE);
    }

    public static void openSubmodule(Component parent, Submodule selected) {
        if (selected != null && selected.getStatus() != Submodule.Status.UNINITIALIZED
                && Context.getGitRepoService().getRepository() != null) {
            File workTree = Context.getGitRepoService().getRepository().getWorkTree();
            File subDir = new File(workTree, selected.getPath());
            File gitMarker = new File(subDir, ".git");
            if (subDir.isDirectory() && gitMarker.exists()) {
                new com.az.gitember.ui.mainframe.ReopenRepoHandler(
                        com.az.gitember.ui.MainFrame.getInstance())
                        .accept(new com.az.gitember.data.Project(subDir.getAbsolutePath(), new java.util.Date()));
            } else {
                JOptionPane.showMessageDialog(parent,
                        "Submodule working tree is not available.\nInitialize and update it first.",
                        "Open Submodule", JOptionPane.WARNING_MESSAGE);
            }
        } else {
            JOptionPane.showMessageDialog(parent,
                    "Initialize and update the submodule before opening it.",
                    "Open Submodule", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void syncSubmodules() {
        statusBar.setStatus("Syncing submodule URLs...");
        statusBar.showProgress(true);
        ProgressWorker.builder()
                .task(() -> Context.getGitRepoService().syncSubmodules())
                .onDone(statusBar::clearProgress)
                .onSuccess(() -> {
                    statusBar.setStatus("Submodule URLs synced");
                    Context.updateSubmodules();
                })
                .onException(ex -> {
                    statusBar.setStatus("Sync failed: " + ex);
                    JOptionPane.showMessageDialog(this,
                            "Sync failed:\n" + ex,
                            "Error", JOptionPane.ERROR_MESSAGE);
                })
                .execute();
    }

    private static String statusLabel(Submodule.Status status) {
        return switch (status) {
            case UP_TO_DATE    -> "Up to date";
            case MODIFIED      -> "Modified";
            case UNINITIALIZED -> "Uninitialized";
            case MISSING       -> "Missing";
        };
    }

    private static Color statusColor(Submodule.Status status) {
        return switch (status) {
            case UP_TO_DATE    -> new Color(0, 140, 0);
            case MODIFIED      -> new Color(180, 100, 0);
            case UNINITIALIZED -> Color.GRAY;
            case MISSING       -> Color.RED;
        };
    }

    // ── Table columns ─────────────────────────────────────────────────────────

    private static final class SubmoduleColumns implements TableColumns<Submodule, String> {

        private static final List<String> COLUMNS = List.of(PATH, STATUS, INDEX_SHA, HEAD_SHA, URL);

        @Override
        public List<String> identifiers() {
            return COLUMNS;
        }

        @Override
        public Class<?> type(String column) {
            return column.equals(STATUS) ? Submodule.Status.class : String.class;
        }

        @Override
        public Object value(Submodule s, String column) {
            return switch (column) {
                case PATH      -> s.getPath();
                case STATUS    -> s.getStatus();
                case INDEX_SHA -> s.getIndexSha();
                case HEAD_SHA  -> s.getHeadSha();
                case URL       -> s.getUrl();
                default -> "";
            };
        }
    }
}
