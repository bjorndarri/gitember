package com.az.gitember.dialog;

import com.az.gitember.data.ScmStat;
import com.az.gitember.service.Context;
import com.az.gitember.service.GitRepoStatService;
import com.az.gitember.ui.MainFrame;
import com.az.gitember.ui.StatusBar;
import com.az.gitember.ui.stat.MonthlyBarChartPanel;
import com.az.gitember.ui.stat.PieChartPanel;
import is.codion.common.model.component.table.FilterTableModel.TableColumns;
import is.codion.common.model.worker.ProgressWorker;
import is.codion.common.model.worker.ProgressWorker.ProgressReporter;
import is.codion.common.reactive.state.State;
import is.codion.common.reactive.value.Value;
import is.codion.swing.common.model.component.table.SwingFilterTableModel;
import is.codion.swing.common.ui.component.table.FilterTable;
import is.codion.swing.common.ui.control.Control;
import is.codion.swing.common.ui.key.KeyEvents;
import org.apache.commons.collections4.CollectionUtils;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revplot.PlotLane;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static is.codion.swing.common.ui.component.Components.*;

/**
 * Statistics window (JFrame – maximizable).
 *
 * <p>Layout (BorderLayout):</p>
 * <ul>
 *   <li>NORTH  – control bar: depth spinner + Compute button + month progress bar</li>
 *   <li>CENTER – JSplitPane: scalable pie chart (left) | summary table (right)</li>
 *   <li>SOUTH  – monthly stacked-bar chart (~220 px)</li>
 * </ul>
 * Status and progress messages are routed to the application's main {@link StatusBar}.
 */
public class StatDialog extends JFrame {

    private static final Logger log = Logger.getLogger(StatDialog.class.getName());

    private final Value<Integer> depth = Value.nonNull(12);
    private final State computing = State.state();

    private final JProgressBar progressBar;   // month-level progress inside the dialog

    private final PieChartPanel pieChart;
    private final FilterTable<StatRow, String> table;
    private final MonthlyBarChartPanel barChart;

    private final StatusBar statusBar;
    private ProgressWorker<List<ScmStat>, Progress> worker;

