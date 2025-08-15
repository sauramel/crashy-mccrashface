package com.crashymccrashface.crashybought;

import com.crashymccrashface.crashybought.ui.AnalysisPagination;
import com.crashymccrashface.crashybought.ui.ConfirmationView;
import com.crashymccrashface.crashybought.ui.FollowUpModal;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Bot {

    private final JDA jda;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public Bot(Config config, GeminiAnalysis geminiAnalysis, RedisManager redisManager) throws InterruptedException {
        jda = JDABuilder.createDefault(config.getDiscordBotToken())
                .enableIntents(
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT
                )
                .addEventListeners(
                        new LogAnalyzer(config, geminiAnalysis, redisManager),
                        new AdminSlashCommands(config),
                        new FollowUpModal(geminiAnalysis),
                        new AnalysisPagination(redisManager)
                        // Note: ConfirmationView is dynamically created and registered
                )
                .build()
                .awaitReady();

        AdminSlashCommands.registerCommands(jda);
        System.out.println("Logged in as " + jda.getSelfUser().getName() + " (" + jda.getSelfUser().getId() + ")");

        startPresenceUpdates();
    }

    private void startPresenceUpdates() {
        scheduler.scheduleAtFixedRate(this::updatePresence, 0, 5, TimeUnit.MINUTES);
    }

    private void updatePresence() {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM analyses WHERE status = 'complete'");
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                int count = rs.getInt(1);
                jda.getPresence().setActivity(Activity.watching(count + " Crashes analyzed"));
                System.out.println("Updated presence to " + count + " crashes analyzed.");
            }
        } catch (SQLException e) {
            System.err.println("Failed to update presence: " + e.getMessage());
        }
    }
}
