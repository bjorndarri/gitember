package com.az.gitember.dialog;

import com.az.gitember.data.Settings;
import com.az.gitember.service.CommitTemplateService;
import com.az.gitember.service.Context;
import com.az.gitember.service.GitRepoService;
import com.az.gitember.service.GitemberUtil;
import com.az.gitember.service.OllamaManager;
import com.az.gitember.ui.FileViewerWindow;
import com.az.gitember.ui.misc.Util;
import com.az.gitember.data.Const;
import is.codion.common.reactive.state.ObservableState;
import is.codion.common.reactive.state.State;
import is.codion.common.reactive.value.Value;
import is.codion.swing.common.ui.component.combobox.Completion;
import is.codion.swing.common.ui.control.Control;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

import static is.codion.swing.common.ui.component.Components.*;

public class SettingsDialog extends JDialog {

    /**
     * Offered in the model combo, which stays editable — any tag pulled in Ollama works.
     * The first entry is {@link Settings#DEFAULT_LLM_DETECTOR_MODEL}.
     */
    private static final String[] SUGGESTED_LLM_MODELS = {
            Settings.DEFAULT_LLM_DETECTOR_MODEL,
            "qwen2.5-coder:14b",
            "deepseek-coder-v2",
            "codellama",
            "llama3.2",
            "mistral"
    };

    private final Settings settings;

    // Common
    private final Value<String> userName  = Value.nonNull("");
    private final Value<String> userEmail = Value.nonNull("");

    // Commit
    private final Value<String> commitTemplate = Value.nonNull("");
    private JTextArea commitTemplateArea;

    // UI
    private final Value<String>  theme      = Value.nullable();
    private final Value<Integer> fontSize   = Value.nullable();
    private final Value<String>  dateFormat = Value.nullable();
    private final Value<String>  timeFormat = Value.nullable();

    // Commit signing
    private final Value<String> signOption        = Value.nullable();
    private final Value<String> signKey           = Value.nonNull("");
    private final Value<String> signKeyLabel      = Value.nonNull("Sign key:");
    private final State         signing           = State.state();
    private final State         ssh               = State.state();
    private final State         signCommit        = State.state();
    private final State         signTag           = State.state();
    private final State         verifySignatures  = State.state();
    private final State         globalSigners     = State.state();
    private final State         repositorySigners = State.state();

    // AI
    private final State         leakDetector             = State.state();
    private final State         branchCompareDescription = State.state();
    private final State         commitMessageGeneration  = State.state();
    private final Value<String> llmModel                 = Value.nullable();

    // Other
    private final Value<String> ignoreExtensions = Value.nonNull("");

    public SettingsDialog(Frame owner) {
        super(owner, "Settings", true);
        setSize(640, 640);
        setLocationRelativeTo(owner);
        setResizable(true);

        settings = Context.getSettings();

        // Show the stored ignore list (defaults are seeded at startup, so this is always populated)
        ignoreExtensions.set(settings != null
                ? String.join(", ", settings.getIgnoreCompareFiles())
                : String.join(", ", Settings.DEFAULT_IGNORE_COMPARE_FILES));

        JTabbedPane tabbedPane = tabbedPane()
                .tab("Common", createCommonPanel())
                .tab("Commit", createCommitTemplatePanel())
                .tab("UI", createUIPanel())
                .tab("Commit Signing", createComminSignPanel())
                .tab("AI", createAIPanel())
                .tab("Other", createComparePanel())
                .build();

        JButton okBtn = button()
                .control(Control.builder()
                        .command(this::applyAndClose)
                        .caption("OK"))
                .build();

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabbedPane, BorderLayout.CENTER);
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

    private JPanel createComparePanel() {
        return borderLayoutPanel()
                .border(BorderFactory.createEmptyBorder(10, 10, 10, 10))
                .north(label("Ignore extensions (folder compare):"))
                .center(textArea()
                        .rowsColumns(3, 30)
                        .lineWrap(true)
                        .wrapStyleWord(true)
                        .link(ignoreExtensions)
                        .scrollPane())
                .south(flowLayoutPanel(FlowLayout.RIGHT)
                        .add(button()
                                .control(Control.builder()
                                        .command(() -> ignoreExtensions.set(
                                                String.join(", ", Settings.DEFAULT_IGNORE_COMPARE_FILES)))
                                        .caption("Reset to defaults"))))
                .build();
    }