    public StatDialog(Frame owner) {
        super("Statistics");
        this.statusBar = MainFrame.getInstance().getStatusBar();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                close();
            }
        });
        setSize(900, 680);
        setMinimumSize(new Dimension(700, 500));
        setLocationRelativeTo(owner);
        setResizable(true);

        // ── Control bar (no Close button – use window X) ─────────────────────
        JPanel controlBar = panel()
                .layout(new FlowLayout(FlowLayout.LEFT, 8, 6))
                .add(label("Depth:"))
                .add(integerSpinner()
                        .range(1, 120)
                        .stepSize(1)
                        .link(depth)
                        .preferredWidth(70))
                .add(label("months"))
                .add(button()
                        .control(Control.builder()
                                .command(this::startCompute)
                                .caption("Compute")
                                .enabled(computing.not())))
                .build();

        // determinate, with a border, as a JProgressBar is by default
        progressBar = progressBar()
                .indeterminate(false)
                .borderPainted(true)
                .stringPainted(true)
                .string("")
                .build();

        // ── Pie chart ────────────────────────────────────────────────────────
        pieChart = new PieChartPanel();
        pieChart.setBorder(BorderFactory.createTitledBorder("Codebase Ownership (HEAD)"));

        // ── Summary table ────────────────────────────────────────────────────
        table = FilterTable.builder()
                .model(SwingFilterTableModel.<StatRow, String>builder()
                        .columns(new StatColumns())
                        .build())
                .fillsViewportHeight(true)
                .columnReordering(false)
                .autoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS)
                .build();

        // ── Monthly bar chart ─────────────────────────────────────────────────
        barChart = new MonthlyBarChartPanel();
        barChart.setBorder(BorderFactory.createTitledBorder("Monthly Contributions"));
        barChart.setPreferredSize(new Dimension(800, 220));

        // ── Layout ────────────────────────────────────────────────────────────
        getContentPane().add(borderLayoutPanel()
                .layout(new BorderLayout(0, 4))
                .border(BorderFactory.createEmptyBorder(4, 4, 4, 4))
                .north(controlBar)
                .center(splitPane()
                        .orientation(JSplitPane.HORIZONTAL_SPLIT)
                        .leftComponent(pieChart)
                        .rightComponent(scrollPane()
                                .view(table)
                                .border(BorderFactory.createTitledBorder("Summary")))
                        .resizeWeight(0.50)
                        .onBuild(split -> split.setDividerLocation(420)))
                // bar chart above full-width progress bar
                .south(borderLayoutPanel()
                        .layout(new BorderLayout(0, 2))
                        .center(barChart)
                        .south(progressBar))
                .build());

        KeyEvents.builder()
                .keyCode(KeyEvent.VK_ESCAPE)
                .condition(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .action(Control.command(this::close))
                .enable(getRootPane());
    }

    private void close() {
        cancelWorker();
        dispose();
    }

    private void cancelWorker() {
        if (worker != null && !worker.done()) worker.cancel(true);
    }

    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void startCompute() throws Exception {
        statusBar.setStatus("Statistics: loading history ...");
        Context.updatePlotCommitList(null, true, null);
        //GetRepoStatService().computeStats(project.getProjectHomeFolder());
        if (CollectionUtils.isEmpty(Context.getPlotCommitList())) {
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this),
                    "Not enough information to collect statistics .",
                    "No statistics", JOptionPane.WARNING_MESSAGE);
        } else if (new GitRepoStatService().computeStats(Context.getActiveProject()).hasChanges()) {
            JEditorPane ep = new JEditorPane("text/html",
                    "<html><body>"
                            +  "Repository "
                            + Context.getProjectFolder()
                            + " has modified or conflicted files. <br/>Commit / resolve / stash files first before getting statistics"
                            + "</body></html>");
            ep.setEditable(false);
            ep.setOpaque(false);
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this),
                    ep,
                    "Uncommited changes", JOptionPane.WARNING_MESSAGE);
        } else {
            int months = depth.get();
            computing.set(true);
            progressBar.setValue(0);
            progressBar.setString("0 / " + months);
            statusBar.setStatus("Computing statistics…");
            statusBar.showProgress(true);

            worker = ProgressWorker.builder()
                    .task((ProgressReporter<Progress> reporter) -> computeStats(months, reporter))
                    .onPublish(chunks -> showProgress(chunks.get(chunks.size() - 1)))
                    .onDone(() -> computing.set(false))
                    .onResult(stats -> {
                        applyResults(stats);
                        finished("Done", "Statistics: complete.");
                    })
                    .onCancelled(() -> finished("Cancelled", "Statistics: cancelled."))
                    .onException(ex -> {
                        log.log(Level.WARNING, "Statistics computation failed", ex);
                        finished("Error", "Statistics error: " + ex.getMessage());
                    })
                    .execute();
        }
    }

    private List<ScmStat> computeStats(int months, ProgressReporter<Progress> reporter) throws Exception {
        @SuppressWarnings("unchecked")
        List<PlotCommit<PlotLane>> monthly =
                Context.getGitRepoService().getLastCommitPerMonth(months, Context.getPlotCommitList());

        if (monthly.isEmpty()) {
            reporter.publish(new Progress("No commits found.", 0, 0));
            return Collections.emptyList();
        }

        int total = monthly.size();

        ProgressMonitor monitor = new ProgressMonitor() {
            @Override
            public void start(int n) {
            }

            @Override
            public void beginTask(String t, int n) {
            }

            @Override
            public void update(int n) {
            }

            @Override
            public void endTask() {
            }

            @Override
            public boolean isCancelled() {
                return Thread.currentThread().isInterrupted();
            }

            @Override
            public void showDuration(boolean e) {
            }
        };

        List<ScmStat> stats = new ArrayList<>();
        for (int i = 0; i < monthly.size(); i++) {
            if (Thread.currentThread().isInterrupted()) break;
            List<PlotCommit<PlotLane>> single = Collections.singletonList(monthly.get(i));
            stats.addAll(Context.getGitRepoService().blameList(single, monitor));

            int idx = i + 1;
            reporter.publish(new Progress(idx + " / " + total + " months…", idx, total));
        }
        return stats;
    }

    private void showProgress(Progress progress) {
        if (progress.months() > 0) {
            progressBar.setMaximum(progress.months());
            progressBar.setValue(progress.month());
            progressBar.setString(progress.month() + " / " + progress.months());
        }
        statusBar.setStatus("Statistics: " + progress.status());
    }

    // after the outcome, since clearProgress() resets the status
    private void finished(String progress, String status) {
        progressBar.setString(progress);
        statusBar.setStatus(status);
        statusBar.clearProgress();
    }

    private void applyResults(List<ScmStat> stats) {
        if (stats == null || stats.isEmpty()) return;

        ScmStat headStat = stats.get(stats.size() - 1);

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(headStat.getTotalLines().entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> authors = sorted.stream().map(Map.Entry::getKey).collect(Collectors.toList());

        Color[] colors = PieChartPanel.buildColors(authors.size());

        Map<String, Integer> lines = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sorted) lines.put(e.getKey(), e.getValue());
        pieChart.setData(lines, colors);

        table.model().items().set(rows(headStat, authors));
        barChart.setData(stats, authors, colors);
    }

    private static List<StatRow> rows(ScmStat stat, List<String> authors) {
        List<StatRow> rows = new ArrayList<>();
        for (String author : authors) {
            int lines = stat.getTotalLines().getOrDefault(author, 0);
            int commits = stat.getLogMap().getOrDefault(author, 0);
            int avg = commits > 0 ? lines / commits : 0;
            rows.add(new StatRow(author, commits, lines, avg));
        }
        return rows;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** The status to show, and the months done of the total, if any */
    private record Progress(String status, int month, int months) {}

    private record StatRow(String developer, int commits, int lines, int average) {}

    private static final class StatColumns implements TableColumns<StatRow, String> {

        private static final String DEVELOPER = "Developer";
        private static final String COMMITS = "Commits";
        private static final String LINES = "Lines";
        private static final String AVERAGE = "Avg Lines/Commit";

        private static final List<String> IDENTIFIERS = List.of(DEVELOPER, COMMITS, LINES, AVERAGE);

        @Override
        public List<String> identifiers() {
            return IDENTIFIERS;
        }

        @Override
        public Class<?> type(String identifier) {
            return identifier.equals(DEVELOPER) ? String.class : Integer.class;
        }

        @Override
        public Object value(StatRow row, String identifier) {
            return switch (identifier) {
                case DEVELOPER -> row.developer();
                case COMMITS -> row.commits();
                case LINES -> row.lines();
                case AVERAGE -> row.average();
                default -> throw new IllegalArgumentException(identifier);
            };
        }
    }
}
