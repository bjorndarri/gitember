package com.az.gitember.ui;

import com.az.gitember.data.Settings;
import com.az.gitember.service.Context;
import com.az.gitember.service.ExtensionMap;
import com.az.gitember.ui.misc.Util;
import is.codion.common.model.worker.ProgressWorker;
import is.codion.common.reactive.state.State;
import is.codion.common.reactive.value.Value;
import is.codion.swing.common.ui.Utilities;
import is.codion.swing.common.ui.component.text.FileInput;
import is.codion.swing.common.ui.control.Control;
import is.codion.swing.common.ui.control.Controls;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.tree.*;
import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static is.codion.swing.common.ui.component.Components.*;

/**
 * Folder comparison window with two side-by-side synchronised trees.
 *
 * <ul>
 *   <li>Left tree shows the left folder; right tree shows the right folder.</li>
 *   <li>Both trees share the same {@link DefaultTreeModel} and stay in sync
 *       (scroll + expand/collapse).</li>
 *   <li>Folder nodes show open/closed folder icons; file nodes show leaf icons.</li>
 *   <li>Left tree greys out RIGHT_ONLY files; right tree greys out LEFT_ONLY files.</li>
 *   <li>Double-click a file node to open {@link DiffViewerWindowTxt} or {@link DiffViewerWindowImg}.</li>
 *   <li>"Differences only" filter and Prev/Next diff navigation.</li>
 * </ul>
 */
public class FolderCompareWindow extends JFrame {

    private static final Logger log = Logger.getLogger(FolderCompareWindow.class.getName());

    // ── Data ──────────────────────────────────────────────────────────────────

    enum EntryStatus {
        IDENTICAL  ("="),
        DIFFERENT  ("≠"),
        LEFT_ONLY  ("<<"),
        RIGHT_ONLY (">>");

        public final String label;
        EntryStatus(String label) { this.label = label; }
    }

    record FolderEntry(String relativePath,
                       String leftAbsPath,
                       String rightAbsPath,
                       EntryStatus status) {}

    // ── UI ────────────────────────────────────────────────────────────────────

    private final JLabel leftHeader  = new JLabel(" ");
    private final JLabel rightHeader = new JLabel(" ");

    private final JButton prevBtn  = Util.createButton("Prev", "Previous difference", FontAwesomeSolid.ARROW_UP);
    private final JButton nextBtn  = Util.createButton("Next", "Next difference",  FontAwesomeSolid.ARROW_DOWN);
    private final JLabel  statsLbl = new JLabel(" ");

    private final JTree          leftTree;
    private final JTree          rightTree;
    private       DefaultTreeModel treeModel;

    // ── State ─────────────────────────────────────────────────────────────────

    private List<FolderEntry>              allEntries     = Collections.emptyList();
    private List<DefaultMutableTreeNode>   diffNodes      = Collections.emptyList();
    private int                            currentDiffIdx = -1;
    private boolean                        syncingExpansion  = false;
    private boolean                        syncingScroll     = false;
    private boolean                        syncingSelection  = false;

    private final Value<String> leftPath     = Value.nonNull("");
    private final Value<String> rightPath    = Value.nonNull("");
    private final State         scanning     = State.state();
    private final State         diffsOnly    = State.state();
    private final State         diffsPresent = State.state();
    // what can be opened for the entry selected in the trees, whose selection is synchronised
    private final State diffSelected      = State.state();
    private final State leftFileSelected  = State.state();
    private final State rightFileSelected = State.state();

    // ── Construction ──────────────────────────────────────────────────────────

    public FolderCompareWindow() {
        super("Compare Folders");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

        setSize(screenSize.width - 60, screenSize.height - 80);
        setLocationRelativeTo(MainFrame.getInstance());

        treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("root"));
        Controls contextMenu = contextMenuControls();
        leftTree  = makeTree(new LeftRenderer(), contextMenu);
        rightTree = makeTree(new RightRenderer(), contextMenu);

