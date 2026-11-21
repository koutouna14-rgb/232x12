package dev.alias.teamechest;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * justTeams addon (justTeams itself is not modified):
 *  1. /team echest -> /team enderchest
 *  2. Teammates can join an ender chest that is already open instead of hitting the lock message.
 *  3. Clears a stuck ender chest lock (locked, nobody viewing) after STUCK_MS.
 */
public final class TeamEchestAddon extends JavaPlugin implements Listener {

    // /team, /guild, /clan, /party (optionally namespaced) + echest|ec|enderchest + extra args
    private static final Pattern PATTERN = Pattern.compile(
            "^/((?:[a-z0-9_.-]+:)?(?:team|guild|clan|party))\\s+(echest|ec|enderchest)(\\s.*)?$",
            Pattern.CASE_INSENSITIVE);

    private static final long STUCK_MS = 15_000L;

    private JustTeamsBridge bridge;
    private final Map<Integer, Long> stuckSince = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Plugin jt = getServer().getPluginManager().getPlugin("justTeams");
        if (jt != null) {
            try {
                bridge = new JustTeamsBridge(jt);
                getServer().getGlobalRegionScheduler()
                        .runAtFixedRate(this, task -> checkStuckLocks(), 100L, 100L);
            } catch (Throwable e) {
                bridge = null;
                getLogger().warning("Could not hook into justTeams (" + e + "). "
                        + "Only the /team echest alias will work.");
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Matcher m = PATTERN.matcher(event.getMessage());
        if (!m.matches()) {
            return;
        }
        String rest = m.group(3) == null ? "" : m.group(3);

        if (!m.group(2).equalsIgnoreCase("enderchest")) {
            // echest -> enderchest (ec is already understood by justTeams; rewriting is harmless)
            event.setMessage("/" + m.group(1) + " enderchest" + rest);
        }

        if (tryJoinOpenChest(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // Console and command blocks (no leading slash).
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        Matcher m = PATTERN.matcher("/" + event.getCommand());
        if (m.matches() && m.group(2).equalsIgnoreCase("echest")) {
            String rest = m.group(3) == null ? "" : m.group(3);
            event.setCommand(m.group(1) + " enderchest" + rest);
        }
    }

    /**
     * If the team's ender chest is already open on this server, open that same inventory for
     * this player instead of letting justTeams answer "being used on another server".
     * Runs the same checks as /team enderchest (feature on, world allowed, permission, cost).
     * Returns true when handled here (command should be cancelled).
     */
    private boolean tryJoinOpenChest(Player player) {
        if (bridge == null) {
            return false;
        }
        try {
            Object team = bridge.getTeam(player.getUniqueId());
            if (team == null || !bridge.isLocked(team) || !bridge.hasViewers(team)) {
                return false; // normal justTeams flow
            }
            Object chest = bridge.getChest(team);
            if (!(chest instanceof org.bukkit.inventory.Inventory inventory)) {
                return false;
            }
            if (!bridge.isEnderchestEnabled() || !bridge.isFeatureAllowed(player)) {
                return false; // let justTeams show its own message
            }
            if (!bridge.canUseEnderChest(team, player.getUniqueId())
                    && !player.hasPermission("justteams.bypass.enderchest.use")) {
                return false;
            }
            if (!bridge.canAffordAndPay(player)) {
                return true; // justTeams already told the player why
            }
            if (player.openInventory(inventory) != null) {
                bridge.sendMessage(player, "enderchest_opened");
            }
            return true;
        } catch (Exception e) {
            getLogger().warning("Join-open-chest failed, falling back to justTeams: " + e);
            return false;
        }
    }

    /** Every 5s: a team chest that is locked with no viewers for STUCK_MS is a leaked lock. */
    private void checkStuckLocks() {
        if (bridge == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (Object team : bridge.getCachedTeams()) {
                if (team == null) {
                    continue;
                }
                int id = bridge.getId(team);
                if (!seen.add(id)) {
                    continue;
                }
                if (bridge.isLocked(team) && !bridge.hasViewers(team)) {
                    Long since = stuckSince.putIfAbsent(id, now);
                    if (since != null && now - since > STUCK_MS) {
                        stuckSince.remove(id);
                        bridge.unlock(team);
                        getLogger().warning("Cleared stuck ender chest lock for team id " + id);
                        getServer().getAsyncScheduler().runNow(this, t -> {
                            try {
                                bridge.releaseDbLock(id);
                            } catch (Exception ignored) {
                                // single-server mode or storage without a lock table
                            }
                        });
                    }
                } else {
                    stuckSince.remove(id);
                }
            }
            Iterator<Integer> it = stuckSince.keySet().iterator();
            while (it.hasNext()) {
                if (!seen.contains(it.next())) {
                    it.remove();
                }
            }
        } catch (Exception e) {
            getLogger().warning("Stuck-lock check failed: " + e);
        }
    }
}
