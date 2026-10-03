package optalgos.gui;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.LayoutManager;

/**
 * One titled block in the sidebar, stacked with the others by {@code MainFrame}'s vertical
 * {@code BoxLayout}. Exists for exactly one reason: to make "never taller than this section actually
 * needs, but never SHORTER either" a live property rather than a value read once at construction.
 *
 * <p>A {@code GridBagLayout} panel reports an effectively unbounded maximum size, so a
 * {@code BoxLayout} parent stretches it to fill all available height instead of leaving that to the
 * glue below - which is why a cap is needed at all. The cap used to be applied by MainFrame as a
 * one-shot {@code setMaximumSize(getPreferredSize())} right after constructing each panel, and that
 * snapshot is what caused a reported bug: every one of these sections holds text that GROWS after
 * construction - the instance panel's validation message, the algorithm panel's status line (which
 * wraps onto two and three lines as a run progresses), the results table as rows fill in. Once the
 * content needs more height than the snapshot allows, the section is handed less height than its
 * layout asks for, and GridBagLayout resolves the shortfall by compressing rows into each other:
 * the validation message ends up drawn underneath the Generate button, half of it clipped away by
 * the section's bottom border. That reads exactly as the "disappearing text behind the button" it was
 * reported as, and it violates the spec's first visualization rule outright - "Vermeiden Sie nach
 * Moeglichkeit Ueberlappungen von Informationen".
 *
 * <p>Deriving the cap from {@link #getPreferredSize()} on every query instead means the section is
 * always given exactly the height its current content needs, so there is never a shortfall for
 * GridBagLayout to resolve by overlapping anything - whatever the content grows into later.
 */
class SidebarSection extends JPanel {

    /** Points added to the default label font for a section title. Enough that the three step
     *  headings read as the structure of the sidebar at a glance rather than as three more lines of
     *  text among the controls - the spec asks that what should draw the eye be drawn larger and
     *  bolder, and on first sight the thing a viewer needs to find is the order to do things in. */
    private static final float TITLE_FONT_GROWTH_PT = 3f;

    SidebarSection(LayoutManager layout, String title) {
        super(layout);
        TitledBorder border = BorderFactory.createTitledBorder(title);
        Font base = UIManager.getFont("TitledBorder.font");
        if (base == null) {
            base = getFont();
        }
        if (base != null) {
            border.setTitleFont(base.deriveFont(Font.BOLD, base.getSize2D() + TITLE_FONT_GROWTH_PT));
        }
        border.setTitleColor(Palette.TEXT);
        setBorder(border);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
