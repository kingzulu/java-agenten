package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.support.TestTools;
import com.zuluindustries.agenten.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapabilitiesTest {

    final Capabilities capabilities = new Capabilities(new TestTools().registry, TestTools.skills());

    @Test
    void withoutSkillsAllAllowedToolsAreSent() {
        Capabilities.Prepared prepared = capabilities.prepare(List.of("save", "lookup"), List.of());
        assertEquals(List.of("lookup", "save"), names(prepared.tools()));
    }

    @Test
    void singleSkillReducesTools() {
        Capabilities.Prepared prepared = capabilities.prepare(List.of("lookup", "save"), List.of("suche"));
        assertEquals(List.of("lookup"), names(prepared.tools()));
    }

    @Test
    void multipleSkillsOnlyExposeRoutingTool() {
        Capabilities.Prepared prepared = capabilities.prepare(List.of("lookup", "save"), List.of("suche", "ablage"));
        assertEquals(List.of("activate_skill"), names(prepared.tools()));
        assertEquals(2, prepared.skills().size());
    }

    @Test
    void rejectsUnknownAndDisallowed() {
        assertThrows(IllegalArgumentException.class, () -> capabilities.prepare(List.of("gibtsnicht"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> capabilities.prepare(List.of(), List.of("gibtsnicht")));
        assertThrows(AgentPermissionException.class, () -> capabilities.prepare(List.of("lookup"), List.of("verboten")));
    }

    private static List<String> names(List<ToolSpec> tools) {
        return tools.stream().map(ToolSpec::name).toList();
    }
}
