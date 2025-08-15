package com.crashymccrashface.crashybought;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class DatabaseManager {

    private static final String DB_FILE = "analysis_data.db";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
    }

    public static void setupDatabase(Config config) {
        String createAnalysesTable = """
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
        """;

        String createCategoriesTable = """
            CREATE TABLE IF NOT EXISTS categories (
                name TEXT PRIMARY KEY
            )
        """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(createAnalysesTable);
            stmt.execute(createCategoriesTable);

            // Populate default categories if the table is empty
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM categories")) {
                if (rs.getInt(1) == 0) {
                    List<String> defaultCategories = config.getDefaultCategories();
                    if (defaultCategories != null && !defaultCategories.isEmpty()) {
                        String insertCategory = "INSERT OR IGNORE INTO categories (name) VALUES (?)";
                        try (PreparedStatement pstmt = conn.prepareStatement(insertCategory)) {
                            for (String category : defaultCategories) {
                                pstmt.setString(1, category);
                                pstmt.addBatch();
                            }
                            pstmt.executeBatch();
                            System.out.println("Populated database with " + defaultCategories.size() + " default categories.");
                        }
                    }
                }
            }
            System.out.println("Database setup and migration check complete.");

        } catch (SQLException e) {
            System.err.println("Database setup failed: " + e.getMessage());
        }
    }
}