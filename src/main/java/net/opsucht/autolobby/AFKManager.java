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
    private final ModConfig config;

    private double lastX = 0.0;
    private double lastY = 0.0;
    private double lastZ = 0.0;
    private boolean initializedPos = false;

    private long lastMovementTime = System.currentTimeMillis();
    private long lastSoundTime = 0L;
    private long lastStatusMsgTime = 0L;
    private boolean warningDisplayed = false;
    private boolean lobbyExecuted = false;
    private boolean wasOnTargetServer = false;
    private boolean announcedJoin = false;

    // Default to inLobby = true on join, as players spawn in the main Lobby server
    private boolean inLobby = true;

    private final Map<String, Method> methodCache = new HashMap<>();

    public AFKManager() {
        this.config = ModConfig.load();
    }

    public ModConfig getConfig() {
        return config;
    }

    public void handleChatMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) return;
        
        // Ignore the mod's own chat messages to prevent feedback loops
        if (rawMessage.contains("OPAutoLobby")) return;

        String upper = rawMessage.toUpperCase();

        // Detect connection to any LOBBY server (e.g. "OPSUCHT » Verbinde zu LOBBY-4...")
        if (upper.contains("VERBINDE ZU LOBBY")) {
            setLobbyState(true);
        } else if (upper.contains("VERBINDE ZU CB") || upper.contains("VERBINDE ZU FARM") 
                || upper.contains("VERBINDE ZU NETHER") || upper.contains("VERBINDE ZU END")) {
            setLobbyState(false);
        }
    }

    private void setLobbyState(boolean newState) {
        if (this.inLobby != newState) {
            this.inLobby = newState;
            resetAFKTimer();
            LOGGER.info("[OPAutoLobby] Lobby state changed to: {}", newState);

            long now = System.currentTimeMillis();
            if (now - lastStatusMsgTime > 2000L) {
                lastStatusMsgTime = now;
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    if (newState) {
                        client.player.sendMessage(Text.literal("§c§lOPAutoLobby §r§8» §7Lobby erkannt - AFK-Schutz pausiert."), false);
                    } else {
                        client.player.sendMessage(Text.literal("§c§lOPAutoLobby §r§8» §7Citybuild/Farm/Nether/End erkannt - AFK-Schutz gestartet!"), false);
                    }
                }
            }
        }
    }

    public void tick(MinecraftClient client) {
        try {
            if (client.player == null || client.world == null || !config.enabled) {
                return;
            }

            // Verify if player is on target server
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
                inLobby = true; // Default to Lobby mode on initial join
                resetAFKTimer();
            }

            // Dynamically inspect scoreboard sidebar to detect current server (Lobby vs CB/Farm)
            checkScoreboardServer(client);

            // Announce join ONCE only if not currently in lobby
            if (!announcedJoin && !inLobby) {
                announcedJoin = true;
                client.player.sendMessage(
                    Text.literal("§c§lOPAutoLobby §r§8» §7Mod aktiv."),
                    false
                );
            }

            // If in Lobby, completely pause AFK tracking & commands!
            if (inLobby) {
                return;
            }

            // Check if player moved in 3D space via W, A, S, D, or Space
            if (hasPlayerMovedOrActed(client)) {
                lastMovementTime = System.currentTimeMillis();
                if (warningDisplayed || lobbyExecuted) {
                    warningDisplayed = false;
                    lobbyExecuted = false;
                    clearTitle(client);
                    client.player.sendMessage(Text.literal("§c§lOPAutoLobby §r§8» §aW/A/S/D/Space Bewegung erkannt - AFK Timer zurückgesetzt."), false);
                }
                return;
            }

            long afkDuration = System.currentTimeMillis() - lastMovementTime;
            long warningMs = config.warningTimeSeconds * 1000L;
            long lobbyMs = config.lobbyTimeSeconds * 1000L;

            // Stage 2: Execute command (default /lobby) after configured lobby duration
            if (afkDuration >= lobbyMs) {
                if (!lobbyExecuted) {
                    lobbyExecuted = true;
                    executeLobbyCommand(client, config.command);
                }
                return;
            }

            // Stage 1: Display red warning title & actionbar starting at configured warning duration
            if (afkDuration >= warningMs) {
                long remainingSeconds = (lobbyMs - afkDuration + 999) / 1000;
                if (remainingSeconds < 0) remainingSeconds = 0;

                if (!warningDisplayed) {
                    warningDisplayed = true;
                    client.player.sendMessage(Text.literal("§c§lOPAutoLobby §r§8» §c⚠ WARNUNG: Du bist " + config.warningTimeSeconds + " Sekunden AFK!"), false);
                }

                showWarningTitleAndActionbar(client, remainingSeconds);

                // Play subtle warning sound once per second
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
            if (sidebarObj != null) {
                String title = sidebarObj.getDisplayName().getString().toUpperCase();
                if (title.contains("LOBBY")) {
                    setLobbyState(true);
                    return;
                } else if (title.contains("CITYBUILD") || title.contains("CB") || title.contains("FARM")) {
                    setLobbyState(false);
                    return;
                }

                for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(sidebarObj)) {
                    String owner = entry.owner().toUpperCase();
                    if (owner.contains("LOBBY")) {
                        setLobbyState(true);
                        return;
                    } else if (owner.contains("CITYBUILD") || owner.contains("CB-") || owner.contains("FARM")) {
                        setLobbyState(false);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean isOnTargetServer(MinecraftClient client) {
        try {
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
                return !client.isInSingleplayer();
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

            client.player.sendMessage(Text.literal("§c§lOPAutoLobby §r§8» §c3 Minuten AFK erreicht. Command /" + commandStr + " wurde ausgeführt!"), false);
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
        inLobby = true;
    }
}
