package com.az.gitember.dialog;

import com.az.gitember.data.Project;

import com.az.gitember.ui.misc.Util;
import is.codion.swing.common.ui.control.Control;

import javax.swing.*;
import java.awt.*;

import static is.codion.swing.common.ui.component.Components.*;

/**
 * Dialog for configuring per-project remote credentials.
 * Supports both classic username/password and access-token (GitHub PAT, GitLab token, etc.).
 * The access token takes priority over username/password in transport commands.
 */
public class CredentialsDialog extends JDialog {

    private final JPasswordField tokenField;
    private final JTextField userField;
    private final JPasswordField pwdField;
    private boolean confirmed = false;

    public CredentialsDialog(Frame parent, Project project) {
        super(parent, "Repository Credentials", true);
        setSize(460, 260);
        setLocationRelativeTo(parent);
        setResizable(false);

        tokenField = passwordField()
                .label("Access Token:")
                .value(project.getAccessToken())
                .columns(25)
                .build();
        userField = stringField()
                .label("Username:")
                .value(project.getUserName())
                .columns(25)
                .build();
        pwdField = passwordField()
                .label("Password:")
                .value(project.getUserPwd())
                .columns(25)
                .build();

        JButton okBtn = button()
                .control(Control.builder()
                        .command(() -> { confirmed = true; dispose(); })
                        .caption("Save"))
                .build();

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form()
                .border(BorderFactory.createEmptyBorder(15, 15, 10, 15))
                .span(label("<html>Access token takes priority over username/password.<br>"
                        + "Leave token blank to use username &amp; password instead.</html>")
                        .foreground(UIManager.getColor("Label.disabledForeground")))
                .add(tokenField)
                .span(new JSeparator())
                .add(userField)
                .add(pwdField)
                .build(), BorderLayout.CENTER);
        getContentPane().add(flowLayoutPanel(FlowLayout.RIGHT)
                .add(okBtn)
                .add(button()
                        .control(Control.builder()
                                .command(this::dispose)
                                .caption("Cancel")))
                .build(), BorderLayout.SOUTH);
        getRootPane().setDefaultButton(okBtn);
        Util.bindEscapeToDispose(this);
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public String getAccessToken() {
        return new String(tokenField.getPassword()).trim();
    }

    public String getUserName() {
        return userField.getText().trim();
    }

    public String getPassword() {
        return new String(pwdField.getPassword());
    }

    public void setAccessToken(String accessToken) {
        tokenField.setText(accessToken);
    }

    public void setUserName(String userName) {
        userField.setText(userName);
    }

    public void setPassword(String password) {
        pwdField.setText(password);
    }
}
