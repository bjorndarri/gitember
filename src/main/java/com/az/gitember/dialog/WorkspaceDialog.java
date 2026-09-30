package com.az.gitember.dialog;

import com.az.gitember.data.Const;
import com.az.gitember.data.Project;
import com.az.gitember.data.Settings;
import com.az.gitember.data.Workspace;
import com.az.gitember.service.Context;
import com.az.gitember.ui.misc.Util;
import is.codion.common.model.CancelException;
import is.codion.common.reactive.value.Value;
import is.codion.swing.common.model.component.list.SwingFilterListModel;
import is.codion.swing.common.ui.Utilities;
import is.codion.swing.common.ui.component.list.FilterList;
import is.codion.swing.common.ui.control.Control;
import is.codion.swing.common.ui.dialog.Dialogs;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static is.codion.swing.common.ui.component.Components.*;

/**
 * Create / edit and open a {@link Workspace}. A workspace has a name and a list of
 * projects (git repositories). Projects can be added either by picking one of the
 * already-known projects from {@link Settings#getProjects()}, or by selecting a git
 * repository folder on disk.
 *
 * <p>Create mode starts a new workspace and persists it when the user presses <em>Open</em>.
 * Edit mode loads an existing workspace; changes are kept on a copy until <em>Save</em>,
 * then written back to that same instance. <em>Cancel</em> discards the copy.
 */
public class WorkspaceDialog extends JDialog {

    /** Live settings instance being edited, or {@code null} when creating a workspace. */
    private final Workspace existingWorkspace;
    private final Workspace workspace;
    private final Value<String> name = Value.nonNull("");
    public final JTextField nameField;
    private final SwingFilterListModel<Project> projectModel = SwingFilterListModel.builder().<Project>items().build();
    private final FilterList<Project> projectList;

    /** Invoked with the persisted workspace after a successful save (may be {@code null}). */
    private final Consumer<Workspace> onWorkspaceOpened;

    private boolean confirmed = false;


    public WorkspaceDialog(Frame owner, Consumer<Workspace> onWorkspaceOpened) {
        this(owner, null, onWorkspaceOpened);
    }

    public WorkspaceDialog(Frame owner, Workspace existing, Consumer<Workspace> onWorkspaceOpened) {
        super(owner, existing != null ? "Edit workspace" : "Workspaces",
                Dialog.ModalityType.DOCUMENT_MODAL);


        setSize(640, 480);
        setLocationRelativeTo(owner);
        Util.bindEscapeToDispose(this);

        this.existingWorkspace = existing;
        this.onWorkspaceOpened = onWorkspaceOpened;
        this.workspace = loadWorkingCopy(existing);

        nameField = stringField()
                .columns(24)
                .name("workspaceNameField")
                .label("Name:")
                .link(name)
                .build();
        projectList = FilterList.builder()
                .model(projectModel)
                .selectedItem()
                .name("workspaceProjectList")
                .cellRenderer(new ProjectCellRenderer())
                .build();

        setLayout(new BorderLayout());
        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildButtonPanel(), BorderLayout.SOUTH);

        loadIntoForm();

