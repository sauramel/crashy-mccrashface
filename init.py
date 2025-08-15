import discord
import os
import re
import aiosqlite
import aiohttp
import google.generativeai as genai
import logging
import json
import sys
import time
import asyncio
from discord.ext import commands, tasks
from discord import app_commands

# --- Logging & Config Setup ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
CONFIG_FILE = 'bot_config.json'
DB_FILE = 'analysis_data.db'

def load_config():
    """Loads the configuration from a JSON file, creating a template if it doesn't exist."""
    if not os.path.exists(CONFIG_FILE):
        logging.warning(f"{CONFIG_FILE} not found. Creating a template.")
        template_config = {
            "DISCORD_BOT_TOKEN": "YOUR_DISCORD_TOKEN_HERE",
            "GEMINI_API_KEY": "YOUR_GEMINI_API_KEY_HERE",
            "guilds": {},
            "default_categories": [
                "[World Generation]", "[Player Data]", "[KubeJS Scripting]", 
                "[Mod Conflict]", "[Performance/Tick Lag]", "[Unknown]"
            ],
            "forum_channel_id": 1405938109845082265,
            "auto_analysis_channel_id": 1196656884413906984
        }
        with open(CONFIG_FILE, 'w') as f:
            json.dump(template_config, f, indent=4)
        logging.error("Please fill in your tokens in bot_config.json and restart the bot.")
        sys.exit(1)
    with open(CONFIG_FILE, 'r') as f:
        config = json.load(f)
    if config.get("DISCORD_BOT_TOKEN") == "YOUR_DISCORD_TOKEN_HERE" or \
       config.get("GEMINI_API_KEY") == "YOUR_GEMINI_API_KEY_HERE":
        logging.error("Please fill in your tokens in bot_config.json and restart the bot.")
        sys.exit(1)
    return config

def save_config(config_data):
    """Saves the configuration to a JSON file."""
    with open(CONFIG_FILE, 'w') as f:
        json.dump(config_data, f, indent=4)

config = load_config()
DISCORD_BOT_TOKEN = config.get("DISCORD_BOT_TOKEN")
GEMINI_API_KEY = config.get("GEMINI_API_KEY")
FORUM_CHANNEL_ID = config.get("forum_channel_id")
AUTO_ANALYSIS_CHANNEL_ID = config.get("auto_analysis_channel_id")

def get_expert_roles(guild_id: int) -> list[int]:
    return config.get("guilds", {}).get(str(guild_id), {}).get('expert_roles', [])

# --- Bot Setup ---
intents = discord.Intents.default()
intents.message_content = True
intents.members = True
intents.guilds = True
bot = commands.Bot(command_prefix="!", intents=intents)

# --- Gemini AI Analysis (Refined Prompts) ---
try:
    if GEMINI_API_KEY:
        genai.configure(api_key=GEMINI_API_KEY)
        analysis_model = genai.GenerativeModel('gemini-2.5-pro')
        category_model = genai.GenerativeModel('gemini-2.5-flash')
    else:
        analysis_model = None
        category_model = None
except Exception as e:
    logging.error(f"Failed to configure Gemini AI: {e}")
    analysis_model = None
    category_model = None

async def chunk_long_text(text_to_chunk: str, limit: int) -> list[str]:
    """Uses a faster AI model to split long text into chunks without rephrasing."""
    if len(text_to_chunk) <= limit:
        return [text_to_chunk]
    
    if not category_model:
        logging.warning("Chunking model not available, falling back to basic split.")
        return [text_to_chunk[i:i + limit] for i in range(0, len(text_to_chunk), limit)]

    prompt = f"""
    The following text is too long. You must break it into smaller pieces.
    Insert the special delimiter `~##~` between sections to split the text.
    Each piece MUST be less than {limit - 50} characters.
    **CRITICAL:** Do not remove, reword, or change any of the original content. Only insert the `~##~` delimiter.

    **TEXT TO SPLIT:**
    ```
    {text_to_chunk}
    ```
    """
    try:
        response = await category_model.generate_content_async(prompt)
        return [chunk.strip() for chunk in response.text.split("~##~") if chunk.strip()]
    except Exception as e:
        logging.error(f"Gemini API error (chunking): {e}")
        return [text_to_chunk[i:i + limit] for i in range(0, len(text_to_chunk), limit)]

async def analyze_log_for_category(log_content: str, existing_categories: list[str]) -> list[str]:
    """Analyzes the log to assign one or more categories."""
    if not category_model: return ["[Unknown]"]
    
    existing_categories_str = ", ".join(existing_categories)
    prompt = f"""
    You are a log categorization engine. Your task is to assign relevant categories to a Minecraft crash log.

    **Instructions:**
    1.  Review the list of existing categories: {existing_categories_str}
    2.  Analyze the crash log provided.
    3.  Choose one or more **existing categories** that accurately describe the crash.
    4.  If **no existing categories are a good fit**, create a new, concise category name enclosed in square brackets (e.g., `[Rendering Issue]`).
    5.  Return a comma-separated list of your chosen category names.

    **Example Responses:**
    - `[KubeJS Scripting], [Performance/Tick Lag]`
    - `[Mod Conflict]`
    - `[New Category Name]`

    **CRASH LOG:**
    ```
    {log_content}
    ```
    """
    try:
        response = await category_model.generate_content_async(prompt)
        categories = [cat.strip() for cat in response.text.strip().split(',') if cat.strip()]
        if categories and all("[" in cat and "]" in cat for cat in categories):
            return categories
        return ["[Unknown]"]
    except Exception as e:
        logging.error(f"Gemini API error (category): {e}")
        return ["[Unknown]"]

