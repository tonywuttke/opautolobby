package net.opsucht.autolobby.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("OPAutoLobbyConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("opautolobby.json");

    public boolean enabled = true;
    public List<String> serverDomains = new ArrayList<>(List.of("opsucht.net", "opsucht.de"));
    public int warningTimeSeconds = 120; // 2 minutes default
    public int lobbyTimeSeconds = 180;   // 3 minutes default
    public String command = "lobby";

    public static ModConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                ModConfig config = GSON.fromJson(reader, ModConfig.class);
                if (config != null) {
                    if (config.serverDomains == null) config.serverDomains = new ArrayList<>(List.of("opsucht.net", "opsucht.de"));
                    if (config.command == null) config.command = "lobby";
                    config.save(); // Save back to persist any missing fields
                    return config;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load OPAutoLobby config, using defaults", e);
            }
        }
        ModConfig newConfig = new ModConfig();
        newConfig.save();
        return newConfig;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save OPAutoLobby config", e);
        }
    }
}