        wireExpansionSync(leftTree,  rightTree);
        wireExpansionSync(rightTree, leftTree);
        wireSelectionSync(leftTree,  rightTree);
        wireSelectionSync(rightTree, leftTree);

        leftTree.addTreeSelectionListener(e -> selectionChanged());

        Utilities.enabled(diffsPresent, prevBtn, nextBtn);
        diffsOnly.addListener(this::applyFilter);
        prevBtn.addActionListener(e -> navigateDiff(-1));
        nextBtn.addActionListener(e -> navigateDiff(+1));

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(buildTopBar(),   BorderLayout.NORTH);
        getContentPane().add(buildSplit(),    BorderLayout.CENTER);
        getContentPane().add(buildLegend(),  BorderLayout.SOUTH);

        Util.bindEscapeToDispose(this);
    }

    /** Pre-fill both paths and start the scan immediately (used by CLI invocation). */
    public void compare(String leftPath, String rightPath) {
        this.leftPath.set(leftPath);
        this.rightPath.set(rightPath);
        startScan();
    }

    // ── Widget factories ──────────────────────────────────────────────────────

    /**
     * TreeUI that paints full-row backgrounds from the left edge (x=0) before each row,
     * so selection and status highlights span the entire row and are not overwritten.
     */
    private static final class FullRowTreeUI extends BasicTreeUI {
        private final BaseRenderer rowRenderer;

        FullRowTreeUI(BaseRenderer renderer) {
            this.rowRenderer = renderer;
        }

        @Override
        protected void paintRow(Graphics g, Rectangle clipBounds, Insets insets,
                Rectangle bounds, TreePath path, int row, boolean isExpanded,
                boolean hasBeenExpanded, boolean isLeaf) {
            // Paint full row background from left edge before painting row content.
            Color c = tree.isRowSelected(row)
                    ? UIManager.getColor("Tree.selectionBackground")
                    : rowBackgroundForPath(path);
            if (c != null) {
                g.setColor(c);
                g.fillRect(0, bounds.y, tree.getWidth(), bounds.height);
            }
            super.paintRow(g, clipBounds, insets, bounds, path, row, isExpanded, hasBeenExpanded, isLeaf);
        }

        private Color rowBackgroundForPath(TreePath path) {
            Object last = path.getLastPathComponent();
            if (!(last instanceof DefaultMutableTreeNode node)) return null;
            if (!(node.getUserObject() instanceof NodeData data)) return null;
            Color c = rowRenderer.bg(data.effectiveStatus());
            return c != null ? c : tree.getBackground();
        }
    }

    private JTree makeTree(BaseRenderer renderer, Controls contextMenu) {
        return tree()
                .model(treeModel)
                .ui(new FullRowTreeUI(renderer))
                .rootVisible(false)
                .showsRootHandles(true)
                .rowHeight(22)
                .cellRenderer(renderer)
                .selectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION)
                // the selections are synced, so leftTree holds the double-clicked node
                .doubleClick(Control.command(() -> openEntry(entryFromTree(leftTree))))
                .popupControls(tree -> contextMenu)
                .build();
    }

    private JPanel buildTopBar() {
        // the inputs share the width, the compare button between them
        JPanel pathBar = form()
                .columns(2)
                .border(BorderFactory.createEmptyBorder(6, 8, 4, 8))
                .add(pathInput(leftPath))
                .add(button()
                        .control(Control.builder()
                                .command(this::startScan)
                                .caption("Compare")
                                .enabled(scanning.not())), pathInput(rightPath))
                .build();

        JPanel filterBar = panel()
                .layout(new FlowLayout(FlowLayout.LEFT, 6, 2))
                .add(toggleButton()
                        .text("Differences only")
                        .link(diffsOnly))
                .add(new JSeparator(SwingConstants.VERTICAL))
                .add(prevBtn)
                .add(nextBtn)
                .add((JComponent) Box.createHorizontalStrut(12))
                .add(statsLbl)
                .build();

        return borderLayoutPanel()
                .layout(new BorderLayout())
                .north(pathBar)
                .south(filterBar)
                .build();
    }

    private static FileInput.Builder<Path> pathInput(Value<String> path) {
        return directoryInput()
                .dialogTitle("Select folder")
                .filePathField(field -> field
                        .editable(true)
                        .link(path)
                        .onBuild(f -> CompareFilesDialog.attachFileDrop(f, true)));
    }

    private JSplitPane buildSplit() {
        styleHeader(leftHeader);
        styleHeader(rightHeader);

        JScrollPane leftScroll  = scrollPane()
                .view(leftTree)
                .build();
        JScrollPane rightScroll = scrollPane()
                .view(rightTree)
                .build();

        // Sync vertical scrolling
        leftScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!syncingScroll) { syncingScroll = true;
                rightScroll.getVerticalScrollBar().setValue(e.getValue()); syncingScroll = false; }
        });
        rightScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!syncingScroll) { syncingScroll = true;
                leftScroll.getVerticalScrollBar().setValue(e.getValue()); syncingScroll = false; }
        });

        return splitPane()
                .orientation(JSplitPane.HORIZONTAL_SPLIT)
                .leftComponent(borderLayoutPanel()
                        .layout(new BorderLayout())
                        .north(leftHeader)
                        .center(leftScroll))
                .rightComponent(borderLayoutPanel()
                        .layout(new BorderLayout())
                        .north(rightHeader)
                        .center(rightScroll))
                .resizeWeight(0.5)
                .dividerSize(5)
                .build();
    }

    private static void styleHeader(JLabel lbl) {
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 11f));
        lbl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0,
                        UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        lbl.setOpaque(true);
    }

    private void wireExpansionSync(JTree source, JTree target) {
        source.addTreeExpansionListener(new TreeExpansionListener() {
            @Override public void treeExpanded(TreeExpansionEvent e) {
                if (!syncingExpansion) { syncingExpansion = true;
                    target.expandPath(e.getPath()); syncingExpansion = false; }
            }
            @Override public void treeCollapsed(TreeExpansionEvent e) {
                if (!syncingExpansion) { syncingExpansion = true;
                    target.collapsePath(e.getPath()); syncingExpansion = false; }
            }
        });
    }

    private void wireSelectionSync(JTree source, JTree target) {
        source.addTreeSelectionListener(e -> {
            if (!syncingSelection) {
                syncingSelection = true;
                target.setSelectionPath(e.getPath());
                if (e.getPath() != null) target.scrollPathToVisible(e.getPath());
                syncingSelection = false;
            }
        });
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    private void startScan() {
        String leftStr  = leftPath.get().trim();
        String rightStr = rightPath.get().trim();
        if (leftStr.isEmpty() || rightStr.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select both folders.",
                    "Compare Folders", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path leftRoot  = Path.of(leftStr);
        Path rightRoot = Path.of(rightStr);
        if (!Files.isDirectory(leftRoot) || !Files.isDirectory(rightRoot)) {
            JOptionPane.showMessageDialog(this, "Both paths must be directories.",
                    "Compare Folders", JOptionPane.WARNING_MESSAGE);
            return;
        }

        scanning.set(true);
        statsLbl.setText("Scanning…");
        treeModel.setRoot(new DefaultMutableTreeNode("root"));
        diffsPresent.set(false);

        ProgressWorker.builder()
                .task(() -> scan(leftRoot, rightRoot))
                .onDone(() -> scanning.set(false))
                .onResult(entries -> {
                    allEntries = entries;
                    currentDiffIdx = -1;
                    leftHeader.setText(leftStr);
                    rightHeader.setText(rightStr);
                    applyFilter();
                    updateStats();
                })
                .onException(ex -> {
                    log.log(Level.WARNING, "Folder scan failed", ex);
                    statsLbl.setText("Error: " + ex.toString());
                })
                .execute();
    }

    private List<FolderEntry> scan(Path leftRoot, Path rightRoot) throws Exception {
        Set<String> leftRel  = collectRelativePaths(leftRoot);
        Set<String> rightRel = collectRelativePaths(rightRoot);
        Set<String> all = new TreeSet<>(leftRel);
        all.addAll(rightRel);

        List<FolderEntry> entries = new ArrayList<>(all.size());
        for (String rel : all) {
            boolean inLeft  = leftRel.contains(rel);
            boolean inRight = rightRel.contains(rel);
            String  leftAbs  = inLeft  ? leftRoot.resolve(rel).toString()  : null;
            String  rightAbs = inRight ? rightRoot.resolve(rel).toString() : null;
            EntryStatus status;
            if (inLeft && inRight) {
                status = Files.mismatch(Path.of(leftAbs), Path.of(rightAbs)) == -1L
                        ? EntryStatus.IDENTICAL : EntryStatus.DIFFERENT;
            } else {
                status = inLeft ? EntryStatus.LEFT_ONLY : EntryStatus.RIGHT_ONLY;
            }
            entries.add(new FolderEntry(rel, leftAbs, rightAbs, status));
        }
        return entries;
    }

    private Set<String> collectRelativePaths(Path root) throws Exception {
        if (!Files.exists(root)) return Collections.emptySet();
        Set<String> ignore = effectiveIgnoreExtensions();
        Set<String> paths  = new TreeSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!ignore.contains(extensionOf(file.getFileName().toString())))
                    paths.add(root.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        return paths;
    }

    private static Set<String> effectiveIgnoreExtensions() {
        Settings s = Context.getSettings();
        return s != null ? s.getEffectiveIgnoreCompareFiles() : Settings.DEFAULT_IGNORE_COMPARE_FILES;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    // ── Tree model building ───────────────────────────────────────────────────

    private DefaultMutableTreeNode buildTreeModel(List<FolderEntry> entries) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        Map<String, DefaultMutableTreeNode> dirNodes = new LinkedHashMap<>();

        for (FolderEntry entry : entries) {
            String[] segments = entry.relativePath().split("/");
            DefaultMutableTreeNode parent = root;
            StringBuilder soFar = new StringBuilder();

            for (int i = 0; i < segments.length - 1; i++) {
                if (soFar.length() > 0) soFar.append('/');
                soFar.append(segments[i]);
                String key = soFar.toString();
                DefaultMutableTreeNode dir = dirNodes.get(key);
                if (dir == null) {
                    dir = new DefaultMutableTreeNode(new NodeData(segments[i], null));
                    dirNodes.put(key, dir);
                    parent.add(dir);
                }
                parent = dir;
            }
            parent.add(new DefaultMutableTreeNode(
                    new NodeData(segments[segments.length - 1], entry)));
        }

        propagateStatus(root);
        return root;
    }

    private EntryStatus propagateStatus(DefaultMutableTreeNode node) {
        NodeData data = (node.getUserObject() instanceof NodeData nd) ? nd : null;
        if (data != null && data.isFile()) return data.entry.status();

        EntryStatus worst = EntryStatus.IDENTICAL;
        for (int i = 0; i < node.getChildCount(); i++) {
            EntryStatus cs = propagateStatus((DefaultMutableTreeNode) node.getChildAt(i));
            if (statusPriority(cs) > statusPriority(worst)) worst = cs;
        }
        if (data != null) data.propagatedStatus = worst;
        return worst;
    }

    private static int statusPriority(EntryStatus s) {
        return switch (s) {
            case IDENTICAL  -> 0;
            case RIGHT_ONLY -> 1;
            case LEFT_ONLY  -> 2;
            case DIFFERENT  -> 3;
        };
    }

    // ── Filter & navigation ───────────────────────────────────────────────────

    private void applyFilter() {
        List<FolderEntry> visible = diffsOnly.is()
                ? allEntries.stream().filter(e -> e.status() != EntryStatus.IDENTICAL).toList()
                : allEntries;

        DefaultMutableTreeNode newRoot = buildTreeModel(visible);
        treeModel.setRoot(newRoot);

        // Expand all in both trees
        for (int i = 0; i < leftTree.getRowCount();  i++) leftTree.expandRow(i);
        for (int i = 0; i < rightTree.getRowCount(); i++) rightTree.expandRow(i);

        // Collect diff leaf nodes for prev/next navigation
        diffNodes = new ArrayList<>();
        Enumeration<?> en = newRoot.preorderEnumeration();
        while (en.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) en.nextElement();
            if (node.isLeaf() && node.getUserObject() instanceof NodeData data
                    && data.isFile() && data.entry.status() != EntryStatus.IDENTICAL)
                diffNodes.add(node);
        }
        currentDiffIdx = -1;
        diffsPresent.set(!diffNodes.isEmpty());
    }

    private void navigateDiff(int direction) {
        if (diffNodes.isEmpty()) return;
        currentDiffIdx = direction > 0
                ? (currentDiffIdx < diffNodes.size() - 1 ? currentDiffIdx + 1 : 0)
                : (currentDiffIdx > 0 ? currentDiffIdx - 1 : diffNodes.size() - 1);

        DefaultMutableTreeNode node = diffNodes.get(currentDiffIdx);
        TreePath path = new TreePath(node.getPath());
        leftTree.setSelectionPath(path);
        leftTree.scrollPathToVisible(path);
        rightTree.setSelectionPath(path);
        rightTree.scrollPathToVisible(path);
    }

    private void updateStats() {
        long total     = allEntries.size();
        long identical = allEntries.stream().filter(e -> e.status() == EntryStatus.IDENTICAL).count();
        long different = allEntries.stream().filter(e -> e.status() == EntryStatus.DIFFERENT).count();
        long leftOnly  = allEntries.stream().filter(e -> e.status() == EntryStatus.LEFT_ONLY).count();
        long rightOnly = allEntries.stream().filter(e -> e.status() == EntryStatus.RIGHT_ONLY).count();
        statsLbl.setText(total + " files  |  " + identical + " identical  |  "
                + different + " different  |  " + leftOnly + " left only  |  " + rightOnly + " right only");
    }

    // ── Open entries ──────────────────────────────────────────────────────────

    private FolderEntry entryFromTree(JTree tree) {
        TreePath p = tree.getSelectionPath();
        if (p == null) return null;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) p.getLastPathComponent();
        return node.getUserObject() instanceof NodeData d && d.isFile() ? d.entry : null;
    }

    private void openEntry(FolderEntry entry) {
        if (entry == null) return;
        switch (entry.status()) {
            case DIFFERENT, IDENTICAL -> openDiff(entry);
            case LEFT_ONLY  -> openSingle(entry.leftAbsPath(),  entry.relativePath());
            case RIGHT_ONLY -> openSingle(entry.rightAbsPath(), entry.relativePath());
        }
    }

    private void openDiff(FolderEntry entry) {
        if (entry != null && entry.leftAbsPath() != null && entry.rightAbsPath() != null) {
            File lf = new File(entry.leftAbsPath());
            File rf = new File(entry.rightAbsPath());
            boolean image = ExtensionMap.isImage(entry.relativePath())
                    && ExtensionMap.isImage(rf.getName());
            if (image) {
                new DiffViewerWindowImg(entry.relativePath(),
                        leftPath.get().trim(), lf,
                        rightPath.get().trim(), rf).setVisible(true);
            } else if (CompareFilesDialog.looksLikeBinary(lf) || CompareFilesDialog.looksLikeBinary(rf)) {
                JOptionPane.showMessageDialog(this, entry.relativePath() + " appears to be binary.",
                        "Binary file", JOptionPane.WARNING_MESSAGE);
            } else {
                ProgressWorker.builder()
                        .task(() -> new String[]{ Files.readString(lf.toPath()), Files.readString(rf.toPath()) })
                        .onResult(t -> new DiffViewerWindowTxt(entry.relativePath(),
                                leftPath.get().trim(),  t[0],
                                rightPath.get().trim(), t[1]).setVisible(true))
                        .onException(ex -> {
                            log.log(Level.WARNING, "Failed to open diff", ex);
                            JOptionPane.showMessageDialog(this,
                                    "Cannot open diff: " + ex.toString(), "Error", JOptionPane.ERROR_MESSAGE);
                        })
                        .execute();
            }
        }
    }

    private void openSingle(String absPath, String relPath) {
        if (absPath == null) return;
        File f = new File(absPath);
        if (CompareFilesDialog.looksLikeBinary(f)) {
            JOptionPane.showMessageDialog(this, relPath + " is a binary file.",
                    "Binary file", JOptionPane.WARNING_MESSAGE);
            return;
        }
        ProgressWorker.builder()
                .task(() -> Files.readString(f.toPath()))
                .onResult(content -> new FileViewerWindow(relPath, content, relPath).setVisible(true))
                .onException(ex -> log.log(Level.WARNING, "Failed to open file", ex))
                .execute();
    }

    // ── Context menu ──────────────────────────────────────────────────────────

    private Controls contextMenuControls() {
        return Controls.builder()
                .control(Control.builder()
                        .command(() -> openDiff(entryFromTree(leftTree)))
                        .caption("Open diff")
                        .enabled(diffSelected))
                .separator()
                .control(Control.builder()
                        .command(() -> {
                            FolderEntry en = entryFromTree(leftTree);
                            if (en != null) openSingle(en.leftAbsPath(), en.relativePath());
                        })
                        .caption("Open left file")
                        .enabled(leftFileSelected))
                .control(Control.builder()
                        .command(() -> {
                            FolderEntry en = entryFromTree(leftTree);
                            if (en != null) openSingle(en.rightAbsPath(), en.relativePath());
                        })
                        .caption("Open right file")
                        .enabled(rightFileSelected))
                .build();
    }

    private void selectionChanged() {
        FolderEntry en = entryFromTree(leftTree);
        diffSelected.set(en != null && en.leftAbsPath() != null && en.rightAbsPath() != null);
        leftFileSelected.set(en != null && en.leftAbsPath() != null);
        rightFileSelected.set(en != null && en.rightAbsPath() != null);
    }

    // ── Legend ────────────────────────────────────────────────────────────────

    private static JPanel buildLegend() {
        return panel()
                .layout(new FlowLayout(FlowLayout.LEFT, 10, 2))
                .border(BorderFactory.createEmptyBorder(2, 6, 4, 6))
                .add(chip(SyntaxStyleUtil.changedBg(), "Different  " + EntryStatus.DIFFERENT.label))
                .add(chip(SyntaxStyleUtil.deletedBg(), "Left only  " + EntryStatus.LEFT_ONLY.label))
                .add(chip(SyntaxStyleUtil.addedBg(),   "Right only  " + EntryStatus.RIGHT_ONLY.label))
                .add(chip(null,                           "Identical"))
                .build();
    }

    private static JLabel chip(Color bg, String text) {
        JLabel l = label(" " + text + " ")
                .opaque(bg != null)
                .font(font -> font.deriveFont(Font.PLAIN, 11f))
                .border(BorderFactory.createLineBorder(Color.GRAY))
                .build();
        if (bg != null) l.setBackground(bg);
        return l;
    }

    // ── NodeData ──────────────────────────────────────────────────────────────

    static class NodeData {
        final String      name;
        final FolderEntry entry;            // null → directory
        EntryStatus       propagatedStatus; // computed by propagateStatus()

        NodeData(String name, FolderEntry entry) {
            this.name = name;
            this.entry = entry;
            this.propagatedStatus = entry != null ? entry.status() : EntryStatus.IDENTICAL;
        }

        boolean isFile() { return entry != null; }
        EntryStatus effectiveStatus() { return isFile() ? entry.status() : propagatedStatus; }
    }

    // ── Cell renderers ────────────────────────────────────────────────────────

    /**
     * Base renderer. Inherits folder-open / folder-closed / leaf icons from
     * {@link DefaultTreeCellRenderer} automatically via the {@code expanded} and
     * {@code leaf} parameters — no explicit icon override needed.
     */
    private static abstract class BaseRenderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            // super sets open/closed folder icon (non-leaf) or leaf icon based on expanded/leaf
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            // Non-opaque so full-row background painted by FullRowTree shows through.
            setOpaque(false);

            if (!(value instanceof DefaultMutableTreeNode node)) return this;
            if (!(node.getUserObject() instanceof NodeData data)) return this;

            EntryStatus status = data.effectiveStatus();
            setText(label(data, status));

            if (!sel) {
                Color bg = bg(status);
                setBackground(bg != null ? bg : tree.getBackground());
                setForeground(fg(status));
            }
            return this;
        }

        /** Text to display for this node. */
        protected abstract String label(NodeData data, EntryStatus status);
        /** Row background colour, or {@code null} for default. */
        protected abstract Color  bg(EntryStatus status);
        /** Row foreground colour. */
        protected abstract Color  fg(EntryStatus status);
    }

    /** Left-side tree: highlights LEFT_ONLY (red), greys out RIGHT_ONLY. */
    private static class LeftRenderer extends BaseRenderer {
        @Override protected String label(NodeData data, EntryStatus status) {
            String n = data.isFile() ? data.name : data.name + "/";
            return switch (status) {
                case DIFFERENT  -> n + "  " + EntryStatus.DIFFERENT.label;
                case LEFT_ONLY  -> n + "  " + EntryStatus.LEFT_ONLY.label;
                default         -> n;
            };
        }
        @Override protected Color bg(EntryStatus status) {
            return switch (status) {
                case DIFFERENT  -> SyntaxStyleUtil.changedBg();
                case LEFT_ONLY  -> SyntaxStyleUtil.deletedBg();
                default         -> null;
            };
        }
        @Override protected Color fg(EntryStatus status) {
            return switch (status) {
                case DIFFERENT  -> SyntaxStyleUtil.rowFgChanged();
                case LEFT_ONLY  -> SyntaxStyleUtil.rowFgDeleted();
                case RIGHT_ONLY -> UIManager.getColor("Label.disabledForeground");
                default         -> UIManager.getColor("Tree.foreground");
            };
        }
    }

    /** Right-side tree: highlights RIGHT_ONLY (green), greys out LEFT_ONLY. */
    private static class RightRenderer extends BaseRenderer {
        @Override protected String label(NodeData data, EntryStatus status) {
            String n = data.isFile() ? data.name : data.name + "/";
            return switch (status) {
                case DIFFERENT  -> n + "  " + EntryStatus.DIFFERENT.label;
                case RIGHT_ONLY -> n + "  " + EntryStatus.RIGHT_ONLY.label;
                default         -> n;
            };
        }
        @Override protected Color bg(EntryStatus status) {
            return switch (status) {
                case DIFFERENT  -> SyntaxStyleUtil.changedBg();
                case RIGHT_ONLY -> SyntaxStyleUtil.addedBg();
                default         -> null;
            };
        }
        @Override protected Color fg(EntryStatus status) {
            return switch (status) {
                case DIFFERENT  -> SyntaxStyleUtil.rowFgChanged();
                case RIGHT_ONLY -> SyntaxStyleUtil.rowFgAdded();
                case LEFT_ONLY  -> UIManager.getColor("Label.disabledForeground");
                default         -> UIManager.getColor("Tree.foreground");
            };
        }
    }
}