async def analyze_log_for_causes(log_content: str) -> str:
    """Analyzes the log to determine potential causes."""
    if not analysis_model: return "Error: AI model not configured."
    prompt = f"""
    You are an expert Minecraft modded server diagnostician.
    Analyze the **entire crash report and its full stack trace** to determine the most likely technical reasons for the crash.
    Provide a **detailed, in-depth technical breakdown**. This section is for **diagnosis only**. Do not suggest solutions or fixes.
    Explain the chain of events leading to the error, referencing specific mod and class names.
    
    **CRITICAL RULE:** The mod list is **IMMUTABLE**. Do not suggest removing, adding, or updating mods.

    **Format your response as a detailed, multi-point bulleted list.**
    - Example: **Server Thread Deadlock:** A scheduled KubeJS script (`kubejs/server_scripts/some_script.js`) is attempting to access block properties in an unloaded or partially loaded chunk. The main server thread is waiting for this operation to complete, but the operation cannot complete because the thread is locked, resulting in a `ServerHangWatchdog` crash.
    - Example: **Invalid Worldgen Configuration:** The mod `some_mod` is attempting to generate a structure, but its configuration file (`config/some_mod-server.toml`) contains an invalid value for `maxStructuresPerRegion`. This causes a `NullPointerException` when the world generator attempts to read this value during chunk creation.

    **CRASH LOG:**
    ```
    {log_content}
    ```
    """
    try:
        response = await analysis_model.generate_content_async(prompt)
        return response.text or "Error: The AI model returned an empty analysis for causes."
    except Exception as e:
        logging.error(f"Gemini API error (causes): {e}")
        return f"Error analyzing causes: `{e}`"

async def analyze_log_for_fixes(log_content: str, causes_analysis: str) -> str:
    """Analyzes the log to determine possible fixes, using the causes for context."""
    if not analysis_model: return "Error: AI model not configured."
    prompt = f"""
    You are an expert Minecraft modded server diagnostician.
    An initial analysis has already determined the potential causes of a crash. Your task is to provide concrete, actionable solutions based on the **entire original crash log** and the provided causes.
    Explore **multiple distinct avenues** for fixes. Aim for a baseline of 2-3 solutions, but provide up to 4 if different valid approaches exist (e.g., a config change, a script change, a data fix).
    For each solution, provide the file, the action, and a detailed reason.
    **If you suggest multiple distinct types of fixes, separate them with the `~##~` delimiter.**

    **CRITICAL RULES:**
    1. The mod list is **ABSOLUTELY IMMUTABLE**. You **MUST NOT EVER** suggest removing, adding, or updating mods. Do not suggest installing new "helper" mods.
    2. **DO NOT** suggest generic fixes like changing `max-tick-time`. Focus only on specific mod configs, scripts, or data issues directly related to the crash.
    3. **DO NOT** repeat the causes; focus only on providing actionable solutions.

    **PREVIOUSLY IDENTIFIED CAUSES:**
    ```
    {causes_analysis}
    ```

    **ORIGINAL CRASH LOG (FOR FULL CONTEXT):**
    ```
    {log_content}
    ```
    """
    try:
        response = await analysis_model.generate_content_async(prompt)
        return response.text or "Error: The AI model returned an empty analysis for fixes."
    except Exception as e:
        logging.error(f"Gemini API error (fixes): {e}")
        return f"Error analyzing fixes: `{e}`"

async def analyze_follow_up(log_content: str, previous_analysis: str, user_question: str) -> str:
    """Handles follow-up questions from users."""
    if not analysis_model: return "Error: AI model not configured."
    prompt = f"""
    You are an expert Minecraft modded server diagnostician.
    A user has a follow-up question about a crash log you previously analyzed.
    Provide a **comprehensive and detailed technical answer** to their question based on the provided context.

    **CRITICAL RULES:**
    1. The mod list is **IMMUTABLE**. Do not suggest removing, adding, or updating mods.
    2. **DO NOT** suggest generic fixes like changing the `max-tick-time`.

    **CONTEXT - ORIGINAL CRASH LOG:**
    ```
    {log_content}
    ```

    **CONTEXT - PREVIOUS ANALYSIS:**
    ```
    {previous_analysis}
    ```

    **USER'S FOLLOW-UP QUESTION:**
    {user_question}
    """
    try:
        response = await analysis_model.generate_content_async(prompt)
        return response.text or "Error: The AI model returned an empty response for the follow-up."
    except Exception as e:
        logging.error(f"Gemini API error (follow-up): {e}")
        return f"Error analyzing follow-up: `{e}`"

