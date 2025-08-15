package com.crashymccrashface.crashybought;

import com.crashymccrashface.crashybought.ui.AnalysisPagination;
import com.crashymccrashface.crashybought.ui.ConfirmationView;
import com.google.gson.Gson;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LogAnalyzer extends ListenerAdapter {

    private final Config config;
    private final GeminiAnalysis geminiAnalysis;
    private final RedisManager redisManager;
    private final OkHttpClient httpClient;
    private long lastAutoAnalysisTime = 0;

    private static final Pattern MCLOGS_PATTERN = Pattern.compile("mclo\\.gs/([a-zA-Z0-9]+)");

    public LogAnalyzer(Config config, GeminiAnalysis geminiAnalysis, RedisManager redisManager) {
        this.config = config;
        this.geminiAnalysis = geminiAnalysis;
        this.redisManager = redisManager;
        this.httpClient = new OkHttpClient();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild()) {
            return;
        }

        // Auto-analysis Channel Logic
        if (event.getChannel().getIdLong() == config.getAutoAnalysisChannelId()) {
            if (System.currentTimeMillis() - lastAutoAnalysisTime < 300_000) { // 5 minute cooldown
                return;
            }

            String logId = null;
            if (!event.getMessage().getEmbeds().isEmpty()) {
                for (MessageEmbed embed : event.getMessage().getEmbeds()) {
                    logId = getMclogsIdFromEmbed(embed);
                    if (logId != null) break;
                }
            } else {
                logId = getMclogsIdFromContent(event.getMessage().getContentRaw());
            }

            if (logId != null) {
                lastAutoAnalysisTime = System.currentTimeMillis();
                System.out.println("Auto-detected log " + logId + " in channel " + event.getChannel().getId());
                final String finalLogId = logId;
                event.getMessage().createThreadChannel("Analysis for " + logId).queue(thread -> {
                    startAnalysisPipeline(thread, event.getMessage(), finalLogId, event.getMember());
                });
            }
            return;
        }

        // Expert Role Confirmation Logic
        List<Long> expertRoleIds = config.getGuilds()
                                       .getOrDefault(event.getGuild().getId(), new Config.GuildConfig())
                                       .getExpertRoles();
        if (expertRoleIds == null || expertRoleIds.isEmpty()) return;

        boolean isExpert = event.getMember().getRoles().stream()
                                .anyMatch(role -> expertRoleIds.contains(role.getIdLong()));
        if (!isExpert) return;

        String logId = getMclogsIdFromContent(event.getMessage().getContentRaw());
        if (logId == null) return;

        System.out.println("Detected mclogs link from authorized user " + event.getAuthor().getName() + " (ID: " + event.getAuthor().getId() + "). Message ID: " + event.getMessageId());
        
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("INSERT OR IGNORE INTO analyses (source_message_id, status) VALUES (?, ?)")) {
            pstmt.setLong(1, event.getMessageIdLong());
            pstmt.setString(2, "pending");
            pstmt.executeUpdate();

            try (PreparedStatement selectStmt = conn.prepareStatement("SELECT status FROM analyses WHERE source_message_id = ?")) {
                selectStmt.setLong(1, event.getMessageIdLong());
                try(ResultSet rs = selectStmt.executeQuery()) {
                    if (rs.next()) {
                        String status = rs.getString("status");
                        if (!"pending".equals(status)) {
                            System.out.println("Ignoring already processed message ID: " + event.getMessageId());
                            return;
                        }
                    }
                }
            }

        } catch (SQLException e) {
            System.err.println("Database error during confirmation: " + e.getMessage());
            return;
        }
        
        ConfirmationView view = new ConfirmationView(event.getMember(), logId, event.getMessageIdLong(), this);
        event.getMessage().reply("I've detected a mclo.gs link. Would you like me to analyze it for you?")
             .mentionRepliedUser(false)
             .setActionRow(view.getButtons())
             .queue(view::setMessage);
    }

    private String getMclogsIdFromContent(String content) {
        Matcher matcher = MCLOGS_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String getMclogsIdFromEmbed(MessageEmbed embed) {
        StringBuilder contentToSearch = new StringBuilder();
        if (embed.getDescription() != null) {
            contentToSearch.append(embed.getDescription());
        }
        for (MessageEmbed.Field field : embed.getFields()) {
            contentToSearch.append(field.getValue());
        }
        return getMclogsIdFromContent(contentToSearch.toString());
    }

    public String fetchRawLog(String logId) throws IOException {
        Request request = new Request.Builder()
                .url("https://api.mclo.gs/1/raw/" + logId)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            return Objects.requireNonNull(response.body()).string();
        }
    }

    private boolean isCrashReport(String logContent) {
        if (logContent == null || logContent.isEmpty()) {
            return false;
        }
        return logContent.lines().limit(10).anyMatch(line -> line.toLowerCase().contains("crash"));
    }

    private List<MessageEmbed> createPaginatedEmbeds(String causesText, String fixesText, List<String> categories, List<String> history) {
        List<MessageEmbed> embeds = new ArrayList<>();
        String categoryStr = String.join(", ", categories);

        // --- Causes Embed ---
        EmbedBuilder embedCauses = new EmbedBuilder()
                .setTitle("Potential Causes")
                .setColor(0xFF_A5_00); // Orange

        List<String> causeChunks = geminiAnalysis.chunkLongText(causesText, 4096);
        embedCauses.setDescription(causeChunks.get(0));
        if (causeChunks.size() > 1) {
            for (int i = 1; i < causeChunks.size(); i++) {
                embedCauses.addField("Potential Causes (Cont. " + i + ")", causeChunks.get(i), false);
            }
        }
        embeds.add(embedCauses.build());

        // --- Fixes Embeds (One per avenue) ---
        String[] fixAvenues = fixesText.split("~##~");
        if (fixesText.isBlank() || fixAvenues.length == 0) {
            EmbedBuilder embedFixes = new EmbedBuilder()
                    .setTitle("Possible Fixes")
                    .setDescription("No specific fixes were identified.")
                    .setColor(0x00_FF_00); // Green
            embeds.add(embedFixes.build());
        } else {
            for (int i = 0; i < fixAvenues.length; i++) {
                String avenueContent = fixAvenues[i].strip();
                if (avenueContent.isEmpty()) continue;
                EmbedBuilder embedFix = new EmbedBuilder()
                        .setTitle("Possible Fix (Avenue " + (i + 1) + ")")
                        .setDescription(avenueContent)
                        .setColor(0x00_FF_00); // Green
                embeds.add(embedFix.build());
            }
        }

        // --- History Embed ---
        if (history != null && !history.isEmpty()) {
            // This is a placeholder as history implementation requires more database interaction
            EmbedBuilder embedHistory = new EmbedBuilder()
                    .setTitle("Crash History: " + categoryStr)
                    .setDescription("History functionality is under development.")
                    .setColor(0x00_00_FF); // Blue
            embeds.add(embedHistory.build());
        }

        for (int i = 0; i < embeds.size(); i++) {
            EmbedBuilder builder = new EmbedBuilder(embeds.get(i));
            builder.setFooter(String.format("Page %d/%d | Categories: %s | Analysis by Gemini 2.5 Pro",
                                            i + 1, embeds.size(), categoryStr));
            embeds.set(i, builder.build());
        }

        return embeds;
    }

    public void startAnalysisPipeline(MessageChannel replyTarget, Message sourceMessage, String logId, net.dv8tion.jda.api.entities.Member author) {
        final List<String> logLines = new ArrayList<>();
        logLines.add("✅ Acknowledged. Fetching content for log `" + logId + "`...");

        replyTarget.sendMessage(String.join("\n", logLines)).queue(statusMessage -> {
            long startTime = System.currentTimeMillis();
            String rawLog;
            try {
                rawLog = fetchRawLog(logId);
                long fetchDuration = System.currentTimeMillis() - startTime;
                logLines.set(logLines.size() - 1, "✅ Fetched log content... (took " + (fetchDuration / 1000.0) + "s)");
                statusMessage.editMessage(String.join("\n", logLines)).queue();
            } catch (IOException e) {
                logLines.add("❌ Failed to fetch log `" + logId + "`. Not found (private or expired).");
                statusMessage.editMessage(String.join("\n", logLines)).queue();
                return;
            }

            if (!isCrashReport(rawLog)) {
                statusMessage.editMessage("ℹ️ Log `" + logId + "` is not a crash report. Analysis skipped.").queue();
                return;
            }
            
            final String finalRawLog = rawLog;

            startTime = System.currentTimeMillis();
            logLines.add("<a:thinking:1405933688226840660> Categorizing crash...");
            statusMessage.editMessage(String.join("\n", logLines)).queue();

            List<String> existingCategories = new ArrayList<>();
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement("SELECT name FROM categories");
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    existingCategories.add(rs.getString("name"));
                }
            } catch (SQLException e) {
                System.err.println("Failed to fetch categories: " + e.getMessage());
            }

            final List<String> categories = geminiAnalysis.analyzeLogForCategory(rawLog, existingCategories);
            long categoryDuration = System.currentTimeMillis() - startTime;
            logLines.set(logLines.size() - 1, "🏷️ Categorized as `" + String.join(", ", categories) + "`... (took " + (categoryDuration / 1000.0) + "s)");
            statusMessage.editMessage(String.join("\n", logLines)).queue();
            
            // --- Analyze for Causes ---
            startTime = System.currentTimeMillis();
            logLines.add("<a:thinking:1405933688226840660> Analyzing for potential causes...");
            statusMessage.editMessage(String.join("\n", logLines)).queue();

            String causesResult = geminiAnalysis.analyzeLogForCauses(rawLog);
            long causesDuration = System.currentTimeMillis() - startTime;
            logLines.set(logLines.size() - 1, "🧠 Analyzed for potential causes... (took " + (causesDuration / 1000.0) + "s)");
            statusMessage.editMessage(String.join("\n", logLines)).queue();

            if (causesResult.startsWith("Error:")) {
                statusMessage.editMessage("❌ " + causesResult).queue();
                return;
            }

            // --- Analyze for Fixes ---
            startTime = System.currentTimeMillis();
            logLines.add("<a:thinking:1405933688226840660> Analyzing for possible fixes...");
            statusMessage.editMessage(String.join("\n", logLines)).queue();

            String fixesResult = geminiAnalysis.analyzeLogForFixes(rawLog, causesResult);
            long fixesDuration = System.currentTimeMillis() - startTime;
            logLines.set(logLines.size() - 1, "🧠 Analyzed for possible fixes... (took " + (fixesDuration / 1000.0) + "s)");
            statusMessage.editMessage(String.join("\n", logLines)).queue();

            if (fixesResult.startsWith("Error:")) {
                statusMessage.editMessage("❌ " + fixesResult).queue();
                return;
            }
            
            // --- Search for History ---
            logLines.add("🔍 Searching for related crash reports...");
            statusMessage.editMessage(String.join("\n", logLines)).queue();
            List<String> history = Collections.emptyList(); // Placeholder
            logLines.set(logLines.size() - 1, "🔍 Found " + history.size() + " related crash report(s).");
            statusMessage.editMessage(String.join("\n", logLines)).queue();

            // --- Post Results ---
            logLines.add("📄 Posting results!");
            statusMessage.editMessage(String.join("\n", logLines)).queue();
            List<MessageEmbed> embeds = createPaginatedEmbeds(causesResult, fixesResult, categories, history);
            statusMessage.editMessageEmbeds(embeds.get(0))
                         .setComponents(AnalysisPagination.createComponents(statusMessage.getIdLong(), 0, embeds.size()))
                         .queue();

            // --- Post to Forum Channel ---
            if (config.getForumChannelId() != 0) {
                ForumChannel forumChannel = statusMessage.getJDA().getForumChannelById(config.getForumChannelId());
                if (forumChannel != null) {
                    String postTitle = String.join(" ", categories) + " - mclo.gs/" + logId;
                    if (postTitle.length() > 100) {
                        postTitle = postTitle.substring(0, 97) + "...";
                    }
                    MessageCreateBuilder messageBuilder = new MessageCreateBuilder()
                        .setContent("Analysis for `mclo.gs/" + logId + "`")
                        .setEmbeds(embeds);
                    forumChannel.createForumPost(postTitle, messageBuilder.build())
                                .setComponents(new ArrayList<>()) // Placeholder for ForumPostControls
                                .queue(forumPost -> {
                                    System.out.println("Created forum post " + forumPost.getThreadChannel().getId() + " for analysis.");
                                    // TODO: Add ForumPostControls view
                                });
                } else {
                    System.err.println("Forum channel with ID " + config.getForumChannelId() + " not found.");
                }
            }
            
            // --- Save to Database ---
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(
                     "UPDATE analyses SET message_id = ?, author_id = ?, log_id = ?, embeds_json = ?, current_page = ?, categories_json = ?, follow_up_count = ?, forum_post_id = ?, status = 'complete' WHERE source_message_id = ?")) {

                pstmt.setLong(1, statusMessage.getIdLong());
                pstmt.setLong(2, author.getIdLong());
                pstmt.setString(3, logId);
                pstmt.setString(4, new Gson().toJson(embeds.stream().map(MessageEmbed::toData).collect(Collectors.toList())));
                pstmt.setInt(5, 0);
                pstmt.setString(6, new Gson().toJson(categories));
                pstmt.setInt(7, 0);
                //pstmt.setLong(8, forum_post_id); // TODO
                pstmt.setString(9, "complete");
                pstmt.setLong(10, sourceMessage.getIdLong());
                pstmt.executeUpdate();

                // Add new categories to the database
                try (PreparedStatement catPstmt = conn.prepareStatement("INSERT OR IGNORE INTO categories (name) VALUES (?)")) {
                    for (String category : categories) {
                        catPstmt.setString(1, category);
                        catPstmt.addBatch();
                    }
                    catPstmt.executeBatch();
                }

                System.out.println("Saved analysis for message " + statusMessage.getId() + " to database.");

            } catch (SQLException e) {
                System.err.println("Failed to save analysis to database: " + e.getMessage());
            }
        });
    }

}
