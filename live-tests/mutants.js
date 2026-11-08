'use strict';
// Mutants: each one breaks exactly one feature. mutate.js copies the plugin source, applies every edit below
// and builds ONE test jar in which all mutants exist but are switched off. The runner switches them on one at
// a time (through livetest-mutant.txt in the server folder) and runs the tests listed in `tests`; at least one
// of them must fail, otherwise the mutant "survived" and those tests do not really check the feature.
// Each edit's `find` must occur exactly once in the current code, so an outdated edit fails the build loudly.
const P = 'vanishpp-paper/src/main/java/net/thecommandcraft/vanishpp/';
const MAIN = P + 'Vanishpp.java';
const LISTENER = P + 'listeners/PlayerListener.java';
const PLM = P + 'hooks/ProtocolLibManager.java';
const PERMS = P + 'config/PermissionManager.java';
const on = id => `net.thecommandcraft.vanishpp.LiveTestMutant.on("${id}")`;

const mutants = [
  { id: 'everyone-sees', what: 'every player is allowed to see vanished players', tests: ['disappears from the world'] },
  { id: 'staff-blind', what: 'staff can no longer see vanished players', tests: ['staff with vanishpp.see'] },
  { id: 'fake-messages', what: 'fake join/quit messages are never sent', tests: ['fake quit and fake join'] },
  { id: 'tab-complete-scrub', what: "the tab-complete scrubber keeps every name, also in other plugins' commands", tests: ['tab-complete'] },
  { id: 'server-list-count', what: 'the server list count includes vanished players', tests: ['server list'] },
  { id: 'server-list-sample', what: 'the server list sample keeps vanished players', tests: ['server list'] },
  { id: 'night-vision-removal', what: 'unvanish goes back to the old 1-tick night vision trick', tests: ['night vision:'] },
  { id: 'night-vision-restore', what: "the player's own night vision is not given back after unvanish", tests: ['night vision:'] },
  { id: 'sleep-bar', what: 'the sleep action bar is no longer corrected', tests: ['sleep bar'] },
  { id: 'sleep-ignored', what: 'vanished players are not marked sleeping-ignored, so they block the night skip', tests: ['sleep bar'] },
  { id: 'chest-vanilla-open', what: 'containers open the vanilla way while vanished', tests: ['opening a chest'] },
  { id: 'chest-lid', what: 'chest lid animations are not suppressed', tests: ['opening a chest'] },
  { id: 'chest-sound', what: 'chest sounds are not suppressed', tests: ['opening a chest'] },
  { id: 'pickup-allowed', what: 'vanished players pick up items with can_pickup_items off', tests: ['pick up items'] },
  { id: 'pickup-rule-ignored', what: 'can_pickup_items on still blocks pickup', tests: ['pick up items'] },
  { id: 'mob-targeting', what: 'mobs may target vanished players (damage is still cancelled)', tests: ['hostile mobs never target'] },
  { id: 'mob-damage', what: 'mob damage to vanished players is not cancelled (targeting is still blocked)', tests: ['damage a mob deals'] },
  { id: 'join-always-vanished', what: 'a player who left visible rejoins vanished (the saved state is ignored)', tests: ['unvanished before leaving'] },
  { id: 'join-forgets-vanish', what: 'a player who left vanished rejoins visible', tests: ['who leaves rejoins vanished'] },
  { id: 'auto-vanish-ignored', what: 'the auto-vanish preference has no effect on join', tests: ['auto-vanish:'] },
  { id: 'unvanish-not-saved', what: 'unvanishing is not saved, so the old vanished state comes back after a restart', tests: ['server restart'] },
  { id: 'god-mode', what: 'god mode no longer cancels damage to vanished players', tests: ['god mode:'] },
  { id: 'break-allowed', what: 'vanished players break blocks with can_break_blocks off', tests: ['can_break_blocks'] },
  { id: 'break-rule-ignored', what: 'can_break_blocks on still blocks breaking', tests: ['can_break_blocks'] },
  { id: 'chat-not-held', what: 'chat is not held when can_chat is off', tests: ['can_chat'] },
  { id: 'chat-everyone', what: 'chat from a vanished player reaches everyone, not only staff', tests: ['can_chat'] },
  { id: 'chat-no-prefix', what: 'the vanished prefix is missing from chat that staff see', tests: ['can_chat'] },
];