# --- UI Modals & Views ---

class FollowUpModal(discord.ui.Modal, title="Ask a Follow-up Question"):
    question = discord.ui.TextInput(
        label="Your Question",
        style=discord.TextStyle.long,
        placeholder="e.g., Can you elaborate on the KubeJS script issue? Which specific function is causing the problem?",
        required=True,
        max_length=1000,
    )

    def __init__(self, analysis_message: discord.Message):
        super().__init__()
        self.analysis_message = analysis_message

    async def on_submit(self, interaction: discord.Interaction):
        await interaction.response.defer(thinking=True, ephemeral=True)
        
        thread = self.analysis_message.thread
        if not thread:
            try:
                thread = await self.analysis_message.create_thread(name=f"Follow-up for log analysis")
                logging.info(f"Created new thread for follow-up on message {self.analysis_message.id}")
            except Exception as e:
                logging.error(f"Failed to create follow-up thread for message {self.analysis_message.id}: {e}")
                await interaction.followup.send("Failed to create a follow-up thread.", ephemeral=True)
                return

        async with aiosqlite.connect(DB_FILE) as db:
            async with db.execute("SELECT log_id, embeds_json, forum_post_id FROM analyses WHERE message_id = ?", (self.analysis_message.id,)) as cursor:
                row = await cursor.fetchone()
                if not row:
                    await interaction.followup.send("Could not find original analysis data.", ephemeral=True)
                    return
                log_id, embeds_json, forum_post_id = row

        log_analyzer_cog = bot.get_cog("Log Analyzer")
        raw_log, _ = await log_analyzer_cog.fetch_raw_log(log_id)
        if not raw_log:
            await interaction.followup.send("Could not fetch the original log file for context.", ephemeral=True)
            return
            
        previous_analysis = "\n".join([embed['description'] for embed in json.loads(embeds_json)])
        
        follow_up_response = await analyze_follow_up(raw_log, previous_analysis, self.question.value)
        
        # Post to original thread
        try:
            await thread.send(f"**Follow-up from {interaction.user.mention}:**\n> {self.question.value}")
            response_chunks = [follow_up_response[i:i + 2000] for i in range(0, len(follow_up_response), 2000)]
            for chunk in response_chunks:
                await thread.send(chunk)
            logging.info(f"Posted follow-up to thread {thread.id}")
        except Exception as e:
            logging.error(f"Failed to post follow-up to thread {thread.id}: {e}")

        # Sync to forum post
        if forum_post_id:
            try:
                forum_thread = await bot.fetch_channel(forum_post_id)
                await forum_thread.send(f"**Follow-up from {interaction.user.mention}:**\n> {self.question.value}")
                for chunk in response_chunks:
                    await forum_thread.send(chunk)
                logging.info(f"Synced follow-up to forum post {forum_post_id}")
            except (discord.NotFound, discord.Forbidden):
                logging.error(f"Could not find or post to forum thread {forum_post_id}.")

        async with aiosqlite.connect(DB_FILE) as db:
            await db.execute("UPDATE analyses SET follow_up_count = follow_up_count + 1 WHERE message_id = ?", (self.analysis_message.id,))
            await db.commit()

        await interaction.followup.send("Your question has been answered in the thread.", ephemeral=True)

class RenamePostModal(discord.ui.Modal, title="Rename Forum Post"):
    new_title = discord.ui.TextInput(
        label="New Post Title",
        required=True,
        max_length=100
    )

    def __init__(self, forum_post: discord.Thread):
        super().__init__()
        self.forum_post = forum_post
        self.new_title.default = forum_post.name

    async def on_submit(self, interaction: discord.Interaction):
        try:
            await self.forum_post.edit(name=self.new_title.value)
            await interaction.response.send_message("Post title updated successfully.", ephemeral=True)
            logging.info(f"Staff member {interaction.user} renamed forum post {self.forum_post.id} to '{self.new_title.value}'")
        except Exception as e:
            await interaction.response.send_message(f"Failed to rename post: {e}", ephemeral=True)
            logging.error(f"Failed to rename forum post {self.forum_post.id}: {e}")

class ForumPostControls(discord.ui.View):
    """A persistent view for staff controls on a forum post."""
    def __init__(self):
        super().__init__(timeout=None)
        self.rename_button.custom_id = "forum_rename"

    @discord.ui.button(label="Rename Post", style=discord.ButtonStyle.secondary, emoji="✏️")
    async def rename_button(self, interaction: discord.Interaction, button: discord.ui.Button):
        if not interaction.user.guild_permissions.manage_channels:
            await interaction.response.send_message("You do not have permission to rename this post.", ephemeral=True)
            return
        
        if not isinstance(interaction.channel, discord.Thread):
            await interaction.response.send_message("This command can only be used in a forum post.", ephemeral=True)
            return

        await interaction.response.send_modal(RenamePostModal(forum_post=interaction.channel))

