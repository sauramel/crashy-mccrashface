package com.crashymccrashface.crashybought;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class Bot {

    private final JDA jda;

    public Bot(Config config, GeminiAnalysis geminiAnalysis) throws InterruptedException {
        jda = JDABuilder.createDefault(config.getDiscordBotToken())
                .enableIntents(
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT
                )
                // Add event listeners here
                .build()
                .awaitReady();

        System.out.println("Logged in as " + jda.getSelfUser().getName() + " (" + jda.getSelfUser().getId() + ")");
    }
}