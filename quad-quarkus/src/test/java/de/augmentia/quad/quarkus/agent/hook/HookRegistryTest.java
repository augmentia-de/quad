package de.augmentia.quad.quarkus.agent.hook;

import de.augmentia.quad.core.hitl.HITLPlugin;
import de.augmentia.quad.core.hitl.checkpoint.CheckpointService;
import de.augmentia.quad.core.hook.plugin.Plugin;
import de.augmentia.quad.quarkus.hitl.HitlService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HookRegistryTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void invokeInit(HookRegistry registry) throws Exception {
        Method m = registry.getClass().getDeclaredMethod("init");
        m.setAccessible(true);
        m.invoke(registry);
    }

    private static Plugin dummyPlugin(String name) {
        return new Plugin() {
            @Override
            public String name() { return name; }
        };
    }

    @Test
    void registersHitlFromCheckpointService() throws Exception {
        HookRegistry registry = new HookRegistry();
        HitlService stub = new HitlService() {
            @Override
            public CheckpointService checkpointService() {
                return new CheckpointService("writeFile", 5000);
            }
        };
        setField(registry, "hitlService", stub);
        invokeInit(registry);

        assertInstanceOf(HITLPlugin.class, registry.create("hitl"));
        assertInstanceOf(HITLPlugin.class, registry.create("HITL"));
    }

    @Test
    void doesNotRegisterHitlWithoutHitlService() throws Exception {
        HookRegistry registry = new HookRegistry();
        setField(registry, "hitlService", null);
        invokeInit(registry);

        assertNull(registry.create("hitl"));
    }

    @Test
    void fromNamesMapsKnownAndSkipsUnknown() {
        HookRegistry registry = new HookRegistry();
        registry.register("custom", () -> dummyPlugin("custom"));

        List<Plugin> plugins = registry.fromNames(new String[]{"custom", "unknown-Hook", "CUSTOM"});

        assertEquals(2, plugins.size());
        assertEquals("custom", plugins.get(0).name());
        assertEquals("custom", plugins.get(1).name());
    }

    @Test
    void fromNamesHandlesNullAndEmpty() {
        HookRegistry registry = new HookRegistry();
        assertEquals(0, registry.fromNames(null).size());
        assertEquals(0, registry.fromNames(new String[0]).size());
        assertEquals(0, registry.fromNames(new String[]{" "}).size());
    }

    @Test
    void listAllExposesCatalogWithDescription() {
        HookRegistry registry = new HookRegistry();
        registry.register("custom", () -> dummyPlugin("custom"), "my hook description");

        var all = registry.listAll();
        assertEquals(1, all.size());
        assertEquals("custom", all.get(0).get("name"));
        assertEquals("hook", all.get(0).get("type"));
        assertEquals(true, all.get(0).get("active"));
        assertEquals("my hook description", all.get(0).get("description"));
    }
}