    private JPanel createAIPanel() {
        boolean currentLeakDetector = settings == null || !Boolean.FALSE.equals(settings.getEnableLeakDetector());
        boolean currentBranchCompareDesc = settings != null && Boolean.TRUE.equals(settings.getEnableBranchCompareDescription());
        boolean currentCommitMsgGen = settings != null && Boolean.TRUE.equals(settings.getEnableCommitMessageGeneration());
        leakDetector.set(currentLeakDetector);
        branchCompareDescription.set(currentBranchCompareDesc);
        commitMessageGeneration.set(currentCommitMsgGen);
        llmModel.set(settings != null
                ? settings.getLlmDetectorModel() : Settings.DEFAULT_LLM_DETECTOR_MODEL);

        // When any AI feature is enabled, verify Ollama is present
        leakDetector.addConsumer(on -> {
            if (on && !currentLeakDetector) ensureOllamaOrRevert(leakDetector);
        });
        branchCompareDescription.addConsumer(on -> {
            if (on && !currentBranchCompareDesc) ensureOllamaOrRevert(branchCompareDescription);
        });
        commitMessageGeneration.addConsumer(on -> {
            if (on && !currentCommitMsgGen) ensureOllamaOrRevert(commitMessageGeneration);
        });

        return form()
                .border(BorderFactory.createEmptyBorder(10, 10, 10, 10))
                .add(checkBox()
                        .link(leakDetector)
                        .toolTipText("Scan staged files for secrets words before each commit")
                        .label("Enable secret detector (AI experimental):"))
                .add(checkBox()
                        .link(branchCompareDescription)
                        .toolTipText("Show AI descriptions when comparing branches")
                        .label("Branch compare description (AI experimental):"))
                .add(checkBox()
                        .link(commitMessageGeneration)
                        .toolTipText("Generate commit message suggestions in the commit dialog")
                        .label("AI commit message generation (AI experimental):"))
                // Model shared by all three features above
                .add(comboBox()
                        .model(new DefaultComboBoxModel<>(SUGGESTED_LLM_MODELS))
                        .editable(true)
                        .link(llmModel)
                        .toolTipText("Model the features above ask Ollama for. Any pulled model "
                                + "can be typed in; leave it empty to use " + Settings.DEFAULT_LLM_DETECTOR_MODEL + ".")
                        .label("Ollama model:"))
                .build();
    }

    private JPanel createComminSignPanel() {
        signOption.addConsumer(option -> {
            signing.set(!Settings.SignOption.NONE.getOption().equalsIgnoreCase(option));
            ssh.set(Settings.SignOption.SSH.getOption().equalsIgnoreCase(option));
            if (Settings.SignOption.NONE.getOption().equalsIgnoreCase(option)) {
                signCommit.set(false);
                signTag.set(false);
            } else if (Settings.SignOption.PGP.getOption().equalsIgnoreCase(option)) {
                signKeyLabel.set("Signing key:");
            } else if (Settings.SignOption.SSH.getOption().equalsIgnoreCase(option)) {
                signKeyLabel.set("Path to pub key:");
            }
        });
        signOption.set(settings.getSignOption());
        signKey.set(settings.getSignKey());
        signCommit.set(BooleanUtils.toBoolean(settings.getSignCommit()));
        signTag.set(BooleanUtils.toBoolean(settings.getSignTag()));
        verifySignatures.set(settings.isVerifyCommitSignatures());
        repositorySigners.set(settings.isRepositoryAllowedSigners());
        globalSigners.set(!settings.isRepositoryAllowedSigners());

        ButtonGroup allowedGroup = new ButtonGroup();
        ObservableState editSigners = State.and(verifySignatures, globalSigners);

        return form()
                .border(BorderFactory.createEmptyBorder(10, 10, 10, 10))
                .add(comboBox()
                        .model(new DefaultComboBoxModel<>(new String[]{
                                Settings.SignOption.NONE.getOption(),
                                Settings.SignOption.PGP.getOption(),
                                Settings.SignOption.SSH.getOption()}))
                        .completionMode(Completion.Mode.NONE)
                        .link(signOption)
                        .label("Sign option:"))
                // the label follows the visibility of the row
                .add(label()
                        .text(signKeyLabel)
                        .build(), borderLayoutPanel()
                        .layout(new BorderLayout(5, 0))
                        .visible(signing)
                        .center(stringField()
                                .link(signKey)
                                .visible(signing))
                        .east(button()
                                .control(Control.builder()
                                        .command(this::browseKey)
                                        .caption("..."))
                                .visible(ssh))
                        .build())
                .add(label("Sign commits:")
                        .visible(signing)
                        .build(), checkBox()
                        .link(signCommit)
                        .visible(signing)
                        .build())
                .add(label("Sign tag:")
                        .visible(signing)
                        .build(), checkBox()
                        .link(signTag)
                        .visible(signing)
                        .build())
                .span(label("Verification")
                        .font(font -> font.deriveFont(Font.BOLD)))
                .span(checkBox()
                        .text("Verify commit signatures")
                        .link(verifySignatures)
                        .toolTipText("When on, signed commits can be checked against the allowed-signers file on demand"))
                .add(label("Allowed signers:").build(), panel()
                        .layout(new FlowLayout(FlowLayout.LEFT, 0, 0))
                        .add(radioButton()
                                .text(Settings.AllowedSignersFile.GLOBAL_SSH.getOption())
                                .link(globalSigners)
                                .buttonGroup(allowedGroup)
                                .enabled(verifySignatures))
                        .add(button()
                                .control(Control.builder()
                                        .command(() -> FileViewerWindow.openForEdit(
                                                "Edit allowed_signers", GitRepoService.globalSshPath(Const.ALLOWED_SIGNERS_NAME)))
                                        .caption("Edit…")
                                        .description("Edit ~/.ssh/allowed_signers")
                                        .enabled(editSigners)))
                        .add(button()
                                .control(Control.builder()
                                        .command(() -> FileViewerWindow.openForEdit(
                                                "Edit revoked_keys", GitRepoService.globalSshPath(Const.REVOKED_KEYS_NAME)))
                                        .caption("Edit revoked keys…")
                                        .description("Edit ~/.ssh/revoked_keys")
                                        .enabled(editSigners)))
                        .build())
                .add(radioButton()
                        .text(Settings.AllowedSignersFile.REPOSITORY.getOption())
                        .link(repositorySigners)
                        .buttonGroup(allowedGroup)
                        .enabled(verifySignatures)
                        .build())
                .build();
    }