const edits = [
  { file: MAIN, find: '            provider.setVanished(uuid, hidden);\n        } catch (RuntimeException failure) {',
    replace: `            if (hidden || !${on('unvanish-not-saved')}) provider.setVanished(uuid, hidden);\n        } catch (RuntimeException failure) {` },
  { file: LISTENER, find: '        else plugin.reconcileVanishState(player, state.vanished());',
    replace: `        else plugin.reconcileVanishState(player, ${on('join-always-vanished')} || (state.vanished() && !${on('join-forgets-vanish')}));` },
  { file: LISTENER, find: '        return state.autoVanish() && plugin.getPermissionManager().hasPermission(player, "vanishpp.vanish");',
    replace: `        return !${on('auto-vanish-ignored')} && state.autoVanish() && plugin.getPermissionManager().hasPermission(player, "vanishpp.vanish");` },
  { file: LISTENER, find: '        if (config.godMode && event.getEntity() instanceof Player player) {',
    replace: `        if (config.godMode && !${on('god-mode')} && event.getEntity() instanceof Player player) {` },
  { file: MAIN, find: '    public void onEnable() {\n', replace: '    public void onEnable() {\n        net.thecommandcraft.vanishpp.LiveTestMutant.start();\n' },
  { file: PERMS, find: '        if (!plugin.isVanished(target)) return true;\n        ConfigManager cm',
    replace: `        if (!plugin.isVanished(target)) return true;\n        if (${on('everyone-sees')}) return true;\n        if (${on('staff-blind')}) return observer.equals(target);\n        ConfigManager cm` },
  { file: MAIN, find: 'if (!(hidden ? configManager.broadcastFakeQuit : configManager.broadcastFakeJoin)) return;',
    replace: `if (${on('fake-messages')} || !(hidden ? configManager.broadcastFakeQuit : configManager.broadcastFakeJoin)) return;` },
  { file: PLM, find: '        Player target = Bukkit.getPlayerExact(name);\n        return target == null ||',
    replace: `        Player target = Bukkit.getPlayerExact(name);\n        if (${on('tab-complete-scrub')}) return true;\n        return target == null ||` },
  { file: LISTENER, find: 'event.setNumPlayers(plugin.getPermissionManager().publicOnlineCount());',
    replace: `event.setNumPlayers(${on('server-list-count')} ? event.getNumPlayers() : plugin.getPermissionManager().publicOnlineCount());` },
  { file: LISTENER, find: 'if (config.hideFromServerList) event.getListedPlayers().removeIf',
    replace: `if (config.hideFromServerList && !${on('server-list-sample')}) event.getListedPlayers().removeIf` },
  { file: MAIN, find: '            player.removePotionEffect(PotionEffectType.NIGHT_VISION);\n',
    replace: `            if (${on('night-vision-removal')}) player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 1, 0, false, false), true);\n            else player.removePotionEffect(PotionEffectType.NIGHT_VISION);\n` },
  { file: MAIN, find: '            if (restorePreNV) {', replace: `            if (restorePreNV && !${on('night-vision-restore')}) {` },
  { file: PLM, find: '        if (vanishedCount <= 0)\n            return null;', replace: `        if (vanishedCount <= 0 || ${on('sleep-bar')})\n            return null;` },
  { file: MAIN, find: '        try {\n            player.setSleepingIgnored(true);\n        } catch (Throwable ignored) {\n        }',
    replace: `        try {\n            if (!${on('sleep-ignored')}) player.setSleepingIgnored(true);\n        } catch (Throwable ignored) {\n        }` },
  { file: LISTENER, find: '        if (!isContainer) return;', replace: `        if (!isContainer || ${on('chest-vanilla-open')}) return;` },
  { file: PLM, find: 'pos.getX(), pos.getY(), pos.getZ()))\n                        event.setCancelled(true);',
    replace: `pos.getX(), pos.getY(), pos.getZ()))\n                        if (!${on('chest-lid')}) event.setCancelled(true);` },
  { file: PLM, find: 'if (matchesSilentBlock(event.getPacket(), observer))\n                    event.setCancelled(true);',
    replace: `if (matchesSilentBlock(event.getPacket(), observer))\n                    if (!${on('chest-sound')}) event.setCancelled(true);` },
  { file: LISTENER, find: '                && !rules.getRule(player, RuleManager.CAN_PICKUP_ITEMS)) {\n            event.setCancelled(true);',
    replace: `                && (${on('pickup-rule-ignored')} || !rules.getRule(player, RuleManager.CAN_PICKUP_ITEMS))) {\n            if (!${on('pickup-allowed')}) event.setCancelled(true);` },
  { file: LISTENER, find: '                // Cancelling alone is sufficient to prevent the goal from acquiring the target.',
    replace: `                if (${on('mob-targeting')}) return;\n                // Cancelling alone is sufficient to prevent the goal from acquiring the target.` },
  { file: LISTENER, find: '                && !rules.getRule(p, RuleManager.MOB_TARGETING)) {\n            event.setCancelled(true);',
    replace: `                && !rules.getRule(p, RuleManager.MOB_TARGETING)) {\n            if (!${on('mob-damage')}) event.setCancelled(true);` },
  { file: LISTENER, find: '!rules.getRule(event.getPlayer(), RuleManager.CAN_BREAK_BLOCKS)) {\n            event.setCancelled(true);',
    replace: `(${on('break-rule-ignored')} || !rules.getRule(event.getPlayer(), RuleManager.CAN_BREAK_BLOCKS))) {\n            if (!${on('break-allowed')}) event.setCancelled(true);` },
  { file: LISTENER, find: '            if (config.preventAccidentalChat && !rules.getRule(player, RuleManager.CAN_CHAT)) {\n                event.setCancelled(true);',
    replace: `            if (config.preventAccidentalChat && !rules.getRule(player, RuleManager.CAN_CHAT) && !${on('chat-not-held')}) {\n                event.setCancelled(true);` },
  { file: LISTENER, find: '        event.viewers().removeIf(viewer ->', replace: `        if (!${on('chat-everyone')}) event.viewers().removeIf(viewer ->` },
  { file: LISTENER, find: '            prefix.append(previousRenderer.render(source, displayName, message, audience))',
    replace: `            ${on('chat-no-prefix')} ? previousRenderer.render(source, displayName, message, audience) : prefix.append(previousRenderer.render(source, displayName, message, audience))` },
];

// The switch class added to the test jar. It watches livetest-mutant.txt in the server folder and logs every
// change, so the runner knows when a mutant is really active.
const switchClass = `package net.thecommandcraft.vanishpp;

/** Test-only mutant switch, added by live-tests/mutate.js. Never part of a release. */
public final class LiveTestMutant {
    private static volatile String active = "";
    private static volatile boolean started;

    private LiveTestMutant() {}

    public static boolean on(String id) { return id.equals(active); }

    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            java.nio.file.Path file = java.nio.file.Path.of("livetest-mutant.txt");
            while (true) {
                try {
                    String now = java.nio.file.Files.exists(file) ? java.nio.file.Files.readString(file).trim() : "";
                    if (!now.equals(active)) {
                        active = now;
                        org.bukkit.Bukkit.getLogger().info("LTMUTANT active=" + (now.isEmpty() ? "none" : now));
                    }
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception ignored) {
                }
            }
        }, "LiveTestMutant");
        t.setDaemon(true);
        t.start();
    }
}
`;

module.exports = { mutants, edits, switchClass };