        // Keep the workspace name in sync as it is typed.
        name.addListener(this::applyNameEdit);
    }

    // ── UI construction ────────────────────────────────────────────────────────

    private JPanel buildTopPanel() {
        return form()
                .border(BorderFactory.createEmptyBorder(15, 15, 5, 15))
                .add(nameField)
                .build();
    }

    private JComponent buildCenterPanel() {
        Dimension size = new Dimension(180, 36);

        JButton addExistingBtn = Util.createButton("Add existing…", "Add Existing Project…", FontAwesomeSolid.PLUS,0,size);
        addExistingBtn.setName("addExistingProjectButton");
        addExistingBtn.addActionListener(e -> addExistingProject());

        JButton addFromDiskBtn = Util.createButton("Add new…", "Add Repository from Disk…", FontAwesomeSolid.PLUS,0,size);
        addFromDiskBtn.setName("addRepositoryFromDiskButton");
        addFromDiskBtn.addActionListener(e -> addRepositoryFromDisk());

        JButton removeBtn = Util.createButton("Remove", null, FontAwesomeSolid.MINUS,0,size);
        removeBtn.setName("removeProjectButton");
        removeBtn.addActionListener(e -> removeSelectedProject());
        Utilities.enabled(projectModel.selection().present(), removeBtn);

        return borderLayoutPanel()
                .layout(new BorderLayout(8, 8))
                .border(BorderFactory.createEmptyBorder(0, 15, 5, 15))
                .north(label("Repositories")
                        .font(font -> font.deriveFont(Font.BOLD, font.getSize() - 1f))
                        .foreground(UIManager.getColor("Label.disabledForeground")))
                .center(scrollPane()
                        .view(projectList))
                .south(panel()
                        .layout(new FlowLayout(FlowLayout.LEFT, 6, 0))
                        .add(addExistingBtn)
                        .add(addFromDiskBtn)
                        .add(removeBtn))
                .build();
    }

    private JPanel buildButtonPanel() {
        boolean editing = existingWorkspace != null;
        JButton confirmBtn = button()
                .control(Control.builder()
                        .command(this::persistWorkspace)
                        .caption(editing ? "Save" : "Open"))
                .name(editing ? "saveWorkspaceButton" : "openWorkspaceButton")
                .build();
        getRootPane().setDefaultButton(confirmBtn);

        return flowLayoutPanel(FlowLayout.RIGHT)
                .add(confirmBtn)
                .add(button()
                        .control(Control.builder()
                                .command(this::dispose)
                                .caption("Cancel"))
                        .name("cancelWorkspaceButton"))
                .build();
    }

    // ── Model handling ───────────────────────────────────────────────────────────

    private Workspace loadWorkingCopy(Workspace existing) {
        Workspace copy;
        if (existing != null) {
            copy = new Workspace(existing.getName(), new TreeSet<>(existing.getProjects()));
        } else {
            Settings settings = Context.getSettings();
            String name = settings != null ? settings.createNewWorkspaceName() : "New workspace";
            copy = new Workspace(name, new TreeSet<>());
        }
        return copy;
    }

    private void loadIntoForm() {
        name.set(workspace.getName() != null ? workspace.getName() : "");
        projectModel.items().set(workspace.getProjects());
    }

    private void applyNameEdit() {
        workspace.setName(name.get().trim());
    }

    // ── Project actions ────────────────────────────────────────────────────────

    private void addExistingProject() {
        // Candidates: the known recent projects, excluding those already in this workspace.
        Settings settings = Context.getSettings();
        Set<Project> candidates = new TreeSet<>();
        if (settings != null) {
            candidates.addAll(settings.getProjects());
        }
        candidates.removeAll(workspace.getProjects());
        if (candidates.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No other known projects to add.",
                    "Add Existing Project", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Project[] options = candidates.toArray(new Project[0]);
        JList<Project> chooser = new JList<>(options);
        chooser.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        chooser.setCellRenderer(new ProjectCellRenderer());
        int result = JOptionPane.showConfirmDialog(this, new JScrollPane(chooser),
                "Select project(s) to add", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            workspace.getProjects().addAll(chooser.getSelectedValuesList());
            loadIntoForm();
        }
    }

    private void addRepositoryFromDisk() {
        File folder;
        try {
            folder = Dialogs.select()
                    .files()
                    .owner(this)
                    .title("Select Git Repository")
                    .selectDirectory();
        } catch (CancelException e) {
            return;
        }
        if (!new File(folder, Const.GIT_FOLDER).exists()) {
            JOptionPane.showMessageDialog(this,
                    "The selected folder is not a git repository (no " + Const.GIT_FOLDER + " folder found).",
                    "Not a Repository", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Project project = Context.getSettings().getOrCreateProject(folder.getAbsolutePath());
        if (!workspace.getProjects().add(project)) {
            JOptionPane.showMessageDialog(this, "This repository is already in the workspace.",
                    "Already Added", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        loadIntoForm();
        projectModel.selection().item().set(project);
    }

    private void removeSelectedProject() {
        Project selected = projectModel.selection().item().get();
        if (selected == null) {
            return;
        }
        workspace.getProjects().remove(selected);
        loadIntoForm();
    }

    // ── Persist ──────────────────────────────────────────────────────────────────

    public boolean isConfirmed() {
        return confirmed;
    }

    private void persistWorkspace() {
        applyNameEdit();
        if (workspace.getName() == null || workspace.getName().isBlank()) {
            JOptionPane.showMessageDialog(this, "Workspace name cannot be empty.",
                    "Invalid Name", JOptionPane.WARNING_MESSAGE);
            nameField.requestFocusInWindow();
            return;
        }
        Settings settings = Context.getSettings();
        if (settings != null) {
            if (isNameTaken(settings)) {
                JOptionPane.showMessageDialog(this,
                        "A workspace named \"" + workspace.getName() + "\" already exists.",
                        "Invalid Name", JOptionPane.WARNING_MESSAGE);
                nameField.requestFocusInWindow();
                return;
            }
            Workspace persisted = applyToSettings(settings);
            Context.saveSettings();
            if (onWorkspaceOpened != null) {
                onWorkspaceOpened.accept(persisted);
            }
        }
        confirmed = true;
        dispose();
    }

    private boolean isNameTaken(Settings settings) {
        boolean taken = false;
        String name = workspace.getName();
        for (Workspace ws : settings.getWorkspaces()) {
            if (ws != existingWorkspace && name.equals(ws.getName())) {
                taken = true;
            }
        }
        return taken;
    }

    private Workspace applyToSettings(Settings settings) {
        Workspace persisted;
        if (existingWorkspace != null) {
            existingWorkspace.setName(workspace.getName());
            existingWorkspace.setProjects(new TreeSet<>(workspace.getProjects()));
            persisted = existingWorkspace;
        } else {
            settings.getWorkspaces().add(workspace);
            persisted = workspace;
        }
        return persisted;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Renders a project as "folder-name — full/path". */
    private static class ProjectCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Project p) {
                String folder = p.getProjectHomeFolder();
                String name = new File(folder).getName();
                setText("<html><b>" + name + "</b>  <span style='color:gray'>" + folder + "</span></html>");
            }
            return this;
        }
    }
}