    private void browseKey() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileHidingEnabled(false);
        chooser.setDialogTitle("Select Key");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            signKey.set(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private JPanel createCommonPanel() {
        StoredConfig globalConfig = SystemReader.getInstance().openUserConfig(null, FS.detect());

        try {
            globalConfig.load();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        userName.set(globalConfig.getString("user", null, "name"));
        userEmail.set(globalConfig.getString("user", null, "email"));

        return form()
                .border(BorderFactory.createEmptyBorder(10, 10, 10, 10))
                .add(stringField()
                        .link(userName)
                        .label("User name:"))
                .add(stringField()
                        .link(userEmail)
                        .label("User email:"))
                .build();
    }

    private JPanel createCommitTemplatePanel() {
        commitTemplate.set(settings != null && settings.getCommitTemplate() != null
                ? settings.getCommitTemplate() : "");
        commitTemplateArea = textArea()
                .rowsColumns(12, 40)
                .lineWrap(true)
                .wrapStyleWord(true)
                .name("commitTemplateArea")
                .font(font -> new Font(Font.MONOSPACED, Font.PLAIN, Math.max(12, font.getSize())))
                .link(commitTemplate)
                .build();

        return borderLayoutPanel()
                .layout(new BorderLayout(8, 8))
                .border(BorderFactory.createEmptyBorder(10, 10, 10, 10))
                .north(label("<html>Pre-filled in the commit dialog when AI commit message generation is off.<br>"
                        + "Leave empty to use git's <code>commit.template</code> if it is configured.</html>")
                        .foreground(UIManager.getColor("Label.disabledForeground")))
                .center(scrollPane()
                        .view(commitTemplateArea))
                .south(panel()
                        .layout(new FlowLayout(FlowLayout.LEFT, 0, 0))
                        .add(button()
                                .control(Control.builder()
                                        .command(this::loadGitCommitTemplate)
                                        .caption("Load from git commit.template…")
                                        .description("Replace the editor with the file pointed to by git commit.template"))
                                .name("loadGitCommitTemplateButton")))
                .build();
    }

    private void loadGitCommitTemplate() {
        String fromGit = CommitTemplateService.readGitTemplateFile(
                Context.getGitRepoService());
        if (StringUtils.isNotBlank(fromGit)) {
            commitTemplate.set(fromGit);
            commitTemplateArea.setCaretPosition(0);
        } else {
            JOptionPane.showMessageDialog(this,
                    "No git commit.template is configured, or the file could not be read.",
                    "Commit template", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private JPanel createUIPanel() {
        String currentTheme = settings != null && "dark".equalsIgnoreCase(settings.getTheme()) ? "Dark" : "Light";
        int currentFontSize = settings != null ? settings.getFontSize() : 13;
        if (currentFontSize <= 0) currentFontSize = 13;
        theme.set(currentTheme);
        fontSize.set(currentFontSize);
        dateFormat.set(StringUtils.isEmpty(settings.getDateFormat()) ?
                GitemberUtil.defaultSimpleDateFormat : settings.getDateFormat());
        timeFormat.set(StringUtils.isEmpty(settings.getTimeFormat()) ?
                GitemberUtil.defaultSimpleTimeFormat : settings.getTimeFormat());

        return form()
                .border(BorderFactory.createEmptyBorder(10, 10, 10, 10))
                .add(comboBox()
                        .model(new DefaultComboBoxModel<>(new String[]{"Light", "Dark"}))
                        .completionMode(Completion.Mode.NONE)
                        .link(theme)
                        .label("Theme:"))
                .add(integerSpinner()
                        .range(8, 36)
                        .stepSize(1)
                        .link(fontSize)
                        .label("Font size:"))
                .add(comboBox()
                        .model(new DefaultComboBoxModel<>(new String[]{
                                "yyyy-MM-dd HH:mm:ss",
                                "yyyy-MM-dd HH:mm",
                                "dd.MM.yyyy HH:mm:ss",
                                "dd.MM.yyyy HH:mm",
                                "MM/dd/yyyy HH:mm:ss",
                                "MM/dd/yyyy HH:mm",
                                "MMM dd, yyyy HH:mm:ss",
                                "MMM dd, yyyy HH:mm"
                        }))
                        .completionMode(Completion.Mode.NONE)
                        .link(dateFormat)
                        .label("Date format:"))
                .add(comboBox()
                        .model(new DefaultComboBoxModel<>(new String[]{
                                "HH:mm:ss",
                                "HH:mm",
                                "hh:mm a"
                        }))
                        .completionMode(Completion.Mode.NONE)
                        .link(timeFormat)
                        .label("Time format:"))
                .build();
    }

    private void applyAndClose() {
        Settings settings = Context.getSettings();
        if (settings == null) {
            dispose();
            return;
        }

        String selectedTheme = theme.get();
        int selectedFontSize = fontSize.get();

        boolean themeChanged = !selectedTheme.equalsIgnoreCase(
                "dark".equalsIgnoreCase(settings.getTheme()) ? "Dark" : "Light");
        boolean fontChanged = selectedFontSize != settings.getFontSize();

        settings.setTheme("Dark".equals(selectedTheme) ? "dark" : "light");
        settings.setFontSize(selectedFontSize);

        // Parse ignore-extensions textarea (comma-separated, strip dots and whitespace)
        TreeSet<String> ignore = new TreeSet<>();
        for (String tok : ignoreExtensions.get().split("[,\\s]+")) {
            String ext = tok.trim().toLowerCase().replaceAll("^\\.", "");
            if (!ext.isEmpty()) ignore.add(ext);
        }
        // Store exactly what the user typed — no magic substitution
        settings.setIgnoreCompareFiles(ignore);

        settings.setEnableLeakDetector(leakDetector.is());
        settings.setEnableBranchCompareDescription(branchCompareDescription.is());
        settings.setEnableCommitMessageGeneration(commitMessageGeneration.is());
        settings.setCommitTemplate(commitTemplate.get());

        // An empty entry means "use the default", which is what the getter falls back to.
        Object selectedModel = llmModel.get();
        String llmModel = selectedModel != null ? selectedModel.toString().trim() : "";
        settings.setLlmDetectorModel(llmModel.isEmpty() ? null : llmModel);

        settings.setSignOption(signOption.get());
        settings.setSignKey(signKey.get());
        settings.setSignCommit(signCommit.is());
        settings.setSignTag(signTag.is());
        settings.setVerifyCommitSignatures(verifySignatures.is());
        if (repositorySigners.is()) {
            settings.setAllowedSignersFile(Settings.AllowedSignersFile.REPOSITORY.getOption());
        } else {
            settings.setAllowedSignersFile(Settings.AllowedSignersFile.GLOBAL_SSH.getOption());
        }
        settings.setDateFormat(dateFormat.get());
        GitemberUtil.simpleDateFormat = new SimpleDateFormat(dateFormat.get());
        settings.setTimeFormat(timeFormat.get());


        Context.saveSettings();

        if (themeChanged) {
            try {
                if ("Dark".equals(selectedTheme)) {
                    FlatDarkLaf.setup();
                } else {
                    FlatLightLaf.setup();
                }
            } catch (Exception ignored) {
            }
        }

        if (fontChanged) {
            Font defaultFont = UIManager.getFont("defaultFont");
            if (defaultFont == null) {
                defaultFont = new Font(Font.SANS_SERIF, Font.PLAIN, selectedFontSize);
            } else {
                defaultFont = defaultFont.deriveFont((float) selectedFontSize);
            }
            UIManager.put("defaultFont", defaultFont);
        }

        if (themeChanged || fontChanged) {
            for (Window w : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(w);
            }
        }


        //-------------------------
        StoredConfig globalConfig = SystemReader.getInstance().openUserConfig(null, FS.detect());

        try {
            globalConfig.load();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Set user.name and user.email under the "user" section
        globalConfig.setString("user", null, "name", userName.get());
        globalConfig.setString("user", null, "email", userEmail.get());

        // Save the changes back to the global file
        try {
            globalConfig.save();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        dispose();
    }

    /**
     * If Ollama is not installed, shows a confirmation dialog. On confirmation,
     * downloads and installs Ollama (showing progress). If the user declines or
     * the download fails, reverts the feature to disabled.
     */
    private void ensureOllamaOrRevert(State feature) {
        if (OllamaManager.getStatus() != OllamaManager.Status.NOT_INSTALLED) return;

        int choice = JOptionPane.showConfirmDialog(
                this,
                "This AI feature requires Ollama, which is not installed.\n\n" +
                        "Gitember will download and install Ollama automatically.\n" +
                        "This may take several minutes depending on your internet speed.\n\n" +
                        "Download and install Ollama now?",
                "Ollama Required",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);

        if (choice != JOptionPane.YES_OPTION) {
            feature.set(false);
            return;
        }

        // Show a progress dialog while downloading
        JDialog progressDialog = new JDialog(this, "Installing Ollama", true);
        JLabel statusLabel = new JLabel("Preparing download…");
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setIndeterminate(true);

        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));
        p.add(statusLabel, BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        progressDialog.getContentPane().add(p);
        progressDialog.pack();
        progressDialog.setMinimumSize(new Dimension(340, 100));
        progressDialog.setLocationRelativeTo(this);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        new SwingWorker<Void, String>() {
            private long total = -1;

            @Override
            protected Void doInBackground() throws Exception {
                publish("Downloading Ollama…");
                OllamaManager.download(
                        bytes -> {
                            if (total > 0) {
                                int pct = (int) (bytes * 100 / total);
                                SwingUtilities.invokeLater(() -> {
                                    bar.setIndeterminate(false);
                                    bar.setValue(pct);
                                    bar.setString(pct + "%");
                                });
                            }
                        },
                        t -> total = t
                );
                //publish("Starting Ollama…");
                //OllamaManager.startServerAndWait(30_000);
                return null;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                if (!chunks.isEmpty()) statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    publish("Starting Ollama…");
                    OllamaManager.startServerAndWait(30_000);
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof OllamaManager.ChecksumMismatchException) {
                        JOptionPane.showMessageDialog(SettingsDialog.this,
                                "Ollama checksum verification failed.\n" +
                                        "The downloaded file may be corrupt or tampered with.\n\n" +
                                        "All AI features have been disabled for security.",
                                "Checksum Verification Failed", JOptionPane.ERROR_MESSAGE);
                        disableAllAiFeatures();
                    } else {
                        String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(SettingsDialog.this,
                                "Ollama installation failed:\n" + msg,
                                "Error", JOptionPane.ERROR_MESSAGE);
                        feature.set(false);
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(SettingsDialog.this,
                            "Ollama installation failed:\n" + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    feature.set(false);
                }
            }
        }.execute();

        progressDialog.setVisible(true); // blocks until done() calls dispose()
    }

    private void disableAllAiFeatures() {
        leakDetector.set(false);
        branchCompareDescription.set(false);
        commitMessageGeneration.set(false);
        Settings settings = Context.getSettings();
        if (settings != null) {
            settings.setEnableLeakDetector(false);
            settings.setEnableBranchCompareDescription(false);
            settings.setEnableCommitMessageGeneration(false);
            Context.saveSettings();
        }
    }

    public static void applyFontSize(int size) {
        Font defaultFont = UIManager.getFont("defaultFont");
        if (defaultFont == null) {
            defaultFont = new Font(Font.SANS_SERIF, Font.PLAIN, size);
        } else {
            defaultFont = defaultFont.deriveFont((float) size);
        }
        UIManager.put("defaultFont", defaultFont);
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
        }
    }

}
