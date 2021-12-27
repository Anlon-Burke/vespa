// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.builder;

import com.yahoo.config.ConfigInstance;
import com.yahoo.config.model.application.provider.BaseDeployLogger;
import com.yahoo.config.model.builder.xml.XmlHelper;
import com.yahoo.config.model.deploy.ConfigDefinitionStore;
import com.yahoo.config.model.producer.UserConfigRepo;
import com.yahoo.test.ArraytypesConfig;
import com.yahoo.test.SimpletypesConfig;
import com.yahoo.vespa.config.ConfigDefinitionKey;
import com.yahoo.vespa.config.ConfigPayload;
import com.yahoo.vespa.config.ConfigPayloadBuilder;
import com.yahoo.vespa.configdefinition.SpecialtokensConfig;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import java.io.Reader;
import java.io.StringReader;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * @author Ulf Lilleengen
 */
public class UserConfigBuilderTest {

    private final ConfigDefinitionStore configDefinitionStore = defKey -> Optional.empty();

    @Test
    public void require_that_simple_config_is_resolved() {
        Element configRoot = getDocument("<config name=\"test.simpletypes\">" +
                                         "    <intval>13</intval>" +
                                         "</config>" +
                                         "<config name=\"test.simpletypes\" version=\"1\">" +
                                         "    <stringval>foolio</stringval>" +
                                         "</config>");
        UserConfigRepo map = UserConfigBuilder.build(configRoot, configDefinitionStore, new BaseDeployLogger());
        assertFalse(map.isEmpty());
        ConfigDefinitionKey key = new ConfigDefinitionKey("simpletypes", "test");
        assertNotNull(map.get(key));
        SimpletypesConfig config = createConfig(SimpletypesConfig.class, map.get(key));
        assertEquals(13, config.intval());
        assertEquals("foolio", config.stringval());
    }

    private static <ConfigType extends ConfigInstance> ConfigType createConfig(Class<ConfigType> clazz, ConfigPayloadBuilder builder) {
        return ConfigPayload.fromBuilder(builder).toInstance(clazz, "");
    }

    @Test
    public void require_that_arrays_config_is_resolved() {
        Element configRoot = getDocument("<config name=\"test.arraytypes\">" +
                "    <intarr operation=\"append\">13</intarr>" +
                "    <intarr operation=\"append\">10</intarr>" +
                "    <intarr operation=\"append\">1337</intarr>" +
                "</config>");
        UserConfigRepo map = UserConfigBuilder.build(configRoot, configDefinitionStore, new BaseDeployLogger());
        assertFalse(map.isEmpty());
        ConfigDefinitionKey key = new ConfigDefinitionKey("arraytypes", "test");
        assertNotNull(map.get(key));
        ArraytypesConfig config = createConfig(ArraytypesConfig.class, map.get(key));
        assertEquals(3, config.intarr().size());
        assertEquals(13, config.intarr(0));
        assertEquals(10, config.intarr(1));
        assertEquals(1337, config.intarr(2));
    }

    @Test
    public void require_that_arrays_of_structs_are_resolved() {
        Element configRoot = getDocument(
                "  <config name='vespa.configdefinition.specialtokens'>" +
                        "    <tokenlist operation='append'>" +
                        "      <name>default</name>" +
                        "      <tokens operation='append'>" +
                        "        <token>dvd+-r</token>" +
                        "      </tokens>" +
                        "    </tokenlist>" +
                        "  </config>"
        );
        assertArraysOfStructs(configRoot);
    }

    private void assertArraysOfStructs(Element configRoot) {
        UserConfigRepo map = UserConfigBuilder.build(configRoot, configDefinitionStore, new BaseDeployLogger());
        assertFalse(map.isEmpty());
        ConfigDefinitionKey key = new ConfigDefinitionKey(SpecialtokensConfig.CONFIG_DEF_NAME, SpecialtokensConfig.CONFIG_DEF_NAMESPACE);
        assertNotNull(map.get(key));
        SpecialtokensConfig config = createConfig(SpecialtokensConfig.class, map.get(key));
        assertEquals(1, config.tokenlist().size());
        assertEquals("default", config.tokenlist().get(0).name());
        assertEquals(1, config.tokenlist().get(0).tokens().size());
        assertEquals("dvd+-r", config.tokenlist().get(0).tokens().get(0).token());
    }

    @Test
    public void no_exception_when_config_class_does_not_exist() {
        Element configRoot = getDocument("<config name=\"is.unknown\">" +
                "    <foo>1</foo>" +
                "</config>");
        UserConfigRepo repo = UserConfigBuilder.build(configRoot, configDefinitionStore, new BaseDeployLogger());
        ConfigPayloadBuilder builder = repo.get(new ConfigDefinitionKey("unknown", "is"));
        assertNotNull(builder);
    }

    private Element getDocument(String xml) {
        Reader xmlReader = new StringReader("<model>" + xml + "</model>");
        Document doc;
        try {
            doc = XmlHelper.getDocumentBuilder().parse(new InputSource(xmlReader));
        } catch (Exception e) {
            throw new RuntimeException();
        }
        return doc.getDocumentElement();
    }
}