class AnalysisPagination(discord.ui.View):
    """A persistent UI View to handle pagination and follow-ups, locked to the original author."""
    def __init__(self):
        super().__init__(timeout=None)
        self.previous_button.custom_id = "analysis_prev"
        self.next_button.custom_id = "analysis_next"
        self.page_indicator.custom_id = "analysis_indicator"
        self.follow_up_button.custom_id = "analysis_follow_up"

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        expert_roles = get_expert_roles(interaction.guild.id)
        user_roles = {role.id for role in interaction.user.roles}
        if interaction.user.guild_permissions.administrator or any(role_id in user_roles for role_id in expert_roles):
            return True
        await interaction.response.send_message("Only expert members or staff can interact with this analysis.", ephemeral=True)
        return False

    def _update_buttons(self, page: int, max_pages: int):
        self.previous_button.disabled = page == 0
        self.next_button.disabled = page == max_pages - 1
        self.page_indicator.label = f"Page {page + 1}/{max_pages}"

    @discord.ui.button(label="⬅️", style=discord.ButtonStyle.secondary)
    async def previous_button(self, interaction: discord.Interaction, button: discord.ui.Button):
        async with aiosqlite.connect(DB_FILE) as db:
            async with db.execute("SELECT embeds_json, current_page FROM analyses WHERE message_id = ?", (interaction.message.id,)) as cursor:
                row = await cursor.fetchone()
                if not row:
                    await interaction.response.edit_message(content="Error: Could not find analysis data.", view=None)
                    return
                embeds_data, current_page = json.loads(row[0]), row[1]
                if current_page > 0:
                    current_page -= 1
                    embed = discord.Embed.from_dict(embeds_data[current_page])
                    self._update_buttons(current_page, len(embeds_data))
                    await interaction.response.edit_message(embed=embed, view=self)
                    await db.execute("UPDATE analyses SET current_page = ? WHERE message_id = ?", (current_page, interaction.message.id))
                    await db.commit()

    @discord.ui.button(label="➡️", style=discord.ButtonStyle.secondary)
    async def next_button(self, interaction: discord.Interaction, button: discord.ui.Button):
        async with aiosqlite.connect(DB_FILE) as db:
            async with db.execute("SELECT embeds_json, current_page FROM analyses WHERE message_id = ?", (interaction.message.id,)) as cursor:
                row = await cursor.fetchone()
                if not row:
                    await interaction.response.edit_message(content="Error: Could not find analysis data.", view=None)
                    return
                embeds_data, current_page = json.loads(row[0]), row[1]
                if current_page < len(embeds_data) - 1:
                    current_page += 1
                    embed = discord.Embed.from_dict(embeds_data[current_page])
                    self._update_buttons(current_page, len(embeds_data))
                    await interaction.response.edit_message(embed=embed, view=self)
                    await db.execute("UPDATE analyses SET current_page = ? WHERE message_id = ?", (current_page, interaction.message.id))
                    await db.commit()

    @discord.ui.button(label="Page 1/1", style=discord.ButtonStyle.grey, disabled=True)
    async def page_indicator(self, interaction: discord.Interaction, button: discord.ui.Button): pass

    @discord.ui.button(label="Ask a Follow-up", style=discord.ButtonStyle.primary, row=2)
    async def follow_up_button(self, interaction: discord.Interaction, button: discord.ui.Button):
        async with aiosqlite.connect(DB_FILE) as db:
            async with db.execute("SELECT follow_up_count FROM analyses WHERE message_id = ?", (interaction.message.id,)) as cursor:
                row = await cursor.fetchone()
                if not row or row[0] is None:
                    await interaction.response.send_message("Error: Could not retrieve follow-up count.", ephemeral=True)
                    return
                
                count = row[0]
                if count >= 3:
                    await interaction.response.send_message("You have reached the maximum of 3 follow-up questions for this analysis.", ephemeral=True)
                    button.disabled = True
                    await interaction.message.edit(view=self)
                else:
                    await interaction.response.send_modal(FollowUpModal(analysis_message=interaction.message))

# --- Cogs ---

