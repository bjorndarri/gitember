package com.az.gitember.ui;

import com.az.gitember.service.Context;
import com.az.gitember.ui.misc.Util;
import is.codion.common.model.CancelException;
import is.codion.common.model.worker.ProgressWorker;
import is.codion.common.reactive.state.State;
import is.codion.swing.common.ui.Utilities;
import is.codion.swing.common.ui.control.Control;
import is.codion.swing.common.ui.control.Controls;
import is.codion.swing.common.ui.dialog.Dialogs;
import is.codion.swing.common.ui.key.KeyEvents;
import org.apache.commons.lang3.StringUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static is.codion.swing.common.ui.component.Components.*;
import static is.codion.swing.common.ui.key.KeyEvents.MENU_SHORTCUT_MASK;

/**
 * Window to display (and optionally edit) text file content.
 * Supports optional git-blame annotation via the "Annotate" toggle button.
 */
public class FileViewerWindow extends JFrame {

    private static final Logger log = Logger.getLogger(FileViewerWindow.class.getName());

    private final RSyntaxTextArea textArea;
    private final RTextScrollPane scrollPane;
    private final String suggestedFileName;
    private final SearchBar searchBar;

    // Blame components
    private final State annotated    = State.state();
    private final State blameLoading = State.state();
    private final State blameShaClicked = State.state();
    private final JToggleButton annotateBtn;
    private final JButton saveBtn;
    private final JTextArea blameTextArea;
    private final JScrollPane blameScrollPane;
    private final JPanel centerPanel;

    private String  blameCommitSha  = null;
    private String  blameFilePath   = null;
    private boolean blameLoaded     = false;
    private String  lastBlameClickSha = null;
    private java.nio.file.Path editPath = null;
    private String savedContent = null;

    public FileViewerWindow(String title, String content) {
        this(title, content, title);
    }

    public FileViewerWindow(String title, String content, String fileName) {
        this(title, content, fileName, null);
    }

