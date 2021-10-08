// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.plugin;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static com.yahoo.container.plugin.BundleTest.findBundleJar;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;

/**
 * Verifies that the 'useArtifactVersionForExportPackages' setting for the bundle-plugin works as intended.
 *
 * @author gjoranv
 */
public class ExportPackageVersionTest {

    private static Attributes mainAttributes;
    private static String bundleVersion;

    @BeforeClass
    public static void setup() {
        try {
            File componentJar = findBundleJar("artifact-version-for-exports");
            mainAttributes = new JarFile(componentJar).getManifest().getMainAttributes();
            bundleVersion = mainAttributes.getValue("Bundle-Version");

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void artifact_version_without_qualifier_is_used_as_export_version() {
        // Bundle-Version is artifact version without qualifier
        String expectedExport = "ai.vespa.noversion;version=" + bundleVersion;

        String exportPackage = mainAttributes.getValue("Export-Package");
        assertThat(exportPackage, containsString(expectedExport));

        // Verify that there is no qualifier
        assertThat(exportPackage, not(containsString(expectedExport + ".")));
    }

    @Test
    public void explicit_version_in_ExportPackage_annotation_overrides_artifact_version() {
        String exportPackage = mainAttributes.getValue("Export-Package");
        assertThat(exportPackage, containsString("ai.vespa.explicitversion;version=2.4.6.RELEASE"));
    }

    @Test
    public void artifact_version_of_dependency_is_used_as_export_version_for_package_in_compile_scoped_dependency() {
        String exportPackage = mainAttributes.getValue("Export-Package");

        // TODO: This test should have checked for a fixed version of the dependency bundle, different than the main bundle version.
        //       See comment in the dependency bundle's pom.xml for why this is not the case.
        assertThat(exportPackage, containsString("ai.vespa.noversion_dep;version=" + bundleVersion));
    }

    @Test
    public void explicit_version_in_ExportPackage_annotation_overrides_artifact_version_of_compile_scoped_dependency() {
        String exportPackage = mainAttributes.getValue("Export-Package");
        assertThat(exportPackage, containsString("ai.vespa.explicitversion_dep;version=3.6.9.RELEASE"));
    }

}
