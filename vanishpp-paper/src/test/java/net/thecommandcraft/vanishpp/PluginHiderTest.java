package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.thecommandcraft.vanishpp.utils.PluginHider;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The fake /plugins output has to look exactly like Paper's own. */
class PluginHiderTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static String text(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static Component find(Component root, String content) {
        if (root instanceof net.kyori.adventure.text.TextComponent t && t.content().equals(content))
            return root;
        for (Component child : root.children()) {
            Component hit = find(child, content);
            if (hit != null)
                return hit;
        }
        return null;
    }

    @Test
    void layoutMatchesPaper() {
        Plugin plugin = MockBukkit.load(Vanishpp.class);
        List<Component> lines = PluginHider.buildPluginList(List.of(plugin));

        assertEquals("ℹ Server Plugins (1):", text(lines.get(0)));
        assertEquals("Bukkit Plugins:", text(lines.get(1)), "no count when only one kind exists");
        assertEquals(" - Vanishpp", text(lines.get(2)));
        assertEquals(2 + 1, lines.size());
    }

    @Test
    void namesAreColoredAndClickable() {
        Plugin plugin = MockBukkit.load(Vanishpp.class);
        Component name = find(PluginHider.buildPluginList(List.of(plugin)).get(2), "Vanishpp");
        assertNotNull(name);
        assertEquals(NamedTextColor.GREEN, name.color());
        assertEquals(ClickEvent.runCommand("/version Vanishpp"), name.clickEvent());
    }

    @Test
    void emptyListStillHasHeaderOnly() {
        List<Component> lines = PluginHider.buildPluginList(List.of());
        assertEquals(1, lines.size());
        assertEquals("ℹ Server Plugins (0):", text(lines.get(0)));
    }
}