class LogAnalyzer(commands.Cog, name="Log Analyzer"):
    def __init__(self, bot):
        self.bot = bot
        self.session = aiohttp.ClientSession()
        self.last_auto_analysis_time = 0

    def cog_unload(self):
        self.bot.loop.create_task(self.session.close())

    @staticmethod
    def get_mclogs_id_from_content(content: str) -> str | None:
        match = re.search(r"mclo\.gs/([a-zA-Z0-9]+)", content)
        return match.group(1) if match else None

    @staticmethod
    def get_mclogs_id_from_embed(embed: discord.Embed) -> str | None:
        content_to_search = ""
        if embed.description:
            content_to_search += embed.description
        for field in embed.fields:
            content_to_search += field.value
        return LogAnalyzer.get_mclogs_id_from_content(content_to_search)

    async def fetch_raw_log(self, log_id: str) -> tuple[str | None, int]:
        url = f"https://api.mclo.gs/1/raw/{log_id}"
        try:
            async with self.session.get(url) as response:
                if response.status == 200: return await response.text(), response.status
                return None, response.status
        except aiohttp.ClientError as e:
            logging.error(f"AIOHTTP client error fetching {log_id}: {e}")
            return None, -1

    @staticmethod
    def is_crash_report(log_content: str) -> bool:
        if not log_content: return False
        return any("crash" in line.lower() for line in log_content.splitlines()[:10])

    @staticmethod
    async def create_paginated_embeds(causes_text: str, fixes_text: str, categories: list[str], history: list) -> list[discord.Embed]:
        embeds = []
        category_str = ", ".join(categories)

        # --- Causes Embed ---
        embed_causes = discord.Embed(title="Potential Causes", color=discord.Color.orange())
        cause_chunks = await chunk_long_text(causes_text, 4096)
        embed_causes.description = cause_chunks[0]
        if len(cause_chunks) > 1:
            for i, chunk in enumerate(cause_chunks[1:]):
                embed_causes.add_field(name=f"Potential Causes (Cont. {i+1})", value=chunk, inline=False)
        embeds.append(embed_causes)
        
        # --- Fixes Embeds (One per avenue) ---
        fix_avenues = fixes_text.split("~##~")
        if not fixes_text.strip() or not fix_avenues:
            embed_fixes = discord.Embed(title="Possible Fixes", description="No specific fixes were identified.", color=discord.Color.green())
            embeds.append(embed_fixes)
        else:
            for i, avenue in enumerate(fix_avenues):
                avenue_content = avenue.strip()
                if not avenue_content: continue
                embed_fix = discord.Embed(title=f"Possible Fix (Avenue {i+1})", description=avenue_content, color=discord.Color.green())
                embeds.append(embed_fix)

        # --- History Embed ---
        if history:
            history_desc = ""
            for item in history:
                message_link = f"https://discord.com/channels/{item['guild_id']}/{item['channel_id']}/{item['message_id']}"
                first_cause = item['causes'].split('\n')[0].strip('- ').strip()
                if len(first_cause) > 150:
                    first_cause = first_cause[:147] + "..."
                history_desc += f"[`{item['log_id']}`]({message_link}) - {first_cause}\n"
            
            embed_history = discord.Embed(title=f"Crash History: {category_str}", description=history_desc, color=discord.Color.blue())
            embeds.append(embed_history)

        for i, embed in enumerate(embeds):
            embed.set_footer(text=f"Page {i+1}/{len(embeds)} | Categories: {category_str} | Analysis by Gemini 2.5 Pro")
        return embeds

    async def start_analysis_pipeline(self, reply_target: discord.abc.Messageable, source_message_id: int, log_id: str, author: discord.Member):
        """The full, reusable analysis pipeline."""
        log_url = f"https://mclo.gs/{log_id}"
        log_lines = []
        thinking_emoji = "<a:thinking:1405933688226840660>"
        
        is_thread = isinstance(reply_target, discord.Thread)
        if is_thread:
            status_message = await reply_target.send(f"Starting analysis for `mclo.gs/{log_id}`...")
        else:
            status_message = await reply_target.reply(f"Starting analysis for `mclo.gs/{log_id}`...", mention_author=False)

        async def update_status(new_line):
            log_lines.append(new_line)
            await status_message.edit(content="\n".join(log_lines))

        start_time = time.time()
        await update_status(f"✅ Acknowledged. Fetching content for log `{log_id}`...")
        
        raw_log, status = await self.fetch_raw_log(log_id)
        fetch_duration = f"{time.time() - start_time:.2f}s"
        log_lines[-1] = f"✅ Fetched log content... (took {fetch_duration})"
        
        if not raw_log:
            error_text = f"❌ Failed to fetch log `{log_id}`."
            if status == 404: error_text += " Not found (private or expired)."
            elif status > 0: error_text += f" HTTP status {status}."
            else: error_text += " Network error."
            await status_message.edit(content=error_text, view=None)
            logging.error(f"Failed to fetch log {log_id}. Status: {status}")
            return
        if not self.is_crash_report(raw_log):
            await status_message.edit(content=f"ℹ️ Log `{log_id}` is not a crash report. Analysis skipped.", view=None)
            logging.info(f"Skipped log {log_id} as it was not a crash report.")
            return

        start_time = time.time()
        await update_status(f"{thinking_emoji} Categorizing crash...")
        
        async with aiosqlite.connect(DB_FILE) as db:
            async with db.execute("SELECT name FROM categories") as cursor:
                existing_categories = [row[0] for row in await cursor.fetchall()]
        
        categories = await analyze_log_for_category(raw_log, existing_categories)
        category_duration = f"{time.time() - start_time:.2f}s"
        log_lines[-1] = f"🏷️ Categorized as `{', '.join(categories)}`... (took {category_duration})"
        await status_message.edit(content="\n".join(log_lines))

        start_time = time.time()
        await update_status(f"{thinking_emoji} Analyzing for potential causes...")
        causes_result = await analyze_log_for_causes(raw_log)
        causes_duration = f"{time.time() - start_time:.2f}s"
        log_lines[-1] = f"🧠 Analyzed for potential causes... (took {causes_duration})"
        await status_message.edit(content="\n".join(log_lines))
        if "Error:" in causes_result:
            await status_message.edit(content=f"❌ {causes_result}", view=None)
            logging.error(f"AI analysis for causes failed for log {log_id}: {causes_result}")
            return

        start_time = time.time()
        await update_status(f"{thinking_emoji} Analyzing for possible fixes...")
        fixes_result = await analyze_log_for_fixes(raw_log, causes_result)
        fixes_duration = f"{time.time() - start_time:.2f}s"
        log_lines[-1] = f"🧠 Analyzed for possible fixes... (took {fixes_duration})"
        if "Error:" in fixes_result:
            await status_message.edit(content=f"❌ {fixes_result}", view=None)
            logging.error(f"AI analysis for fixes failed for log {log_id}: {fixes_result}")
            return

        await update_status(f"🔍 Searching for related crash reports...")
        history = []
        async with aiosqlite.connect(DB_FILE) as db:
            async with db.execute("""
                SELECT a.source_message_id, a.log_id, a.embeds_json
                FROM analyses a
                JOIN json_each(a.categories_json) j
                WHERE j.value IN (SELECT value FROM json_each(?)) AND a.source_message_id != ?
                GROUP BY a.source_message_id
                ORDER BY a.rowid DESC LIMIT 5
            """, (json.dumps(categories), source_message_id)) as cursor:
                async for row in cursor:
                    source_msg_id, hist_log_id, embeds_json = row
                    try:
                        original_message = await reply_target.channel.fetch_message(source_msg_id)
                        embed_data = json.loads(embeds_json)
                        history.append({
                            "log_id": hist_log_id,
                            "message_id": original_message.id,
                            "channel_id": original_message.channel.id,
                            "guild_id": original_message.guild.id,
                            "causes": embed_data[0]['description']
                        })
                    except discord.NotFound:
                        logging.warning(f"Could not find original message {source_msg_id} for history.")
                        continue
        log_lines[-1] = f"🔍 Found {len(history)} related crash report(s)."

        log_lines.append(f"📄 Posting results!")
        embeds = await self.create_paginated_embeds(causes_result, fixes_result, categories, history)
        
        view = AnalysisPagination()
        view._update_buttons(0, len(embeds))

        await status_message.edit(content="\n".join(log_lines), embed=embeds[0], view=view)
        logging.info(f"Successfully posted analysis for log {log_id}.")
        
        # --- Post to Forum Channel ---
        forum_post_id = None
        if FORUM_CHANNEL_ID:
            try:
                forum = await bot.fetch_channel(FORUM_CHANNEL_ID)
                if isinstance(forum, discord.ForumChannel):
                    post_title = f"{' '.join(categories)} - mclo.gs/{log_id}"
                    if len(post_title) > 100: post_title = post_title[:97] + "..."
                    
                    forum_post_message, _ = await forum.create_thread(name=post_title, content=f"Analysis for `mclo.gs/{log_id}`")
                    forum_post_id = forum_post_message.id
                    
                    for embed in embeds:
                        await forum_post_message.send(embed=embed)
                    await forum_post_message.send(view=ForumPostControls())
                    
                    logging.info(f"Created forum post {forum_post_id} for analysis.")
                else:
                    logging.error(f"Channel ID {FORUM_CHANNEL_ID} is not a forum channel.")
            except (discord.NotFound, discord.Forbidden) as e:
                logging.error(f"Failed to create forum post: {e}")

        async with aiosqlite.connect(DB_FILE) as db:
            embeds_json = json.dumps([e.to_dict() for e in embeds])
            categories_json = json.dumps(categories)
            await db.execute("UPDATE analyses SET message_id = ?, author_id = ?, log_id = ?, embeds_json = ?, current_page = ?, categories_json = ?, follow_up_count = ?, forum_post_id = ?, status = 'complete' WHERE source_message_id = ?",
                             (status_message.id, author.id, log_id, embeds_json, 0, categories_json, 0, forum_post_id, source_message_id))
            for cat in categories:
                await db.execute("INSERT OR IGNORE INTO categories (name) VALUES (?)", (cat,))
            await db.commit()
            logging.info(f"Saved analysis for message {status_message.id} to database with categories '{', '.join(categories)}'.")
            await update_presence.coro(bot)


    @commands.Cog.listener()
    async def on_message(self, message: discord.Message):
        if message.author.bot or not message.guild: return
        
        # --- Auto-analysis Channel Logic ---
        if message.channel.id == AUTO_ANALYSIS_CHANNEL_ID:
            if time.time() - self.last_auto_analysis_time < 300: # 5 minute cooldown
                return

            log_id = None
            if message.embeds:
                for embed in message.embeds:
                    log_id = self.get_mclogs_id_from_embed(embed)
                    if log_id: break
            
            if log_id:
                self.last_auto_analysis_time = time.time()
                logging.info(f"Auto-detected log {log_id} in channel {message.channel.id}.")
                try:
                    thread = await message.create_thread(name=f"Analysis for {log_id}")
                    await self.start_analysis_pipeline(thread, message.id, log_id, message.author)
                except Exception as e:
                    logging.error(f"Failed to start auto-analysis pipeline: {e}")
            return # End processing for this channel

        # --- Expert Role Confirmation Logic ---
        expert_role_ids = get_expert_roles(message.guild.id)
        if not expert_role_ids: return
        author_role_ids = {role.id for role in message.author.roles}
        if not any(role_id in author_role_ids for role_id in expert_role_ids): return
        log_id = self.get_mclogs_id_from_content(message.content)
        if not log_id: return

        logging.info(f"Detected mclogs link from authorized user {message.author} (ID: {message.author.id}). Message ID: {message.id}")

        async with aiosqlite.connect(DB_FILE) as db:
            await db.execute("INSERT OR IGNORE INTO analyses (source_message_id, status) VALUES (?, ?)", (message.id, 'pending'))
            await db.commit()
            async with db.execute("SELECT status FROM analyses WHERE source_message_id = ?", (message.id,)) as cursor:
                status = (await cursor.fetchone())[0]
                if status != 'pending':
                    logging.warning(f"Ignoring already processed message ID: {message.id}")
                    return
        
        view = ConfirmationView(author=message.author, log_id=log_id, source_message_id=message.id, cog_ref=self)
        confirmation_message = await message.reply(
            "I've detected a mclo.gs link. Would you like me to analyze it for you?",
            view=view,
            mention_author=False
        )
        view.message = confirmation_message
        logging.info(f"Sent confirmation prompt for log {log_id} to user {message.author.id}.")

