package optalgos.gui;

import optalgos.rectpack.generator.InstanceConfig;
import optalgos.rectpack.generator.InstanceGenerator;
import optalgos.rectpack.model.Rectangle;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Lets the user configure and generate a new random instance: rectangle count, per-dimension
 * min/max side length, and box length L - each editable per generation, per the spec. Validates
 * input inline (min <= max <= L, count >= 1, L >= 1) rather than silently clamping: the spec is
 * explicit that the viewer is someone unfamiliar with this GUI, who needs clear feedback rather than
 * a value quietly changing underneath them.
 */
final class InstanceConfigPanel extends SidebarSection {

    // The validation message is the one piece of text in this panel whose length is not known up
    // front, and it sits directly above the Generate button, so it is the piece that decides whether
    // this panel can ever overlap itself. Two constraints, both learned from the bug where it ended
    // up drawn behind the button:
    //
    //  - it must never demand more WIDTH than the sidebar has (an unconstrained JLabel sizes itself
    //    to fit its text on one line, which propagates out through GridBagLayout as a wider preferred
    //    width for the whole panel), so it is wrapped in a fixed-width <html> block exactly as
    //    AlgorithmControlPanel's status label is - see STATUS_LABEL_WIDTH_PX there for the same fix;
    //  - it must never demand more HEIGHT than the panel is given, which wrapping onto a second line
    //    would otherwise do. That half is handled by SidebarSection: the panel's height cap now
    //    tracks its live preferred height instead of a snapshot taken while this label was empty.
    private static final int MESSAGE_WIDTH_PX = 250;

    private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 1000, 1));
    private final JSpinner minWidthSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 500, 1));
    private final JSpinner maxWidthSpinner = new JSpinner(new SpinnerNumberModel(15, 1, 500, 1));
    private final JSpinner minHeightSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 500, 1));
    private final JSpinner maxHeightSpinner = new JSpinner(new SpinnerNumberModel(15, 1, 500, 1));
    private final JSpinner boxLengthSpinner = new JSpinner(new SpinnerNumberModel(30, 1, 500, 1));
    // Optional: empty means "a fresh random instance", which is the default and what repeated
    // generating should give. A number reproduces exactly the instance that seed generated before.
    // Deliberately never filled in automatically - a remembered seed would make every further click
    // on Generate silently produce the same instance again.
    private final JTextField seedField = new JTextField(10);
    private final JLabel errorLabel = new JLabel();
    private final JButton generateButton = new JButton("Generate Instance");

    InstanceConfigPanel(Consumer<GeneratedInstance> onGenerated) {
        super(new GridBagLayout(), "Step 1: Generate instance.");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 6, 3, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        addRow(gbc, 0, "Rectangles:", countSpinner);
        addRow(gbc, 1, "Width min / max:", pair(minWidthSpinner, maxWidthSpinner));
        addRow(gbc, 2, "Height min / max:", pair(minHeightSpinner, maxHeightSpinner));
        addRow(gbc, 3, "Box length L:", boxLengthSpinner);
        seedField.setToolTipText("Leave empty for a new random instance; enter a seed to regenerate that exact instance.");
        addRow(gbc, 4, "Seed (optional):", seedField);

        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        // A message row and a button sharing a 3px inset is what made the two read as one collided
        // block the moment anything squeezed them together; a message that only ever appears to say
        // something went wrong needs visible separation from the control it is about, not proximity.
        gbc.insets = new Insets(8, 6, 8, 6);
        errorLabel.setForeground(Palette.ERROR);
        add(errorLabel, gbc);

        gbc.gridy = 6;
        gbc.insets = new Insets(3, 6, 6, 6);
        add(generateButton, gbc);

        clearError();
        // A message about values that have since been edited is stale the instant the user starts
        // fixing them - and a stale error sitting under the button is exactly what invites reading it
        // as a permanent part of the panel rather than as feedback on one attempt.
        for (JSpinner spinner : List.of(countSpinner, minWidthSpinner, maxWidthSpinner,
                minHeightSpinner, maxHeightSpinner, boxLengthSpinner)) {
            spinner.addChangeListener(e -> clearError());
        }
        seedField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                clearError();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                clearError();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                clearError();
            }
        });

        generateButton.addActionListener(e -> {
            String error = validationError();
            if (error != null) {
                showError(error);
                return;
            }
            clearError();
            InstanceConfig config = new InstanceConfig(
                    (int) countSpinner.getValue(), (int) minWidthSpinner.getValue(), (int) maxWidthSpinner.getValue(),
                    (int) minHeightSpinner.getValue(), (int) maxHeightSpinner.getValue(), (int) boxLengthSpinner.getValue());
            long seed = enteredSeed();
            List<Rectangle> rectangles = new InstanceGenerator(seed).generate(config);
            onGenerated.accept(new GeneratedInstance(rectangles, config.boxLength(), seed));
        });
    }

    /** The seed typed into the field, or - if it is empty - a fresh random one. Random seeds are drawn
     *  below one billion so the number shown afterwards is short enough to note down and type back in;
     *  any long still works when entered by hand. Only called after {@link #validationError} passed. */
    private long enteredSeed() {
        String text = seedField.getText().trim();
        return text.isEmpty() ? ThreadLocalRandom.current().nextLong(1_000_000_000L) : Long.parseLong(text);
    }

    private void showError(String message) {
        errorLabel.setText("<html><div style='width:" + MESSAGE_WIDTH_PX + "px'>" + escaped(message) + "</div></html>");
        revalidate();
        repaint();
    }

    /** Wrapping a message in {@code <html>} means Swing parses it as markup, and every one of these
     *  messages is about an ordering constraint written with "<=" - which the parser reads as the
     *  start of a tag and swallows, leaving "Max side length must be = box length L." on screen: the
     *  message says the exact opposite of the rule it is enforcing. */
    private static String escaped(String message) {
        return message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Blanks the message but keeps its row at a full line's height, so generating an instance after
     *  a failed attempt doesn't make every control above jump upward by a line. */
    private void clearError() {
        errorLabel.setText("<html><div style='width:" + MESSAGE_WIDTH_PX + "px'>&nbsp;</div></html>");
        revalidate();
        repaint();
    }

    private String validationError() {
        int minW = (int) minWidthSpinner.getValue();
        int maxW = (int) maxWidthSpinner.getValue();
        int minH = (int) minHeightSpinner.getValue();
        int maxH = (int) maxHeightSpinner.getValue();
        int boxLength = (int) boxLengthSpinner.getValue();
        if (minW > maxW) {
            return "Min width must be <= max width.";
        }
        if (minH > maxH) {
            return "Min height must be <= max height.";
        }
        if (maxW > boxLength || maxH > boxLength) {
            return "Max side length must be <= box length L.";
        }
        String seedText = seedField.getText().trim();
        if (!seedText.isEmpty()) {
            try {
                Long.parseLong(seedText);
            } catch (NumberFormatException ex) {
                return "Seed must be a whole number, or empty for a random instance.";
            }
        }
        return null;
    }

    private void addRow(GridBagConstraints gbc, int row, String label, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        add(new JLabel(label), gbc);
        gbc.gridx = 1;
        add(field, gbc);
    }

    private JComponent pair(JComponent a, JComponent b) {
        JPanel panel = new JPanel(new GridLayout(1, 2, 4, 0));
        panel.add(a);
        panel.add(b);
        return panel;
    }
}
