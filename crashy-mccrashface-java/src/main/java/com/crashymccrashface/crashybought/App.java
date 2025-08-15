package com.crashymccrashface.crashybought;

public class App {
    public static void main(String[] args) {
        Config config = Config.load();
        System.out.println("Config loaded successfully!");

        DatabaseManager.setupDatabase(config);
    }
}