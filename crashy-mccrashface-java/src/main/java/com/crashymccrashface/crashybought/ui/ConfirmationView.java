package com.crashymccrashface.crashybought.ui;

import com.crashymccrashface.crashybought.LogAnalyzer;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ConfirmationView extends ListenerAdapter {

    private final Member author;
    private final String logId;
    private final long sourceMessageId;
    private final LogAnalyzer cog;
    private Message message;

    public ConfirmationView(Member author, String logId, long sourceMessageId, LogAnalyzer cog) {
        this.author = author;
        this.logId = logId;
        this.sourceMessageId = sourceMessageId;
        this.cog = cog;
    }

    public List<Button> getButtons() {
        return List.of(
                Button.success("confirm-analysis:" + sourceMessageId, "Analyze"),
                Button.danger("cancel-analysis:" + sourceMessageId, "Cancel")
        );
    }

    public void setMessage(Message message) {
        this.message = message;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split(":");
        if (!parts[0].equals("confirm-analysis") && !parts[0].equals("cancel-analysis")) {
            return;
        }

        if (event.getUser().getIdLong() != author.getIdLong()) {
            event.reply("You are not authorized to interact with this.").setEphemeral(true).queue();
            return;
        }

        if (parts[0].equals("confirm-analysis")) {
            event.deferEdit().queue();
            cog.startAnalysisPipeline(message.getChannel(), message, logId, author);
        } else {
            event.editMessage("Analysis for log `" + logId + "` canceled.").setComponents().queue();
        }
    }
}
