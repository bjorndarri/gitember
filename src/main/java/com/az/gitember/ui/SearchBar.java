package com.az.gitember.ui;

import com.az.gitember.ui.misc.Util;
import is.codion.common.reactive.value.Value;
import is.codion.swing.common.ui.control.Control;
import is.codion.swing.common.ui.key.KeyEvents;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;

import static is.codion.swing.common.ui.component.Components.stringField;

/**
 * Compact inline search bar for one or more {@link RSyntaxTextArea} instances.
 *
 * <p>All registered text areas have their matches highlighted simultaneously
 * ("mark all"). Next / Previous navigation operates on whichever area most
 * recently received keyboard focus.</p>
 *
 * <p>Show with {@link #activate()} (e.g. from a Ctrl+F binding).
 * The bar hides itself on Escape or when the close button is clicked.</p>
 */
class SearchBar extends JPanel {

    private final Value<String> searchText = Value.nonNull("");
    private final JTextField searchField;
    private final JLabel statusLabel;

    private final RSyntaxTextArea[] textAreas;
    private RSyntaxTextArea activeArea;

    SearchBar(RSyntaxTextArea... areas) {
        this.textAreas = areas;
        this.activeArea = areas.length > 0 ? areas[0] : null;

        // Track which pane last had focus so next/prev goes to the right one
        for (RSyntaxTextArea area : areas) {
            area.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { activeArea = area; }
            });
        }

        setLayout(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                UIManager.getColor("Separator.foreground")));




        add(new JLabel("Find:"));

        searchField = stringField()
                .columns(22)
                .link(searchText)
                .action(Control.command(() -> findNext(true)))
                .keyEvent(KeyEvents.builder()
                        .keyCode(KeyEvent.VK_ESCAPE)
                        .action(Control.command(this::close)))
                .build();
        add(searchField);

        JButton prevBtn = Util.createButton("", "Previous match", FontAwesomeSolid.ANGLE_UP, 0, new Dimension(32, 32));
        prevBtn.addActionListener(e -> findNext(false));
        JButton nextBtn = Util.createButton("", "Next match", FontAwesomeSolid.ANGLE_DOWN, 0, new Dimension(32, 32));
        nextBtn.addActionListener(e -> findNext(true));
        JButton closeBtn = Util.createButton("", "Close search [Esc]", FontAwesomeSolid.WINDOW_CLOSE, 0, new Dimension(32, 32));
        closeBtn.addActionListener(e -> close());

        add(prevBtn);
        add(nextBtn);
        add(closeBtn);

        statusLabel = new JLabel("");

        setVisible(false);

        searchText.addListener(this::markAll);
    }

    /** Shows the bar and focuses the search field. */
    void activate() {
        setVisible(true);
        searchField.requestFocusInWindow();
        searchField.selectAll();
        markAll();
    }

    /** Hides the bar, clears highlights, and returns focus to the last active pane. */
    void close() {
        setVisible(false);
        clearHighlights();
        if (activeArea != null) activeArea.requestFocusInWindow();
    }

    public JTextField getSearchField() {
        return searchField;
    }

    public void findNext(boolean forward) {
        String text = searchText.get();
        if (text.isEmpty()) return;
        RSyntaxTextArea target = activeArea != null ? activeArea
                : (textAreas.length > 0 ? textAreas[0] : null);
        if (target == null) return;

        SearchContext ctx = buildContext(forward);
        SearchResult result = SearchEngine.find(target, ctx);
        if (!result.wasFound()) {
            // Wrap around
            target.setCaretPosition(forward ? 0 : target.getDocument().getLength());
            SearchEngine.find(target, ctx);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private SearchContext buildContext(boolean forward) {
        SearchContext ctx = new SearchContext(searchText.get());
        ctx.setSearchForward(forward);
        ctx.setWholeWord(false);
        return ctx;
    }

    private void markAll() {
        String text = searchText.get();
        SearchContext ctx = buildContext(true);
        int total = 0;
        for (RSyntaxTextArea area : textAreas) {
            SearchResult result = SearchEngine.markAll(area, ctx);
            total += result.getMarkedCount();
        }
        if (text.isEmpty()) {
            statusLabel.setText("");
            statusLabel.setForeground(UIManager.getColor("Label.foreground"));
        } else if (total == 0) {
            statusLabel.setText("No matches");
            statusLabel.setForeground(new Color(0xCC3333));
        } else {
            statusLabel.setText(total + " match" + (total != 1 ? "es" : ""));
            statusLabel.setForeground(UIManager.getColor("Label.foreground"));
        }
    }



    private void clearHighlights() {
        SearchContext ctx = new SearchContext("");
        for (RSyntaxTextArea area : textAreas) {
            SearchEngine.markAll(area, ctx);
        }
        statusLabel.setText("");
    }


}