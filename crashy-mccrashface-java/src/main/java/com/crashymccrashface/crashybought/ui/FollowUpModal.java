package com.crashymccrashface.crashybought.ui;

import com.crashymccrashface.crashybought.DatabaseManager;
import com.crashymccrashface.crashybought.GeminiAnalysis;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

public class FollowUpModal extends ListenerAdapter {

    private final GeminiAnalysis geminiAnalysis;

    public FollowUpModal(GeminiAnalysis geminiAnalysis) {
        this.geminiAnalysis = geminiAnalysis;
    }

    public static Modal create(long messageId) {
        TextInput questionInput = TextInput.create("question", "Your Question", TextInputStyle.PARAGRAPH)
                .setPlaceholder("e.g., Can you elaborate on the KubeJS script issue? Which specific function is causing the problem?")
                .setRequired(true)
                .setMaxLength(1000)
                .build();

        return Modal.create("follow-up:" + messageId, "Ask a Follow-up Question")
                .addComponents(ActionRow.of(questionInput))
                .build();
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("follow-up:")) return;

        event.deferReply(true).queue();
        long messageId = Long.parseLong(event.getModalId().split(":")[1]);
        String question = event.getValue("question").getAsString();

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("SELECT log_id, embeds_json FROM analyses WHERE message_id = ?")) {
            pstmt.setLong(1, messageId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String logId = rs.getString("log_id");
                    String embedsJson = rs.getString("embeds_json");

                    // This is a simplified way to get the raw log; in a real app, you might need to fetch it again
                    // For now, we assume we can't get the raw log back, so we'll pass an empty string.
                    String rawLog = ""; // Placeholder

                    Gson gson = new Gson();
                    Type embedListType = new TypeToken<List<DataObject>>(){}.getType();
                    List<DataObject> embedData = gson.fromJson(embedsJson, embedListType);
                    String previousAnalysis = embedData.stream()
                                                       .map(data -> data.getString("description", ""))
                                                       .collect(Collectors.joining("\n"));

                    String followUpResponse = geminiAnalysis.analyzeFollowUp(rawLog, previousAnalysis, question);

                    event.getChannel().asTextChannel().createThreadChannel("Follow-up for log " + logId, messageId)
                         .queue(thread -> {
                             thread.sendMessage("**Follow-up from " + event.getUser().getAsMention() + ":**\n> " + question).queue();
                             List<String> responseChunks = geminiAnalysis.chunkLongText(followUpResponse, 2000);
                             for (String chunk : responseChunks) {
                                 thread.sendMessage(chunk).queue();
                             }
                         });

                    try (PreparedStatement updatePstmt = conn.prepareStatement("UPDATE analyses SET follow_up_count = follow_up_count + 1 WHERE message_id = ?")) {
                        updatePstmt.setLong(1, messageId);
                        updatePstmt.executeUpdate();
                    }

                    event.getHook().sendMessage("Your question has been answered in the thread.").queue();
                } else {
                    event.getHook().sendMessage("Could not find original analysis data.").queue();
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error during follow-up: " + e.getMessage());
            event.getHook().sendMessage("An error occurred while processing your request.").queue();
        }
    }
}