package com.az.gitember.dialog;

import com.az.gitember.data.RemoteRepoParameters;
import com.az.gitember.service.GitemberUtil;
import is.codion.common.reactive.state.ObservableState;
import is.codion.common.reactive.state.State;
import is.codion.common.reactive.value.Value;
import is.codion.swing.common.ui.control.Control;
import org.apache.commons.lang3.StringUtils;

import com.az.gitember.ui.misc.Util;

import javax.swing.*;
import java.awt.*;

import static is.codion.swing.common.ui.component.Components.*;

public class CloneDialog extends JDialog {

    private final Value<String> url = Value.nonNull("");
    private final Value<String> destination = Value.nonNull("");
    private final Value<String> branch = Value.nonNull("");
    private final Value<String> userName = Value.nonNull("");
    private final Value<String> password = Value.nonNull("");
    private final Value<String> token = Value.nonNull("");
    private final Value<Integer> depth = Value.nonNull(100);
    private final State urlSpecified = State.state();
    private final State http = State.state();
    private final State passwordAuth = State.state(true);
    private final State tokenAuth = State.state();
    private final State shallow = State.state();
    private final State withSubmodules = State.state();
    private boolean confirmed = false;

    public CloneDialog(Frame parent) {
        super(parent, "Clone Repository", true);
        setSize(500, 440);
        setLocationRelativeTo(parent);
        setResizable(false);

        destination.set(GitemberUtil.getHomeFolder());
        url.addConsumer(this::onUrlChanged);

        // The auth type selector is shown for HTTPS, hidden for SSH/git@, along with the credential rows
        ObservableState passwordRows = State.and(http, passwordAuth);
        ObservableState tokenRows = State.and(http, tokenAuth);
        ButtonGroup authGroup = new ButtonGroup();

        JButton cloneBtn = button()
                .control(Control.builder()
                        .command(this::onClone)
                        .caption("Clone")
                        .enabled(urlSpecified))
                .build();

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form()
                .border(BorderFactory.createEmptyBorder(15, 15, 10, 15))
                .add(stringField()
                        .label("Repository URL:")
                        .columns(30)
                        .link(url))
                .add(directoryInput()
                        .label("Destination:")
                        .dialogTitle("Select Destination Folder")
                        .filePathField(field -> field
                                .editable(true)
                                .columns(25)
                                .link(destination)))
                .span(panel()
                        .layout(new FlowLayout(FlowLayout.LEFT, 0, 0))
                        .opaque(false)
                        .add(checkBox()
                                .text("Clone last commits only (shallow clone)")
                                .link(shallow))
                        .add((JComponent) Box.createHorizontalStrut(8))
                        .add(integerSpinner()
                                .range(1, 999999)
                                .columns(6)
                                .enabled(shallow)
                                .link(depth)))
                .span(checkBox()
                        .text("With submodules")
                        .link(withSubmodules))
                .add(stringField()
                        .label("Branch:")
                        .columns(20)
                        .toolTipText("Comma separated list of branches and tags refs/heads/master,refs/tags/v1.0.0")
                        .hint("Example: refs/heads/master,refs/tags/v1.0.0")
                        .link(branch))
                .span(panel()
                        .layout(new FlowLayout(FlowLayout.LEFT, 0, 0))
                        .opaque(false)
                        .add(radioButton()
                                .text("Username & Password")
                                .buttonGroup(authGroup)
                                .link(passwordAuth))
                        .add((JComponent) Box.createHorizontalStrut(16))
                        .add(radioButton()
                                .text("Access Token")
                                .buttonGroup(authGroup)
                                .link(tokenAuth))
                        .visible(http))
                .add(stringField()
                        .label("Username:")
                        .columns(20)
                        .visible(passwordRows)
                        .link(userName))
                .add(passwordField()
                        .label("Password:")
                        .columns(20)
                        .visible(passwordRows)
                        .link(password))
                .add(passwordField()
                        .label("Token:")
                        .columns(20)
                        .visible(tokenRows)
                        .link(token))
                .build(), BorderLayout.CENTER);
        getContentPane().add(flowLayoutPanel(FlowLayout.RIGHT)
                .add(cloneBtn)
                .add(button()
                        .control(Control.builder()
                                .command(this::dispose)
                                .caption("Cancel")))
                .build(), BorderLayout.SOUTH);

        getRootPane().setDefaultButton(cloneBtn);
        Util.bindEscapeToDispose(this);
    }

    // -------------------------------------------------------------------------

    private void onUrlChanged(String url) {
        String trimmed = url.trim();
        urlSpecified.set(StringUtils.isNotBlank(trimmed));

        String repoName = repoNameFromUrl(trimmed);
        if (repoName != null) {
            String home = GitemberUtil.getHomeFolder();
            destination.set(home != null ? home + repoName : repoName);
        } else {
            destination.set(GitemberUtil.getHomeFolder());
        }

        http.set(trimmed.startsWith("https:") || trimmed.startsWith("http:"));
    }

    /** Extracts the repository folder name from a clone URL, stripping trailing {@code .git}. */
    private static String repoNameFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        // Strip trailing slashes
        String u = url.stripTrailing();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // Take the last path segment
        int slash = Math.max(u.lastIndexOf('/'), u.lastIndexOf(':'));
        if (slash < 0 || slash == u.length() - 1) return null;
        String name = u.substring(slash + 1);
        if (name.toLowerCase().endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isBlank() ? null : name;
    }

    private void onClone() {
        if (url.get().isBlank()) {
            JOptionPane.showMessageDialog(this, "Repository URL is required",
                    "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (destination.get().isBlank()) {
            JOptionPane.showMessageDialog(this, "Destination folder is required",
                    "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }
        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public RemoteRepoParameters getParameters() {
        RemoteRepoParameters params = new RemoteRepoParameters();
        params.setUrl(url.get().trim());
        params.setDestinationFolder(destination.get().trim());
        params.setBranch(branch.get());
        params.setWithSubmodules(withSubmodules.is());
        if (tokenAuth.is()) {
            params.setAccessToken(token.get());
        } else {
            params.setUserName(userName.get().trim());
            params.setUserPwd(password.get());
        }
        if (shallow.is()) {
            params.setDepth(depth.get());
        }
        return params;
    }
}
