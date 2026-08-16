package net.opsucht.autolobby;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.GameOptions;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.opsucht.autolobby.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class AFKManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("OPAutoLobbyAFK");
    
    // Global Singleton & Static State
    private static final AFKManager INSTANCE = new AFKManager();
    
    // Default state: inLobby = true (AFK protection paused until active gameplay subserver is detected)
    private static boolean globalInLobby = true;

    private final ModConfig config;

    private double lastX = 0.0;
    private double lastY = 0.0;
    private double lastZ = 0.0;
    private boolean initializedPos = false;

    private long lastMovementTime = System.currentTimeMillis();
    private long lastSoundTime = 0L;
    private boolean warningDisplayed = false;
    private boolean lobbyExecuted = false;
    private boolean wasOnTargetServer = false;
    private boolean announcedJoin = false;

    private final Map<String, Method> methodCache = new HashMap<>();

    private AFKManager() {
        this.config = ModConfig.load();
    }

    public static AFKManager getInstance() {
        return INSTANCE;
    }

    public ModConfig getConfig() {
        return config;
    }

    public void handleChatMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) return;
        if (rawMessage.contains("OPAutoLobby")) return;

        String upper = rawMessage.toUpperCase();

        // Chat transfer message backup matching
        if (upper.contains("VERBINDE ZU LOBBY") || upper.contains("SUPPORT")) {
            setLobbyState(true);
        } else if (upper.contains("VERBINDE ZU CB") || upper.contains("VERBINDE ZU FARM") 
                || upper.contains("VERBINDE ZU NETHER") || upper.contains("VERBINDE ZU END")
                || upper.contains("VERBINDE ZU LUXURY")) {
            setLobbyState(false);
        }
    }

    private static synchronized void setLobbyState(boolean newState) {
        // Update internal state SILENTLY without printing chat messages
        if (globalInLobby != newState) {
            globalInLobby = newState;
            INSTANCE.resetAFKTimer();
            LOGGER.info("[OPAutoLobby] State updated: inLobby = {}", newState);
        }
    }

    public void tick(MinecraftClient client) {
        try {
            if (client.player == null || client.world == null || !config.enabled) {
                return;
            }

            // Verify multiplayer connection
            if (!isOnTargetServer(client)) {
                if (wasOnTargetServer) {
                    wasOnTargetServer = false;
                    announcedJoin = false;
                    resetAFKTimer();
                }
                return;
            }

            if (!wasOnTargetServer) {
                wasOnTargetServer = true;
                resetAFKTimer();
            }

            // Inspect Scoreboard Sidebar for server state (LOBBY, SUPPORTER, CB, FARM, END, NETHER, LUXURY)
            checkScoreboardServer(client);

            // Announce join ONCE per session
            if (!announcedJoin) {
                announcedJoin = true;
                if (config.showStatusMessages) {
                    client.player.sendMessage(
                        Text.literal("§c§lOPAutoLobby §r§8» §7Mod aktiv."),
                        false
                    );
                }
            }

            // If in Lobby mode (globalInLobby == true), AFK protection is DEACTIVATED / PAUSED
            if (globalInLobby) {
                return;
            }

            // Check if player moved in 3D space via W, A, S, D, or Space
            if (hasPlayerMovedOrActed(client)) {
                lastMovementTime = System.currentTimeMillis();
                if (warningDisplayed || lobbyExecuted) {
                    warningDisplayed = false;
                    lobbyExecuted = false;
                    clearTitle(client);
                }
                return;
            }

            long afkDuration = System.currentTimeMillis() - lastMovementTime;
            long warningMs = config.warningTimeSeconds * 1000L;
            long lobbyMs = config.lobbyTimeSeconds * 1000L;

            // Stage 2: Execute command (default /lobby) after configured AFK duration
            if (afkDuration >= lobbyMs) {
                if (!lobbyExecuted) {
                    lobbyExecuted = true;
                    executeLobbyCommand(client, config.command);
                }
                return;
            }

            // Stage 1: Display red warning title & actionbar after warning duration
            if (afkDuration >= warningMs) {
                long remainingSeconds = (lobbyMs - afkDuration + 999) / 1000;
                if (remainingSeconds < 0) remainingSeconds = 0;

                if (!warningDisplayed) {
                    warningDisplayed = true;
                }

                showWarningTitleAndActionbar(client, remainingSeconds);

                long now = System.currentTimeMillis();
                if (now - lastSoundTime >= 1000L) {
                    lastSoundTime = now;
                    playWarningSound(client);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Error in OPAutoLobby tick", t);
        }
    }

    private void checkScoreboardServer(MinecraftClient client) {
        if (client.world == null) return;
        try {
            Scoreboard scoreboard = client.world.getScoreboard();
            if (scoreboard == null) return;

            ScoreboardObjective sidebarObj = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (sidebarObj == null) return;

            StringBuilder sb = new StringBuilder();
            sb.append(sidebarObj.getDisplayName().getString()).append(" ");

            for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(sidebarObj)) {
                sb.append(entry.owner()).append(" ");
                try {
                    var team = scoreboard.getScoreHolderTeam(entry.owner());
                    if (team != null) {
                        sb.append(team.getPrefix().getString()).append(" ");
                        sb.append(team.getSuffix().getString()).append(" ");
                    }
                } catch (Throwable ignored) {}
            }

            String combined = sb.toString().toUpperCase();

            // 1. DISABLE AFK protection if sidebar contains "LOBBY" or "SUPPORTER"
            if (combined.contains("LOBBY") || combined.contains("SUPPORTER") || combined.contains("SUPPORT")) {
                setLobbyState(true);
                return;
            }

            // 2. ENABLE AFK protection if sidebar contains "CB", "FARM", "END", "NETHER", or "LUXURY"
            if (combined.contains("CB") || combined.contains("CITYBUILD") || combined.contains("FARM")
                    || combined.contains("END") || combined.contains("NETHER") || combined.contains("LUXURY")) {
                setLobbyState(false);
            }
        } catch (Throwable ignored) {}
    }

    private boolean isOnTargetServer(MinecraftClient client) {
        try {
            if (client.player == null || client.world == null) {
                return false;
            }
            if (client.isInSingleplayer()) {
                return false;
            }

            if (config.serverDomains == null || config.serverDomains.isEmpty()) {
                return true;
            }

            ServerInfo serverEntry = client.getCurrentServerEntry();
            String address = null;
            if (serverEntry != null && serverEntry.address != null) {
                address = serverEntry.address.toLowerCase();
            }

            if (address == null && client.getNetworkHandler() != null && client.getNetworkHandler().getBrand() != null) {
                address = client.getNetworkHandler().getBrand().toLowerCase();
            }

            if (address == null) {
                return true;
            }

            for (String domain : config.serverDomains) {
                if (domain != null && !domain.isEmpty() && address.contains(domain.toLowerCase().trim())) {
                    return true;
                }
            }

            return address.contains("opsucht");
        } catch (Throwable t) {
            LOGGER.error("Error checking target server", t);
        }
        return false;
    }

    private boolean hasPlayerMovedOrActed(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;

        if (client.currentScreen != null) {
            return false;
        }

        double currentX = getX(player);
        double currentY = getY(player);
        double currentZ = getZ(player);

        if (!initializedPos) {
            lastX = currentX;
            lastY = currentY;
            lastZ = currentZ;
            initializedPos = true;
            return false;
        }

        double dx = Math.abs(currentX - lastX);
        double dy = Math.abs(currentY - lastY);
        double dz = Math.abs(currentZ - lastZ);
        boolean positionMoved = (dx > 0.01 || dy > 0.01 || dz > 0.01);

        GameOptions options = client.options;
        boolean movementKeyPressed = false;
        if (options != null) {
            movementKeyPressed = (options.forwardKey != null && options.forwardKey.isPressed())
                    || (options.backKey != null && options.backKey.isPressed())
                    || (options.leftKey != null && options.leftKey.isPressed())
                    || (options.rightKey != null && options.rightKey.isPressed())
                    || (options.jumpKey != null && options.jumpKey.isPressed());
        }

        lastX = currentX;
        lastY = currentY;
        lastZ = currentZ;

        return movementKeyPressed || positionMoved;
    }

    private double getX(ClientPlayerEntity player) {
        try { return player.getX(); } catch (Throwable t) { return getPlayerDouble(player, "getX", "method_23317", "m_20185_"); }
    }

    private double getY(ClientPlayerEntity player) {
        try { return player.getY(); } catch (Throwable t) { return getPlayerDouble(player, "getY", "method_23318", "m_20186_"); }
    }

    private double getZ(ClientPlayerEntity player) {
        try { return player.getZ(); } catch (Throwable t) { return getPlayerDouble(player, "getZ", "method_23321", "m_20189_"); }
    }

    private double getPlayerDouble(Object player, String... candidateNames) {
        Class<?> clazz = player.getClass();
        String cacheKey = clazz.getName() + ":" + String.join(",", candidateNames);

        Method m = methodCache.get(cacheKey);
        if (m != null) {
            try {
                Object res = m.invoke(player);
                if (res instanceof Number) return ((Number) res).doubleValue();
            } catch (Throwable ignored) {}
        }

        while (clazz != null && clazz != Object.class) {
            for (String name : candidateNames) {
                try {
                    Method method = clazz.getDeclaredMethod(name);
                    method.setAccessible(true);
                    Object res = method.invoke(player);
                    if (res instanceof Number) {
                        methodCache.put(cacheKey, method);
                        return ((Number) res).doubleValue();
                    }
                } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return 0.0;
    }

    private void showWarningTitleAndActionbar(MinecraftClient client, long remainingSeconds) {
        try {
            Text titleText = Text.literal("§c§l⚠ AFK WARNUNG ⚠");
            Text subtitleText = Text.literal("§cDu bist AFK! Automatische Lobby in §e" + remainingSeconds + "s§c!");

            if (client.inGameHud != null) {
                client.inGameHud.setTitle(titleText);
                client.inGameHud.setSubtitle(subtitleText);
                client.inGameHud.setTitleTicks(0, 30, 5);
            }

            if (client.player != null) {
                client.player.sendMessage(Text.literal("§c§l⚠ AFK WARNUNG ⚠ §cAuto-Lobby in §e" + remainingSeconds + "s"), true);
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to render warning title/actionbar", t);
        }
    }

    private void clearTitle(MinecraftClient client) {
        try {
            if (client.inGameHud != null) {
                client.inGameHud.setTitle(Text.literal(""));
                client.inGameHud.setSubtitle(Text.literal(""));
            }
        } catch (Throwable t) {
            // Ignore
        }
    }

    private void playWarningSound(MinecraftClient client) {
        try {
            if (client.player != null && client.world != null) {
                client.world.playSound(
                    client.player,
                    client.player.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
                    SoundCategory.MASTER,
                    1.0f,
                    1.5f
                );
            }
        } catch (Throwable t) {
            // Ignore
        }
    }

    private void executeLobbyCommand(MinecraftClient client, String commandStr) {
        if (client.player == null) return;
        try {
            Object networkHandler = client.player.networkHandler;
            if (networkHandler != null) {
                boolean sent = false;

                for (String methodName : new String[]{"sendCommand", "sendChatCommand", "method_44099", "m_246623_"}) {
                    try {
                        Method m = findMethod(networkHandler.getClass(), methodName, String.class);
                        if (m != null) {
                            m.invoke(networkHandler, commandStr);
                            sent = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }

                if (!sent) {
                    for (String methodName : new String[]{"sendChatMessage", "method_44096", "m_246336_"}) {
                        try {
                            Method m = findMethod(networkHandler.getClass(), methodName, String.class);
                            if (m != null) {
                                m.invoke(networkHandler, "/" + commandStr);
                                sent = true;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to execute lobby command", t);
        }
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        while (clazz != null && clazz != Object.class) {
            try {
                Method m = clazz.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public void resetAFKTimer() {
        initializedPos = false;
        lastMovementTime = System.currentTimeMillis();
        lastSoundTime = 0L;
        warningDisplayed = false;
        lobbyExecuted = false;
    }

    public void reset() {
        resetAFKTimer();
        wasOnTargetServer = false;
        announcedJoin = false;
        globalInLobby = true;
    }
}
