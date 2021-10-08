// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.plugin.bundle;

import com.yahoo.container.plugin.osgi.ExportPackages;
import com.yahoo.container.plugin.osgi.ExportPackages.Export;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.yahoo.container.plugin.classanalysis.TestUtilities.throwableMessage;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

/**
 * @author Tony Vaagenes
 * @author ollivir
 */
public class AnalyzeBundleTest {
    private final List<Export> exports;
    private final Map<String, Export> exportsByPackageName;

    File jarDir = new File("src/test/resources/jar");

    public AnalyzeBundleTest() {
        File notOsgi = new File(jarDir, "notAOsgiBundle.jar");
        File simple = new File(jarDir, "simple1.jar");
        exports = AnalyzeBundle.exportedPackagesAggregated(Arrays.asList(notOsgi, simple));
        exportsByPackageName = ExportPackages.exportsByPackageName(this.exports);
    }

    private File jarFile(String name) {
        return new File(jarDir, name);
    }

    @Test
    public void require_that_non_osgi_bundles_are_ignored() {
        assertThat(exportsByPackageName.keySet(), not(hasItem("com.yahoo.sample.exported.package.ignored")));
    }

    @Test
    public void require_that_exports_are_retrieved_from_manifest_in_jars() {
        assertThat(exportsByPackageName.keySet().size(), is(1));
        assertThat(exportsByPackageName.keySet(), hasItem("com.yahoo.sample.exported.package"));
    }

    @Test
    public void exported_class_names_can_be_retrieved() {
        assertThat(ExportPackages.packageNames(exports), is(new HashSet<>(exports.get(0).getPackageNames())));
    }

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Test
    public void require_that_invalid_exports_throws_exception() {
        exception.expect(Exception.class);

        exception.expectMessage(containsString("Invalid manifest in bundle"));
        exception.expectMessage(matchesPattern("Invalid manifest in bundle '.*errorExport.jar'"));
        exception.expectCause(throwableMessage(startsWith("Failed parsing Export-Package")));

        AnalyzeBundle.exportedPackages(jarFile("errorExport.jar"));
    }

    private TypeSafeMatcher<String> matchesPattern(String pattern) {
        return new TypeSafeMatcher<String>() {
            @Override
            protected boolean matchesSafely(String s) {
                return s.matches(pattern);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("expects String that matches the pattern ").appendValue(pattern);
            }
        };
    }
}
