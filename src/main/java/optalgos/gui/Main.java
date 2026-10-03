package optalgos.gui;

import javax.swing.SwingUtilities;

/** Entry point for the Swing GUI. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}
