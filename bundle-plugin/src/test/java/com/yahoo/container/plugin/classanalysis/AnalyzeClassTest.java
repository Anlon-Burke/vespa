// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.plugin.classanalysis;

import com.yahoo.container.plugin.classanalysis.sampleclasses.Base;
import com.yahoo.container.plugin.classanalysis.sampleclasses.ClassAnnotation;
import com.yahoo.container.plugin.classanalysis.sampleclasses.Derived;
import com.yahoo.container.plugin.classanalysis.sampleclasses.DummyAnnotation;
import com.yahoo.container.plugin.classanalysis.sampleclasses.Fields;
import com.yahoo.container.plugin.classanalysis.sampleclasses.Interface1;
import com.yahoo.container.plugin.classanalysis.sampleclasses.Interface2;
import com.yahoo.container.plugin.classanalysis.sampleclasses.MethodAnnotation;
import com.yahoo.container.plugin.classanalysis.sampleclasses.MethodInvocation;
import com.yahoo.osgi.annotation.ExportPackage;
import com.yahoo.osgi.annotation.Version;
import org.junit.Test;

import javax.security.auth.login.LoginException;
import java.awt.Image;
import java.awt.image.ImagingOpException;
import java.awt.image.Kernel;
import java.util.List;
import java.util.Optional;

import static com.yahoo.container.plugin.classanalysis.TestUtilities.analyzeClass;
import static com.yahoo.container.plugin.classanalysis.TestUtilities.classFile;
import static com.yahoo.container.plugin.classanalysis.TestUtilities.name;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests that analysis of class files works.
 *
 * @author Tony Vaagenes
 * @author ollivir
 */
public class AnalyzeClassTest {

    @Test
    public void full_class_name_is_returned() {
        assertEquals(name(Base.class), analyzeClass(Base.class).getName());
    }

    @Test
    public void base_class_is_included() {
        assertTrue(analyzeClass(Derived.class).getReferencedClasses().contains(name(Base.class)));
    }

    @Test
    public void implemented_interfaces_are_included() {
        assertTrue(analyzeClass(Base.class).getReferencedClasses().containsAll(
                List.of(name(Interface1.class), name(Interface2.class))));
    }

    @Test
    public void interface_can_be_analyzed() {
        ClassFileMetaData classMetaData = analyzeClass(Interface1.class);

        assertEquals(name(Interface1.class), classMetaData.getName());
        assertTrue(classMetaData.getReferencedClasses().contains(name(Interface2.class)));
    }

    @Test
    public void return_type_is_included() {
        assertTrue(analyzeClass(Interface1.class).getReferencedClasses().contains(name(Image.class)));
    }

    @Test
    public void parameters_are_included() {
        assertTrue(analyzeClass(Interface1.class).getReferencedClasses().contains(name(Kernel.class)));
    }

    @Test
    public void exceptions_are_included() {
        assertTrue(analyzeClass(Interface1.class).getReferencedClasses().contains(name(ImagingOpException.class)));
    }

    @Test
    public void basic_types_ignored() {
        List.of("int", "float").forEach(type ->
                assertFalse(analyzeClass(Interface1.class).getReferencedClasses().contains(type)));
    }

    @Test
    public void arrays_of_basic_types_ignored() {
        List.of("int[]", "int[][]").forEach(type ->
                assertFalse(analyzeClass(Interface1.class).getReferencedClasses().contains(type)));
    }

    @Test
    public void instance_field_types_are_included() {
        assertTrue(analyzeClass(Fields.class).getReferencedClasses().contains(name(String.class)));
    }

    @Test
    public void static_field_types_are_included() {
        assertTrue(analyzeClass(Fields.class).getReferencedClasses().contains(name(java.util.List.class)));
    }

    @Test
    public void field_annotation_is_included() {
        assertTrue(analyzeClass(Fields.class).getReferencedClasses().contains(name(DummyAnnotation.class)));
    }

    @Test
    public void class_annotation_is_included() {
        assertTrue(analyzeClass(ClassAnnotation.class).getReferencedClasses().contains(name(DummyAnnotation.class)));
    }

    @Test
    public void method_annotation_is_included() {
        assertTrue(analyzeClass(MethodAnnotation.class).getReferencedClasses().contains(name(DummyAnnotation.class)));
    }

    @Test
    public void export_package_annotations_are_ignored() {
        List.of(ExportPackage.class, Version.class).forEach(type ->
                assertFalse(Analyze.analyzeClass(classFile("com.yahoo.container.plugin.classanalysis.sampleclasses.package-info"))
                        .getReferencedClasses().contains(type)));
    }

    @Test
    public void export_annotations_are_processed() {
        assertEquals(Optional.of(new ExportPackageAnnotation(3, 1, 4, "TEST_QUALIFIER-2")),
                Analyze.analyzeClass(classFile("com.yahoo.container.plugin.classanalysis.sampleclasses.package-info")).getExportPackage());
    }

    @Test
    public void export_annotations_are_validated() {

        try {
            Analyze.analyzeClass(classFile("com.yahoo.container.plugin.classanalysis.sampleclasses.invalid.package-info"));
            fail();
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("invalid/package-info"));
            assertTrue(e.getCause().getMessage().contains("qualifier must follow the format"));
            assertTrue(e.getCause().getMessage().contains("'EXAMPLE INVALID QUALIFIER'"));
        }
    }

    @Test
    public void catch_clauses_are_included() {
        assertTrue(Analyze.analyzeClass(classFile("com.yahoo.container.plugin.classanalysis.sampleclasses.CatchException"))
                .getReferencedClasses().contains(name(LoginException.class)));
    }

    @Test
    public void class_references_are_included() {
        assertTrue(Analyze.analyzeClass(classFile("com.yahoo.container.plugin.classanalysis.sampleclasses.ClassReference"))
                .getReferencedClasses().contains(name(Interface1.class)));
    }

    @Test
    public void return_type_of_method_invocations_are_included() {
        assertTrue(analyzeClass(MethodInvocation.class).getReferencedClasses().contains(name(Image.class)));
    }

    @Test
    public void attributes_are_included() {
        //Uses com/coremedia/iso/Utf8.class from com.googlecode.mp4parser:isoparser:1.0-RC-1
        assertTrue(Analyze.analyzeClass(classFile("class/Utf8")).getReferencedClasses()
                .contains("org.aspectj.weaver.MethodDeclarationLineNumber"));
    }
}
