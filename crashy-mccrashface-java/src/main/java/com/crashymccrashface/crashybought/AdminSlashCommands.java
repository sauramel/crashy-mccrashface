package com.crashymccrashface.crashybought;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.List;
import java.util.stream.Collectors;

public class AdminSlashCommands extends ListenerAdapter {

    private final Config config;

    public AdminSlashCommands(Config config) {
        this.config = config;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "help":
                sendHelpEmbed(event);
                break;
            case "setrole":
                setRole(event);
                break;
            case "addrole":
                addRole(event);
                break;
            case "removerole":
                removeRole(event);
                break;
            case "listroles":
                listRoles(event);
                break;
        }
    }

    private void sendHelpEmbed(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Bot Help")
                .setDescription("This bot analyzes mclo.gs crash reports for expert modpack developers.")
                .setColor(0x00_00_FF) // Blue
                .addField("How it Works", "When a user with a configured 'expert role' posts a message containing an `mclo.gs` URL, the bot will automatically provide a detailed technical analysis.", false)
                .addField("Admin Commands", "`/setrole <role>` - Overwrites all expert roles with a single new one.\n`/addrole <role>` - Adds a role to the list of experts.\n`/removerole <role>` - Removes a role from the list.\n`/listroles` - Shows all current expert roles.", false)
                .setFooter("Requires Administrator permissions for config commands.");
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private void setRole(SlashCommandInteractionEvent event) {
        if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            event.reply("You do not have permission to use this command.").setEphemeral(true).queue();
            return;
        }

        var role = event.getOption("role").getAsRole();
        String guildId = event.getGuild().getId();

        config.getGuilds().computeIfAbsent(guildId, k -> new Config.GuildConfig());
        config.getGuilds().get(guildId).getExpertRoles().clear();
        config.getGuilds().get(guildId).getExpertRoles().add(role.getIdLong());
        config.save();

        event.reply("✅ Success! The expert role has been set to **" + role.getName() + "**.").setEphemeral(true).queue();
    }

    private void addRole(SlashCommandInteractionEvent event) {
        if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            event.reply("You do not have permission to use this command.").setEphemeral(true).queue();
            return;
        }

        var role = event.getOption("role").getAsRole();
        String guildId = event.getGuild().getId();

        config.getGuilds().computeIfAbsent(guildId, k -> new Config.GuildConfig());
        List<Long> expertRoles = config.getGuilds().get(guildId).getExpertRoles();
        if (expertRoles.contains(role.getIdLong())) {
            event.reply("⚠️ **" + role.getName() + "** is already an expert role.").setEphemeral(true).queue();
        } else {
            expertRoles.add(role.getIdLong());
            config.save();
            event.reply("✅ Success! **" + role.getName() + "** has been added.").setEphemeral(true).queue();
        }
    }

    private void removeRole(SlashCommandInteractionEvent event) {
        if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            event.reply("You do not have permission to use this command.").setEphemeral(true).queue();
            return;
        }

        var role = event.getOption("role").getAsRole();
        String guildId = event.getGuild().getId();

        List<Long> expertRoles = config.getGuilds().getOrDefault(guildId, new Config.GuildConfig()).getExpertRoles();
        if (expertRoles != null && expertRoles.remove(role.getIdLong())) {
            config.save();
            event.reply("✅ Success! **" + role.getName() + "** has been removed.").setEphemeral(true).queue();
        } else {
            event.reply("⚠️ **" + role.getName() + "** is not an expert role.").setEphemeral(true).queue();
        }
    }

    private void listRoles(SlashCommandInteractionEvent event) {
        if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            event.reply("You do not have permission to use this command.").setEphemeral(true).queue();
            return;
        }

        String guildId = event.getGuild().getId();
        List<Long> expertRoles = config.getGuilds().getOrDefault(guildId, new Config.GuildConfig()).getExpertRoles();

        if (expertRoles == null || expertRoles.isEmpty()) {
            event.reply("There are no expert roles configured for this server.").setEphemeral(true).queue();
        } else {
            String roleMentions = expertRoles.stream()
                                             .map(roleId -> "<@&" + roleId + ">")
                                             .collect(Collectors.joining(", "));
            event.reply("Current expert roles: " + roleMentions).setEphemeral(true).queue();
        }
    }

    public static void registerCommands(net.dv8tion.jda.api.JDA jda) {
        jda.updateCommands().addCommands(
            Commands.slash("help", "Shows the bot's help information."),
            Commands.slash("setrole", "Sets the single expert role for this server.")
                .addOption(OptionType.ROLE, "role", "The role to set as the expert role.", true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
            Commands.slash("addrole", "Adds an expert role for this server.")
                .addOption(OptionType.ROLE, "role", "The expert role to add.", true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
            Commands.slash("removerole", "Removes an expert role for this server.")
                .addOption(OptionType.ROLE, "role", "The expert role to remove.", true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
            Commands.slash("listroles", "Lists the current expert roles for this server.")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
        ).queue();
    }
}