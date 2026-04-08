package com.cribbagecounter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private final String jdbcUrl;
    private final String user;
    private final String password;

    public Database() {
        this.jdbcUrl = Env.get("SUPABASE_DB_JDBC_URL");
        this.user = Env.get("SUPABASE_DB_USER");
        this.password = Env.get("SUPABASE_DB_PASSWORD");

        if (jdbcUrl == null || user == null || password == null) {
            throw new IllegalStateException("Missing SUPABASE_DB_JDBC_URL, SUPABASE_DB_USER, or SUPABASE_DB_PASSWORD");
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    public void bootstrapSchema() {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id UUID PRIMARY KEY,
                    username TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """);

            statement.execute("""
                CREATE TABLE IF NOT EXISTS games (
                    id UUID PRIMARY KEY,
                    created_by UUID NOT NULL REFERENCES users(id),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    status TEXT NOT NULL DEFAULT 'active'
                )
                """);

            statement.execute("""
                CREATE TABLE IF NOT EXISTS game_players (
                    game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
                    player_username TEXT NOT NULL,
                    seat INT NOT NULL,
                    score INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (game_id, player_username),
                    UNIQUE (game_id, seat)
                )
                """);

            statement.execute("""
                CREATE TABLE IF NOT EXISTS score_events (
                    id BIGSERIAL PRIMARY KEY,
                    game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
                    player_username TEXT NOT NULL,
                    points INT NOT NULL,
                    note TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """);
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to bootstrap schema", ex);
        }
    }
}

