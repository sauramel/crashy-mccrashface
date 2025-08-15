package com.crashymccrashface.crashybought;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class Config {

    private static final String CONFIG_FILE = "bot_config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("DISCORD_BOT_TOKEN")
    private String discordBotToken;

    @SerializedName("GEMINI_API_KEY")
    private String geminiApiKey;

    @SerializedName("guilds")
    private Map<String, GuildConfig> guilds;

    @SerializedName("default_categories")
    private List<String> defaultCategories;

    @SerializedName("forum_channel_id")
    private long forumChannelId;

    @SerializedName("auto_analysis_channel_id")
    private long autoAnalysisChannelId;

    public static Config load() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            System.out.println("Config file not found. Creating a template.");
            createTemplate();
            System.err.println("Please fill in your tokens in bot_config.json and restart the bot.");
            System.exit(1);
        }

        try (FileReader reader = new FileReader(configFile)) {
            Config config = GSON.fromJson(reader, Config.class);
            if ("YOUR_DISCORD_TOKEN_HERE".equals(config.getDiscordBotToken()) ||
                "YOUR_GEMINI_API_KEY_HERE".equals(config.getGeminiApiKey())) {
                System.err.println("Please fill in your tokens in bot_config.json and restart the bot.");
                System.exit(1);
            }
            return config;
        } catch (IOException e) {
            System.err.println("Error loading config file: " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            System.err.println("Error saving config file: " + e.getMessage());
        }
    }

    private static void createTemplate() {
        Config template = new Config();
        template.discordBotToken = "YOUR_DISCORD_TOKEN_HERE";
        template.geminiApiKey = "YOUR_GEMINI_API_KEY_HERE";
        template.guilds = Collections.emptyMap();
        template.defaultCategories = List.of(
            "[World Generation]", "[Player Data]", "[KubeJS Scripting]",
            "[Mod Conflict]", "[Performance/Tick Lag]", "[Unknown]"
        );
        template.forumChannelId = 1405938109845082265L;
        template.autoAnalysisChannelId = 1196656884413906984L;

        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(template, writer);
        } catch (IOException e) {
            System.err.println("Error creating config template: " + e.getMessage());
        }
    }

    public String getDiscordBotToken() {
        return discordBotToken;
    }

    public String getGeminiApiKey() {
        return geminiApiKey;
    }

    public Map<String, GuildConfig> getGuilds() {
        return guilds;
    }

    public List<String> getDefaultCategories() {
        return defaultCategories;
    }

    public long getForumChannelId() {
        return forumChannelId;
    }

    public long getAutoAnalysisChannelId() {
        return autoAnalysisChannelId;
    }

    public static class GuildConfig {
        @SerializedName("expert_roles")
        private List<Long> expertRoles;

        public List<Long> getExpertRoles() {
            return expertRoles;
        }
    }
}