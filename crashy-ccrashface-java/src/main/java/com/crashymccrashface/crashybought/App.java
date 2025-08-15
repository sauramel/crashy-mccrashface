package com.crashymccrashface.crashybought;

public class App {
    public static void main(String[] args) {
        Config config = Config.load();
        System.out.println("Config loaded successfully!");

        DatabaseManager.setupDatabase(config);

        // TODO: Replace with your actual project ID and location
        String projectId = "your-gcp-project-id";
        String location = "us-central1";
        GeminiAnalysis geminiAnalysis = new GeminiAnalysis(projectId, location, config.getGeminiApiKey());
        System.out.println("Gemini Analysis service initialized.");

        try {
            new Bot(config, geminiAnalysis);
        } catch (InterruptedException e) {
            System.err.println("Failed to start bot: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