    public FileViewerWindow(String title, String content, String fileName, String searchTerm) {
        setName("fileViewerWindow");
        setTitle(title);
        setSize(900, 600);
        setLocationRelativeTo(MainFrame.getInstance());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImages(Util.appIcons());

        this.suggestedFileName = fileName;

        // ── Main text area ────────────────────────────────────────────────
        textArea = new RSyntaxTextArea(content);
        textArea.setName("fileContentArea");
        textArea.setEditable(false);
        textArea.setSyntaxEditingStyle(SyntaxStyleUtil.getSyntaxStyle(fileName));
        textArea.setCodeFoldingEnabled(false);
        textArea.setAntiAliasingEnabled(true);
        textArea.setCaretPosition(0);
        textArea.setBracketMatchingEnabled(false);
        textArea.setAnimateBracketMatching(false);
        SyntaxStyleUtil.applyTheme(textArea);
        textArea.setFont(SyntaxStyleUtil.monoFont());  // after theme so settings font size is applied

        scrollPane = new RTextScrollPane(textArea);
        scrollPane.setFoldIndicatorEnabled(false);

        // ── Blame sidebar ─────────────────────────────────────────────────
        boolean dark = SyntaxStyleUtil.isDarkTheme();
        blameTextArea = textArea()
                .editable(false)
                .font(SyntaxStyleUtil.monoFont())
                .focusable(false)
                .lineWrap(false)
                .border(BorderFactory.createEmptyBorder(0, 4, 0, 8))
                .background(dark ? new Color(35, 35, 40)  : new Color(240, 240, 245))
                .foreground(dark ? new Color(160, 170, 180) : new Color(80,  80,  90))
                .build();

        blameScrollPane = scrollPane()
                .view(blameTextArea)
                .verticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER)
                .horizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER)
                .border(BorderFactory.createMatteBorder(
                        0, 0, 0, 1,
                        dark ? new Color(70, 70, 80) : new Color(200, 200, 210)))
                .visible(false)
                .build();

        // ── Blame context menu & mouse interactions ───────────────────────
        // the menu acts on the line clicked, so it is shown from the mouse listener
        JPopupMenu blameContextMenu = menu()
                .controls(Controls.builder()
                        .control(Control.builder()
                                .command(() -> navigateToHistoryCommit(lastBlameClickSha))
                                .caption("Open commit in History")
                                .enabled(blameShaClicked)))
                .buildPopupMenu();

        blameTextArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    lastBlameClickSha = extractShaFromBlamePoint(e.getPoint());
                    if (lastBlameClickSha != null && !lastBlameClickSha.isBlank()) {
                        navigateToHistoryCommit(lastBlameClickSha);
                    }
                }
            }

            @Override public void mousePressed(MouseEvent e)  { maybeShowBlameMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowBlameMenu(e); }

            private void maybeShowBlameMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                lastBlameClickSha = extractShaFromBlamePoint(e.getPoint());
                blameShaClicked.set(lastBlameClickSha != null && !lastBlameClickSha.isBlank());
                blameContextMenu.show(blameTextArea, e.getX(), e.getY());
            }
        });

        // Sync blame viewport to follow the main viewport (one-way, no model conflicts)
        scrollPane.getViewport().addChangeListener(e -> {
            int y = scrollPane.getViewport().getViewPosition().y;
            Point cur = blameScrollPane.getViewport().getViewPosition();
            if (cur.y != y) {
                blameScrollPane.getViewport().setViewPosition(new Point(0, y));
            }
        });

        // ── Search bar ────────────────────────────────────────────────────
        searchBar = new SearchBar(textArea);

        if (StringUtils.isNotBlank(searchTerm)) {
            SwingUtilities.invokeLater(() -> {
                searchBar.activate();
                searchBar.getSearchField().setText(searchTerm);
                searchBar.findNext(true);
            });
        }


        // ── Buttons ───────────────────────────────────────────────────────
        saveBtn = button()
                .control(Control.builder()
                        .command(this::saveContent)
                        .caption("Save..."))
                .name("saveFileButton")
                .build();

        JButton closeBtn = button()
                .control(Control.builder()
                        .command(this::close)
                        .caption("Close"))
                .name("closeFileButton")
                .build();

        annotateBtn = toggleButton()
                .text("Annotate")
                .link(annotated)
                .visible(false)   // shown only when enableBlame() is called
                .build();
        Utilities.enabled(blameLoading.not(), annotateBtn);
        annotated.addListener(this::toggleAnnotation);

        JPanel btnPanel = borderLayoutPanel()
                .layout(new BorderLayout())
                .west(panel()
                        .layout(new FlowLayout(FlowLayout.LEFT,  8, 6))
                        .add(annotateBtn))
                .east(panel()
                        .layout(new FlowLayout(FlowLayout.RIGHT, 8, 6))
                        .add(saveBtn)
                        .add(closeBtn))
                .build();

        // ── Layout ────────────────────────────────────────────────────────
        centerPanel = borderLayoutPanel()
                .layout(new BorderLayout())
                .west(blameScrollPane)
                .center(scrollPane)
                .build();

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(searchBar,   BorderLayout.NORTH);
        getContentPane().add(centerPanel, BorderLayout.CENTER);
        getContentPane().add(btnPanel,    BorderLayout.SOUTH);

        getRootPane().setDefaultButton(closeBtn);

        // Ctrl/Cmd+F → open search bar
        KeyEvents.builder()
                .keyCode(KeyEvent.VK_F)
                .modifiers(MENU_SHORTCUT_MASK)
                .condition(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .action(Control.command(searchBar::activate))
                .enable(getRootPane());

        Util.bindEscapeToDispose(this);

        this.addPropertyChangeListener("graphicsConfiguration", evt -> {
            SwingUtilities.invokeLater(() -> {
                textArea.revalidate();
                if (textArea.getParserCount() > 0) {
                    textArea.forceReparsing(0);
                }
                textArea.repaint();
            });
        });
    }

    /**
     * Call this before {@code setVisible(true)} to enable the "Annotate" button.
     *
     * @param commitSha SHA of the commit whose tree the file was read from
     * @param filePath  repo-relative file path
     */
    public void enableBlame(String commitSha, String filePath) {
        this.blameCommitSha = commitSha;
        this.blameFilePath  = filePath;
        annotateBtn.setVisible(true);
    }

    /**
     * Opens a non-modal editor for {@code path}. Missing files start empty and are created on save.
     */
    public static void openForEdit(String title, Path path) {
        String content = "";
        boolean readable = true;
        if (Files.exists(path)) {
            try {
                content = Files.readString(path);
            } catch (Exception ex) {
                log.log(Level.WARNING, "Cannot read " + path, ex);
                JOptionPane.showMessageDialog(MainFrame.getInstance(),
                        "Cannot read " + path.getFileName() + ":\n" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                readable = false;
            }
        }
        if (readable) {
            FileViewerWindow viewer = new FileViewerWindow(title, content, path.getFileName().toString());
            viewer.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
            viewer.enableFileEdit(path);
            viewer.setVisible(true);
        }
    }

    /**
     * Turns this viewer into an in-place editor for {@code filePath}. Save writes back to
     * that path (creating the file if needed) and refreshes the working-copy status.
     * Call before {@code setVisible(true)}.
     */
    public void enableFileEdit(Path filePath) {
        this.editPath = filePath;
        this.savedContent = textArea.getText();
        textArea.setEditable(true);
        textArea.setHighlightCurrentLine(true);
        saveBtn.setText("Save");

        KeyEvents.builder()
                .keyCode(KeyEvent.VK_S)
                .modifiers(MENU_SHORTCUT_MASK)
                .condition(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .action(Control.command(this::saveContent))
                .enable(getRootPane());

        // replaces the dispose action bound to Escape by Util.bindEscapeToDispose
        getRootPane().getActionMap().put("ESCAPE", Control.command(this::confirmCloseIfDirty));

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                confirmCloseIfDirty();
            }
        });
    }

    /**
     * Scrolls to {@code lineNo} (1-based) and highlights that line in amber.
     * Call after {@code setVisible(true)} or from {@code SwingUtilities.invokeLater}.
     */
    public void scrollToAndHighlight(int lineNo) {
        if (lineNo <= 0) return;
        try {
            int zeroLine = lineNo - 1;
            textArea.addLineHighlight(zeroLine, new Color(0xFF, 0xD0, 0x00, 120));
            int offset = textArea.getLineStartOffset(zeroLine);
            textArea.setCaretPosition(offset);
            textArea.scrollRectToVisible(textArea.modelToView2D(offset).getBounds());
        } catch (Exception ex) {
            log.fine("scrollToAndHighlight failed at line " + lineNo + ": " + ex.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void toggleAnnotation() {
        if (!annotated.is()) {
            blameScrollPane.setVisible(false);
            centerPanel.revalidate();
            return;
        }

        if (blameLoaded) {
            blameScrollPane.setVisible(true);
            centerPanel.revalidate();
            return;
        }

        blameLoading.set(true);
        annotateBtn.setText("Loading…");

        ProgressWorker.builder()
                .task(() -> Context.getGitRepoService()
                        .getBlameAnnotations(blameCommitSha, blameFilePath))
                .onDone(() -> {
                    blameLoading.set(false);
                    annotateBtn.setText("Annotate");
                })
                .onResult(annotations -> {
                    blameTextArea.setText(String.join("\n", annotations));
                    blameTextArea.setCaretPosition(0);

                    // Fit blame panel to its content width
                    int charWidth = blameTextArea.getFontMetrics(blameTextArea.getFont())
                            .charWidth('m');
                    int maxLen = annotations.stream()
                            .mapToInt(String::length).max().orElse(36);
                    int panelW = charWidth * (maxLen + 2) + 12;
                    blameScrollPane.setPreferredSize(
                            new Dimension(panelW, blameScrollPane.getHeight()));

                    blameLoaded = true;
                    blameScrollPane.setVisible(true);
                    centerPanel.revalidate();
                })
                .onException(ex -> {
                    log.log(Level.WARNING, "Blame load failed", ex);
                    annotated.set(false);
                    JOptionPane.showMessageDialog(this,
                            "Blame unavailable: " + ex.toString(),
                            "Annotate", JOptionPane.WARNING_MESSAGE);
                })
                .execute();
    }

    /** Extracts the 7-char commit SHA from the annotation line under the given point. */
    private String extractShaFromBlamePoint(Point p) {
        try {
            int offset = blameTextArea.viewToModel2D(p);
            int line   = blameTextArea.getLineOfOffset(offset);
            int start  = blameTextArea.getLineStartOffset(line);
            int end    = blameTextArea.getLineEndOffset(line);
            String lineText = blameTextArea.getText(start, end - start);
            if (lineText == null || lineText.length() < 7) return null;
            String sha = lineText.substring(0, 7).trim();
            return sha.isEmpty() ? null : sha;
        } catch (Exception ex) {
            log.fine("Failed to extract blame SHA: " + ex.getMessage());
            return null;
        }
    }

    /** Brings the main window to front and navigates the history view to the given short SHA. */
    private void navigateToHistoryCommit(String sha) {
        javax.swing.JFrame mf = MainFrame.getInstance();
        if (mf instanceof MainFrame mainFrame) {
            mainFrame.toFront();
            mainFrame.showCommitInHistory(sha);
        }
    }

    private void close() {
        if (editPath != null) {
            confirmCloseIfDirty();
        } else {
            dispose();
        }
    }

    private void confirmCloseIfDirty() {
        if (isDirty()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Save changes to " + suggestedFileName + "?",
                    "Unsaved changes",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                if (saveContent()) {
                    dispose();
                }
            } else if (choice == JOptionPane.NO_OPTION) {
                dispose();
            }
        } else {
            dispose();
        }
    }

    private boolean isDirty() {
        return editPath != null && savedContent != null && !textArea.getText().equals(savedContent);
    }

    private boolean saveContent() {
        boolean saved = false;
        if (editPath != null) {
            try {
                if (editPath.getParent() != null) {
                    Files.createDirectories(editPath.getParent());
                }
                Files.writeString(editPath, textArea.getText(), StandardCharsets.UTF_8);
                savedContent = textArea.getText();
                Context.updateStatus(null, true);
                saved = true;
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                        "Cannot save file:\n" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            try {
                File file = Dialogs.select()
                        .files()
                        .owner(this)
                        .title("Save as")
                        .filter(new FileNameExtensionFilter(
                                "Patch / Diff files (*.patch, *.diff)", "patch", "diff"))
                        .confirmOverwrite(false)
                        .selectFileToSave(suggestedFileName);
                Files.writeString(file.toPath(), textArea.getText(), StandardCharsets.UTF_8);
                saved = true;
            } catch (CancelException ex) {
                // cancelled
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                        "Cannot save file:\n" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
        return saved;
    }
}
