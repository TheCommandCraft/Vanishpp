package net.thecommandcraft.vanishpp.hooks;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Soft-dependency hook for Geyser/Floodgate.
 * Provides native Bedrock form UIs for vanish toggle and rules editing.
 * Gracefully degrades to no-ops when Floodgate is not installed.
 */
public class FloodgateHook {

    private final boolean available;
    private final Vanishpp plugin;

    public FloodgateHook(Vanishpp plugin) {
        this.plugin = plugin;
        boolean ok = false;
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            FloodgateApi.getInstance();
            ok = true;
            plugin.getLogger().info("[Hooks] Floodgate detected — Bedrock form UI enabled.");
        } catch (Exception e) {
            plugin.getLogger().info("[Hooks] Floodgate not found — Bedrock form UI disabled.");
        }
        this.available = ok;
    }

    /** Returns true if Floodgate is installed and functional. */
    public boolean isAvailable() {
        return available;
    }

    /** Returns true if the given UUID belongs to a connected Bedrock (Floodgate) player. */
    public boolean isBedrockPlayer(UUID uuid) {
        if (!available) return false;
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Shows a ModalForm (yes/no) to a Bedrock player for the vanish toggle.
     * {@code onConfirm} runs on the main thread when the player taps the confirm button.
     */
    public void sendVanishToggleForm(Player player, boolean currentlyVanished, Runnable onConfirm) {
        if (!available) return;
        try {
            ModalForm form = ModalForm.builder()
                    .title("Vanish Toggle")
                    .content(currentlyVanished
                            ? "You are currently VANISHED.\nDo you want to unvanish?"
                            : "You are currently visible.\nDo you want to vanish?")
                    .button1(currentlyVanished ? "Unvanish" : "Vanish")
                    .button2("Cancel")
                    .validResultHandler(response -> {
                        if (response.clickedFirst()) {
                            plugin.getVanishScheduler().runGlobal(onConfirm);
                        }
                    })
                    .build();
            FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        } catch (Exception e) {
            plugin.getLogger().warning("[Floodgate] Failed to send vanish form: " + e.getMessage());
        }
    }

    /**
     * Shows a SimpleForm listing all current vanish rules as toggle buttons.
     * {@code onToggle} is called with (ruleKey, newValue) on the main thread when a button is tapped.
     */
    public void sendRulesForm(Player player, Map<String, Boolean> rules,
                               BiConsumer<String, Boolean> onToggle) {
        if (!available) return;
        try {
            List<String> keys = new ArrayList<>(rules.keySet());
            SimpleForm.Builder builder = SimpleForm.builder().title("Vanish Rules");
            for (String key : keys) {
                boolean val = rules.get(key);
                builder.button(key + ": " + (val ? "§aON" : "§cOFF"));
            }
            builder.validResultHandler(response -> {
                int idx = response.clickedButtonId();
                if (idx < 0 || idx >= keys.size()) return;
                String key = keys.get(idx);
                boolean toggled = !rules.get(key);
                plugin.getVanishScheduler().runGlobal(() -> onToggle.accept(key, toggled));
            });
            FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder.build());
        } catch (Exception e) {
            plugin.getLogger().warning("[Floodgate] Failed to send rules form: " + e.getMessage());
        }
    }
}