class ConfirmationView(discord.ui.View):
    def __init__(self, author: discord.Member, log_id: str, source_message_id: int, cog_ref: LogAnalyzer):
        super().__init__(timeout=300.0)
        self.author = author
        self.log_id = log_id
        self.source_message_id = source_message_id
        self.cog_ref = cog_ref
        self.message = None

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id != self.author.id:
            await interaction.response.send_message("You are not authorized to interact with this.", ephemeral=True)
            return False
        return True

    async def on_timeout(self):
        if self.message:
            for item in self.children: item.disabled = True
            await self.message.edit(content=f"Confirmation for log `{self.log_id}` timed out.", view=self)
            logging.info(f"Confirmation for log {self.log_id} timed out for user {self.author.id}.")

    @discord.ui.button(label="Analyze", style=discord.ButtonStyle.success)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button):
        logging.info(f"User {self.author.id} confirmed analysis for log {self.log_id}.")
        for item in self.children: item.disabled = True
        await interaction.response.edit_message(view=self)
        await self.cog_ref.start_analysis_pipeline(self.message, self.source_message_id, self.log_id, self.author)

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.danger)
    async def cancel(self, interaction: discord.Interaction, button: discord.ui.Button):
        logging.info(f"User {self.author.id} cancelled analysis for log {self.log_id}.")
        for item in self.children: item.disabled = True
        await interaction.response.edit_message(content=f"Analysis for log `{self.log_id}` canceled.", view=self)
        async with aiosqlite.connect(DB_FILE) as db:
            await db.execute("UPDATE analyses SET status = 'cancelled' WHERE source_message_id = ?", (self.source_message_id,))
            await db.commit()

