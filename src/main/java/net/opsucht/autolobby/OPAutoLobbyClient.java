package net.opsucht.autolobby;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OPAutoLobbyClient implements ClientModInitializer {
    public static final String MOD_ID = "opautolobby";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static boolean initialized = false;

    @Override
    public void onInitializeClient() {
        if (initialized) {
            LOGGER.info("[OPAutoLobby] Skipping duplicate client initialization");
            return;
        }
        initialized = true;

        LOGGER.info("[OPAutoLobby] Initializing OPAutoLobby Client Mod for Minecraft 1.21.1");

        AFKManager afkManager = AFKManager.getInstance();

        // Register in-game client commands (/opautolobby, /opautolobby gui, /autolobby)
        CommandManager.register();

        // Register tick event to monitor player movement and AFK status
        ClientTickEvents.END_CLIENT_TICK.register(afkManager::tick);

        // Register chat listeners to detect Lobby vs Citybuild/Farm/Nether/End/Luxury server switches
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (message != null) {
                afkManager.handleChatMessage(message.getString());
            }
        });

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (message != null) {
                afkManager.handleChatMessage(message.getString());
            }
        });
    }
}
