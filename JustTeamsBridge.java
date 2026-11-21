package dev.alias.teamechest;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Talks to justTeams without compiling against it. Uses MethodHandles with exact signatures
 * (not Class#getMethod), because justTeams' main class mentions Vault's Economy in its method
 * signatures and getMethod() would crash with NoClassDefFoundError when Vault is missing.
 */
final class JustTeamsBridge {

    private final Plugin jt;
    private final Object teamManager;
    private final Object configManager;
    private final Object featureManager;
    private final Object messageManager;
    private final Object storageManager;

    private final MethodHandle getPlayerTeamCached;
    private final MethodHandle getPlayerTeamCache;
    private final MethodHandle isEnderchestEnabled;
    private final MethodHandle isFeatureAllowed;
    private final MethodHandle canAffordAndPay;
    private final MethodHandle teamIsLocked;
    private final MethodHandle teamGetChest;
    private final MethodHandle teamHasViewers;
    private final MethodHandle teamUnlock;
    private final MethodHandle teamGetId;
    private final MethodHandle teamGetMember;
    private final MethodHandle memberCanUse;
    private final MethodHandle getStorage;
    private final MethodHandle releaseLock;
    private final MethodHandle sendMessage;

    JustTeamsBridge(Plugin jt) throws Throwable {
        this.jt = jt;
        ClassLoader cl = jt.getClass().getClassLoader();
        MethodHandles.Lookup lk = MethodHandles.publicLookup();
        Class<?> jtClass = jt.getClass();

        Class<?> teamManagerC = cls(cl, "eu.kotori.justTeams.team.TeamManager");
        Class<?> configManagerC = cls(cl, "eu.kotori.justTeams.config.ConfigManager");
        Class<?> featureManagerC = cls(cl, "eu.kotori.justTeams.util.FeatureRestrictionManager");
        Class<?> messageManagerC = cls(cl, "eu.kotori.justTeams.config.MessageManager");
        Class<?> storageManagerC = cls(cl, "eu.kotori.justTeams.storage.StorageManager");
        Class<?> storageC = cls(cl, "eu.kotori.justTeams.storage.IDataStorage");
        Class<?> teamC = cls(cl, "eu.kotori.justTeams.team.Team");
        Class<?> memberC = cls(cl, "eu.kotori.justTeams.team.TeamPlayer");

        this.teamManager = lk.findVirtual(jtClass, "getTeamManager", MethodType.methodType(teamManagerC))
                .invoke(jt);
        this.configManager = lk.findVirtual(jtClass, "getConfigManager", MethodType.methodType(configManagerC))
                .invoke(jt);
        this.featureManager = lk.findVirtual(jtClass, "getFeatureRestrictionManager",
                MethodType.methodType(featureManagerC)).invoke(jt);
        this.messageManager = lk.findVirtual(jtClass, "getMessageManager", MethodType.methodType(messageManagerC))
                .invoke(jt);
        this.storageManager = lk.findVirtual(jtClass, "getStorageManager", MethodType.methodType(storageManagerC))
                .invoke(jt);

        this.getPlayerTeamCached = lk.findVirtual(teamManagerC, "getPlayerTeamCached",
                MethodType.methodType(teamC, UUID.class));
        this.getPlayerTeamCache = lk.findVirtual(teamManagerC, "getPlayerTeamCache",
                MethodType.methodType(Map.class));
        this.isEnderchestEnabled = lk.findVirtual(configManagerC, "isTeamEnderchestEnabled",
                MethodType.methodType(boolean.class));
        this.isFeatureAllowed = lk.findVirtual(featureManagerC, "isFeatureAllowed",
                MethodType.methodType(boolean.class, Player.class, String.class));
        this.canAffordAndPay = lk.findVirtual(featureManagerC, "canAffordAndPay",
                MethodType.methodType(boolean.class, Player.class, String.class));
        this.teamIsLocked = lk.findVirtual(teamC, "isEnderChestLocked", MethodType.methodType(boolean.class));
        this.teamGetChest = lk.findVirtual(teamC, "getEnderChest", MethodType.methodType(Inventory.class));
        this.teamHasViewers = lk.findVirtual(teamC, "hasEnderChestViewers", MethodType.methodType(boolean.class));
        this.teamUnlock = lk.findVirtual(teamC, "unlockEnderChest", MethodType.methodType(void.class));
        this.teamGetId = lk.findVirtual(teamC, "getId", MethodType.methodType(int.class));
        this.teamGetMember = lk.findVirtual(teamC, "getMember", MethodType.methodType(memberC, UUID.class));
        this.memberCanUse = lk.findVirtual(memberC, "canUseEnderChest", MethodType.methodType(boolean.class));
        this.getStorage = lk.findVirtual(storageManagerC, "getStorage", MethodType.methodType(storageC));
        this.releaseLock = lk.findVirtual(storageC, "releaseEnderChestLock",
                MethodType.methodType(void.class, int.class));

        MethodHandle send = null;
        try {
            send = lk.findVirtual(messageManagerC, "sendMessage",
                    MethodType.methodType(void.class, CommandSender.class, String.class, TagResolver[].class))
                    .asFixedArity();
        } catch (Throwable ignored) {
            // cosmetic only
        }
        this.sendMessage = send;
    }

    private static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    /** Runs a handle, turning any Throwable (even a linkage Error) into an Exception. */
    private static Object call(MethodHandle h, Object... args) throws Exception {
        try {
            return h.invokeWithArguments(args);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception(t);
        }
    }

    Object getTeam(UUID uuid) throws Exception {
        return call(getPlayerTeamCached, teamManager, uuid);
    }

    @SuppressWarnings("unchecked")
    Collection<Object> getCachedTeams() throws Exception {
        Object map = call(getPlayerTeamCache, teamManager);
        if (map instanceof Map<?, ?> m) {
            return (Collection<Object>) (Collection<?>) m.values();
        }
        return Collections.emptyList();
    }

    boolean isEnderchestEnabled() throws Exception {
        return (boolean) call(isEnderchestEnabled, configManager);
    }

    boolean isFeatureAllowed(Player p) throws Exception {
        return (boolean) call(isFeatureAllowed, featureManager, p, "enderchest");
    }

    /** Also charges the player and tells them if they can't afford it, exactly like /team enderchest does. */
    boolean canAffordAndPay(Player p) throws Exception {
        return (boolean) call(canAffordAndPay, featureManager, p, "enderchest");
    }

    boolean isLocked(Object team) throws Exception {
        return (boolean) call(teamIsLocked, team);
    }

    Object getChest(Object team) throws Exception {
        return call(teamGetChest, team);
    }

    boolean hasViewers(Object team) throws Exception {
        return (boolean) call(teamHasViewers, team);
    }

    void unlock(Object team) throws Exception {
        call(teamUnlock, team);
    }

    int getId(Object team) throws Exception {
        return (int) call(teamGetId, team);
    }

    boolean canUseEnderChest(Object team, UUID uuid) throws Exception {
        Object member = call(teamGetMember, team, uuid);
        return member != null && (boolean) call(memberCanUse, member);
    }

    /** Releases the cross-server database lock row. Blocking call - run it async. */
    void releaseDbLock(int teamId) throws Exception {
        Object storage = call(getStorage, storageManager);
        if (storage != null) {
            call(releaseLock, storage, teamId);
        }
    }

    /** Sends a justTeams message key (e.g. enderchest_opened) to the player. Best effort. */
    void sendMessage(Player p, String key) {
        if (sendMessage == null) {
            return;
        }
        try {
            call(sendMessage, messageManager, p, key, new TagResolver[0]);
        } catch (Exception ignored) {
            // cosmetic only
        }
    }

    Plugin plugin() {
        return jt;
    }
}