class AdminSlashCommands(commands.Cog, name="Admin"):
    def __init__(self, bot: commands.Bot): self.bot = bot
    @app_commands.command(name='help', description="Shows the bot's help information.")
    async def help_command(self, interaction: discord.Interaction):
        embed = discord.Embed(title="Bot Help", description="This bot analyzes mclo.gs crash reports for expert modpack developers.", color=discord.Color.blue())
        embed.add_field(name="How it Works", value="When a user with a configured 'expert role' posts a message containing an `mclo.gs` URL, the bot will automatically provide a detailed technical analysis.", inline=False)
        embed.add_field(name="Admin Commands", value="`/setrole <role>` - Overwrites all expert roles with a single new one.\n`/addrole <role>` - Adds a role to the list of experts.\n`/removerole <role>` - Removes a role from the list.\n`/listroles` - Shows all current expert roles.", inline=False)
        embed.set_footer(text="Requires Administrator permissions for config commands.")
        await interaction.response.send_message(embed=embed, ephemeral=True)
    @app_commands.command(name='setrole', description="Sets the single expert role for this server.")
    @app_commands.describe(role="The role to set as the expert role.")
    @app_commands.checks.has_permissions(administrator=True)
    async def set_role(self, interaction: discord.Interaction, role: discord.Role):
        guild_id = str(interaction.guild.id)
        if "guilds" not in config: config["guilds"] = {}
        config["guilds"][guild_id] = {'expert_roles': [role.id]}
        save_config(config)
        await interaction.response.send_message(f"✅ Success! The expert role has been set to **{role.name}**.", ephemeral=True)
        logging.info(f"Admin {interaction.user} set expert role to {role.name} in guild {interaction.guild.id}.")
    @app_commands.command(name='addrole', description="Adds an expert role for this server.")
    @app_commands.describe(role="The expert role to add.")
    @app_commands.checks.has_permissions(administrator=True)
    async def add_role(self, interaction: discord.Interaction, role: discord.Role):
        guild_id = str(interaction.guild.id)
        if "guilds" not in config: config["guilds"] = {}
        if guild_id not in config["guilds"]: config["guilds"][guild_id] = {'expert_roles': []}
        roles = config["guilds"][guild_id].get('expert_roles', [])
        if role.id not in roles:
            roles.append(role.id)
            config["guilds"][guild_id]['expert_roles'] = roles
            save_config(config)
            await interaction.response.send_message(f"✅ Success! **{role.name}** has been added.", ephemeral=True)
            logging.info(f"Admin {interaction.user} added expert role {role.name} in guild {interaction.guild.id}.")
        else:
            await interaction.response.send_message(f"⚠️ **{role.name}** is already an expert role.", ephemeral=True)
    @app_commands.command(name='removerole', description="Removes an expert role for this server.")
    @app_commands.describe(role="The expert role to remove.")
    @app_commands.checks.has_permissions(administrator=True)
    async def remove_role(self, interaction: discord.Interaction, role: discord.Role):
        guild_id = str(interaction.guild.id)
        roles = get_expert_roles(interaction.guild.id)
        if role.id in roles:
            roles.remove(role.id)
            config["guilds"][guild_id]['expert_roles'] = roles
            save_config(config)
            await interaction.response.send_message(f"✅ Success! **{role.name}** has been removed.", ephemeral=True)
            logging.info(f"Admin {interaction.user} removed expert role {role.name} in guild {interaction.guild.id}.")
        else:
            await interaction.response.send_message(f"⚠️ **{role.name}** is not an expert role.", ephemeral=True)
    @app_commands.command(name='listroles', description="Lists the current expert roles for this server.")
    @app_commands.checks.has_permissions(administrator=True)
    async def list_roles(self, interaction: discord.Interaction):
        roles_ids = get_expert_roles(interaction.guild.id)
        if not roles_ids:
            await interaction.response.send_message("There are no expert roles configured for this server.", ephemeral=True)
            return
        role_mentions = [f"<@&{role_id}>" for role_id in roles_ids]
        await interaction.response.send_message(f"Current expert roles: {', '.join(role_mentions)}", ephemeral=True, allowed_mentions=discord.AllowedMentions.none())

