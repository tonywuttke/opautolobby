package net.opsucht.autolobby;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.opsucht.autolobby.config.ModConfig;
import net.opsucht.autolobby.config.OPAutoLobbyModMenu;

public class CommandManager {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = ClientCommandManager.literal("opautolobby")
                .executes(context -> {
                    showConfigHelp(context.getSource().getClient());
                    return 1;
                })
                .then(ClientCommandManager.literal("gui")
                    .executes(context -> {
                        MinecraftClient client = context.getSource().getClient();
                        client.send(() -> client.setScreen(OPAutoLobbyModMenu.createConfigScreen(null)));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("toggle")
                    .executes(context -> {
                        ModConfig config = AFKManager.getInstance().getConfig();
                        config.enabled = !config.enabled;
                        config.save();
                        sendMessage(context.getSource().getClient(), "§7Mod Status: " + (config.enabled ? "§aAktiviert" : "§cDeaktiviert"));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("messages")
                    .executes(context -> {
                        ModConfig config = AFKManager.getInstance().getConfig();
                        config.showStatusMessages = !config.showStatusMessages;
                        config.save();
                        sendMessage(context.getSource().getClient(), "§7Chat-Benachrichtigungen: " + (config.showStatusMessages ? "§aAktiviert" : "§cDeaktiviert"));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("warning")
                    .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(5, 3600))
                        .executes(context -> {
                            int sec = IntegerArgumentType.getInteger(context, "seconds");
                            ModConfig config = AFKManager.getInstance().getConfig();
                            config.warningTimeSeconds = sec;
                            config.save();
                            sendMessage(context.getSource().getClient(), "§7Warnungs-Zeit gesetzt auf §e" + sec + "s");
                            return 1;
                        })))
                .then(ClientCommandManager.literal("delay")
                    .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(10, 7200))
                        .executes(context -> {
                            int sec = IntegerArgumentType.getInteger(context, "seconds");
                            ModConfig config = AFKManager.getInstance().getConfig();
                            config.lobbyTimeSeconds = sec;
                            config.save();
                            sendMessage(context.getSource().getClient(), "§7Auto-Lobby Zeit gesetzt auf §e" + sec + "s");
                            return 1;
                        })));

            dispatcher.register(root);
            dispatcher.register(ClientCommandManager.literal("autolobby").redirect(dispatcher.getRoot().getChild("opautolobby")));
        });
    }

    private static void showConfigHelp(MinecraftClient client) {
        ModConfig config = AFKManager.getInstance().getConfig();
        sendMessage(client, "§c§lOPAutoLobby Configuration:");
        sendMessage(client, "§7• Status: " + (config.enabled ? "§aAktiv" : "§cDeaktiviert") + " §8(/opautolobby toggle)");
        sendMessage(client, "§7• Warnung: §e" + config.warningTimeSeconds + "s §8(/opautolobby warning <sec>)");
        sendMessage(client, "§7• Auto-Lobby: §e" + config.lobbyTimeSeconds + "s §8(/opautolobby delay <sec>)");
        sendMessage(client, "§7• Nachrichten: " + (config.showStatusMessages ? "§aAktiv" : "§cDeaktiviert") + " §8(/opautolobby messages)");
        sendMessage(client, "§7• Open GUI: §e/opautolobby gui");
    }

    private static void sendMessage(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§c§lOPAutoLobby §r§8» " + text), false);
        }
    }
}
