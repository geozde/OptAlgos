package optalgos.algorithm;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Mechanically enforces the single highest-weighted grading criterion (see the project plan): the
 * generic algorithm implementations must contain zero knowledge of the rectangle-packing problem, or
 * of any concrete neighborhood/selection-strategy. If this ever goes red, something under
 * {@code optalgos.algorithm} accidentally imported problem-specific code - the fix is to remove that
 * import, never to relax this rule.
 */
class ArchitectureRulesTest {

    @Test
    void algorithmPackageMustNotDependOnProblemOrPresentationPackages() {
        noClasses().that().resideInAPackage("optalgos.algorithm..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "optalgos.rectpack..",
                        "optalgos.neighborhood..",
                        "optalgos.greedystrategy..",
                        "optalgos.gui..",
                        "optalgos.benchmark..")
                .check(new ClassFileImporter().importPackages("optalgos"));
    }
}