# --- Bot Startup & DB Setup ---
async def setup_database():
    """Initializes the database and performs any necessary migrations."""
    async with aiosqlite.connect(DB_FILE) as db:
        # Main analysis table
        await db.execute("""
            CREATE TABLE IF NOT EXISTS analyses (
                source_message_id INTEGER PRIMARY KEY,
                message_id INTEGER,
                author_id INTEGER,
                log_id TEXT,
                embeds_json TEXT,
                categories_json TEXT,
                current_page INTEGER,
                follow_up_count INTEGER,
                forum_post_id INTEGER,
                status TEXT NOT NULL
            )
        """)
        # Categories table
        await db.execute("""
            CREATE TABLE IF NOT EXISTS categories (
                name TEXT PRIMARY KEY
            )
        """)
        
        # Populate default categories if the table is empty
        async with db.execute("SELECT count(*) FROM categories") as cursor:
            count = (await cursor.fetchone())[0]
            if count == 0:
                default_categories = config.get("default_categories", [])
                if default_categories:
                    await db.executemany("INSERT OR IGNORE INTO categories (name) VALUES (?)", [(cat,) for cat in default_categories])
                    logging.info(f"Populated database with {len(default_categories)} default categories.")

        await db.commit()
        logging.info("Database setup and migration check complete.")

@tasks.loop(minutes=5)
async def update_presence(bot):
    async with aiosqlite.connect(DB_FILE) as db:
        async with db.execute("SELECT COUNT(*) FROM analyses WHERE status = 'complete'") as cursor:
            count = (await cursor.fetchone())[0]
    await bot.change_presence(activity=discord.Activity(type=discord.ActivityType.watching, name=f"{count} Crashes analyzed"))
    logging.info(f"Updated presence to {count} crashes analyzed.")

@bot.event
async def on_ready():
    await setup_database()
    
    bot.add_view(ForumPostControls())
    bot.add_view(AnalysisPagination())
    logging.info("Re-registered persistent views.")

    await bot.add_cog(LogAnalyzer(bot))
    await bot.add_cog(AdminSlashCommands(bot))
    update_presence.start(bot)

    try:
        synced = await bot.tree.sync()
        logging.info(f"Synced {len(synced)} application command(s).")
    except Exception as e:
        logging.error(f"Failed to sync commands: {e}")
    logging.info(f'Logged in as {bot.user.name} ({bot.user.id})')

if __name__ == "__main__":
    bot.run(DISCORD_BOT_TOKEN)
