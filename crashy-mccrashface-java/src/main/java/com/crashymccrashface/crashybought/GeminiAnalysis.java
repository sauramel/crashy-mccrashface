package com.crashymccrashface.crashybought;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class GeminiAnalysis {

    private final GenerativeModel analysisModel;
    private final GenerativeModel categoryModel;

    public GeminiAnalysis(String projectId, String location, String apiKey) {
        try (VertexAI vertexAI = new VertexAI(projectId, location)) {
            analysisModel = new GenerativeModel("gemini-2.5-pro", vertexAI);
            categoryModel = new GenerativeModel("gemini-2.5-flash", vertexAI);
        } catch (Exception e) {
            System.err.println("Failed to configure Gemini AI: " + e.getMessage());
            throw new RuntimeException("Failed to initialize Gemini AI", e);
        }
    }

    public List<String> chunkLongText(String textToChunk, int limit) {
        if (textToChunk.length() <= limit) {
            return List.of(textToChunk);
        }

        if (categoryModel == null) {
            System.err.println("Chunking model not available, falling back to basic split.");
            // Simple split as fallback
            return Arrays.asList(textToChunk.split("(?<=\\G.{" + limit + "})"));
        }

        String prompt = String.format("""
            The following text is too long. You must break it into smaller pieces.
            Insert the special delimiter `~##~` between sections to split the text.
            Each piece MUST be less than %d characters.
            **CRITICAL:** Do not remove, reword, or change any of the original content. Only insert the `~##~` delimiter.

            **TEXT TO SPLIT:**
            ```
            %s
            ```
            """, limit - 50, textToChunk);

        try {
            GenerateContentResponse response = categoryModel.generateContent(prompt);
            String text = ResponseHandler.getText(response);
            return Arrays.stream(text.split("~##~"))
                         .map(String::strip)
                         .filter(s -> !s.isEmpty())
                         .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Gemini API error (chunking): " + e.getMessage());
            // Fallback to simple split
            return Arrays.asList(textToChunk.split("(?<=\\G.{" + limit + "})"));
        }
    }

    public List<String> analyzeLogForCategory(String logContent, List<String> existingCategories) {
        if (categoryModel == null) {
            return List.of("[Unknown]");
        }

        String existingCategoriesStr = String.join(", ", existingCategories);
        String prompt = String.format("""
            You are a log categorization engine. Your task is to assign relevant categories to a Minecraft crash log.

            **Instructions:**
            1.  Review the list of existing categories: %s
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
            %s
            ```
            """, existingCategoriesStr, logContent);

        try {
            GenerateContentResponse response = categoryModel.generateContent(prompt);
            String text = ResponseHandler.getText(response);
            List<String> categories = Arrays.stream(text.strip().split(","))
                                            .map(String::strip)
                                            .filter(s -> !s.isEmpty() && s.startsWith("[") && s.endsWith("]"))
                                            .collect(Collectors.toList());
            return categories.isEmpty() ? List.of("[Unknown]") : categories;
        } catch (IOException e) {
            System.err.println("Gemini API error (category): " + e.getMessage());
            return List.of("[Unknown]");
        }
    }

    public String analyzeLogForCauses(String logContent) {
        if (analysisModel == null) {
            return "Error: AI model not configured.";
        }
        String prompt = String.format("""
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
            %s
            ```
            """, logContent);

        try {
            GenerateContentResponse response = analysisModel.generateContent(prompt);
            String text = ResponseHandler.getText(response);
            return text.isEmpty() ? "Error: The AI model returned an empty analysis for causes." : text;
        } catch (IOException e) {
            System.err.println("Gemini API error (causes): " + e.getMessage());
            return "Error analyzing causes: `" + e.getMessage() + "`";
        }
    }

    public String analyzeLogForFixes(String logContent, String causesAnalysis) {
        if (analysisModel == null) {
            return "Error: AI model not configured.";
        }
        String prompt = String.format("""
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
            %s
            ```

            **ORIGINAL CRASH LOG (FOR FULL CONTEXT):**
            ```
            %s
            ```
            """, causesAnalysis, logContent);

        try {
            GenerateContentResponse response = analysisModel.generateContent(prompt);
            String text = ResponseHandler.getText(response);
            return text.isEmpty() ? "Error: The AI model returned an empty analysis for fixes." : text;
        } catch (IOException e) {
            System.err.println("Gemini API error (fixes): " + e.getMessage());
            return "Error analyzing fixes: `" + e.getMessage() + "`";
        }
    }

    public String analyzeFollowUp(String logContent, String previousAnalysis, String userQuestion) {
        if (analysisModel == null) {
            return "Error: AI model not configured.";
        }
        String prompt = String.format("""
            You are an expert Minecraft modded server diagnostician.
            A user has a follow-up question about a crash log you previously analyzed.
            Provide a **comprehensive and detailed technical answer** to their question based on the provided context.

            **CRITICAL RULES:**
            1. The mod list is **IMMUTABLE**. Do not suggest removing, adding, or updating mods.
            2. **DO NOT** suggest generic fixes like changing the `max-tick-time`.

            **CONTEXT - ORIGINAL CRASH LOG:**
            ```
            %s
            ```

            **CONTEXT - PREVIOUS ANALYSIS:**
            ```
            %s
            ```

            **USER'S FOLLOW-UP QUESTION:**
            %s
            """, logContent, previousAnalysis, userQuestion);

        try {
            GenerateContentResponse response = analysisModel.generateContent(prompt);
            String text = ResponseHandler.getText(response);
            return text.isEmpty() ? "Error: The AI model returned an empty response for the follow-up." : text;
        } catch (IOException e) {
            System.err.println("Gemini API error (follow-up): " + e.getMessage());
            return "Error analyzing follow-up: `" + e.getMessage() + "`";
        }
    }
}
