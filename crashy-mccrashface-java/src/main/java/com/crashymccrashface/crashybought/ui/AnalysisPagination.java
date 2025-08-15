package com.crashymccrashface.crashybought.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class AnalysisPagination extends ListenerAdapter {

    public static List<ActionRow> createComponents(long messageId, int currentPage, int maxPages) {
        Button previousButton = Button.secondary("analysis:prev:" + messageId, "⬅️").withDisabled(currentPage == 0);
        Button nextButton = Button.secondary("analysis:next:" + messageId, "➡️").withDisabled(currentPage >= maxPages - 1);
        Button pageIndicator = Button.secondary("analysis:indicator:" + messageId, "Page " + (currentPage + 1) + "/" + maxPages).asDisabled();
        Button followUpButton = Button.primary("analysis:follow-up:" + messageId, "Ask a Follow-up");

        return List.of(
            ActionRow.of(previousButton, nextButton, pageIndicator),
            ActionRow.of(followUpButton)
        );
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split(":");
        if (!parts[0].equals("analysis")) return;

        event.deferEdit().queue();

        String action = parts[1];
        long messageId = Long.parseLong(parts[2]);

        try (Connection conn = com.crashymccrashface.crashybought.DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("SELECT embeds_json, current_page FROM analyses WHERE message_id = ?")) {
            pstmt.setLong(1, messageId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String embedsJson = rs.getString("embeds_json");
                    int currentPage = rs.getInt("current_page");

                    Gson gson = new Gson();
                    Type embedListType = new TypeToken<List<DataObject>>(){}.getType();
                    List<DataObject> embedData = gson.fromJson(embedsJson, embedListType);
                    List<MessageEmbed> embeds = embedData.stream().map(EmbedBuilder::fromData).map(EmbedBuilder::build).collect(java.util.stream.Collectors.toList());

                    int newPage = currentPage;
                    if (action.equals("prev") && currentPage > 0) {
                        newPage--;
                    } else if (action.equals("next") && currentPage < embeds.size() - 1) {
                        newPage++;
                    }

                    if (newPage != currentPage) {
                        try (PreparedStatement updatePstmt = conn.prepareStatement("UPDATE analyses SET current_page = ? WHERE message_id = ?")) {
                            updatePstmt.setInt(1, newPage);
                            updatePstmt.setLong(2, messageId);
                            updatePstmt.executeUpdate();
                        }
                    }

                    event.getHook().editMessageById(messageId, "")
                         .setEmbeds(embeds.get(newPage))
                         .setComponents(createComponents(messageId, newPage, embeds.size()))
                         .queue();
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error during pagination: " + e.getMessage());
        }
    }
}