package net.opsucht.autolobby.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;

public class OPAutoLobbyModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> createConfigScreen(parent);
    }

    public static Screen createConfigScreen(Screen parent) {
        ModConfig config = ModConfig.load();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("OPAutoLobby Settings"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory general = builder.getOrCreateCategory(Text.literal("OPAutoLobby Config"));

        // 1. Mod Enabled Toggle
        general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable Mod"), config.enabled)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Enable or disable AFK protection on opsucht.net"))
                .setSaveConsumer(newValue -> config.enabled = newValue)
                .build());

        // 2. Chat Notifications Toggle
        general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Show Chat Notifications"), config.showStatusMessages)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show chat messages when joining server or resetting AFK status"))
                .setSaveConsumer(newValue -> config.showStatusMessages = newValue)
                .build());

        // 3. Warning Time (Seconds)
        general.addEntry(entryBuilder.startIntField(Text.literal("Warning Delay (Seconds)"), config.warningTimeSeconds)
                .setDefaultValue(120)
                .setMin(5)
                .setMax(3600)
                .setTooltip(Text.literal("Seconds of 3D movement inactivity before red title warning appears (default: 120s)"))
                .setSaveConsumer(newValue -> config.warningTimeSeconds = newValue)
                .build());

        // 4. Auto-Lobby Command Time (Seconds)
        general.addEntry(entryBuilder.startIntField(Text.literal("Auto-Lobby Delay (Seconds)"), config.lobbyTimeSeconds)
                .setDefaultValue(180)
                .setMin(10)
                .setMax(7200)
                .setTooltip(Text.literal("Seconds of 3D movement inactivity before executing /lobby (default: 180s)"))
                .setSaveConsumer(newValue -> config.lobbyTimeSeconds = newValue)
                .build());

        // 5. Auto-Lobby Command
        general.addEntry(entryBuilder.startStrField(Text.literal("Lobby Command"), config.command)
                .setDefaultValue("lobby")
                .setTooltip(Text.literal("Command executed when AFK time limit is reached (without leading slash)"))
                .setSaveConsumer(newValue -> config.command = newValue)
                .build());

        // 6. Target Server Domains
        String currentDomains = config.serverDomains != null ? String.join(", ", config.serverDomains) : "opsucht.net, opsucht.de";
        general.addEntry(entryBuilder.startStrField(Text.literal("Target Server Domains"), currentDomains)
                .setDefaultValue("opsucht.net, opsucht.de")
                .setTooltip(Text.literal("Comma-separated domains where AFK protection runs"))
                .setSaveConsumer(newValue -> {
                    if (newValue != null) {
                        String[] split = newValue.split(",");
                        config.serverDomains = new ArrayList<>();
                        for (String s : split) {
                            String trimmed = s.trim();
                            if (!trimmed.isEmpty()) {
                                config.serverDomains.add(trimmed);
                            }
                        }
                    }
                })
                .build());

        builder.setSavingRunnable(config::save);

        return builder.build();
    }
}
