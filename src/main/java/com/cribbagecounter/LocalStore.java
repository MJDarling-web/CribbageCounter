package com.cribbagecounter;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class LocalStore {
    private final Path storeFile;
    private State state;

    public LocalStore(Path storeFile) {
        this.storeFile = storeFile;
        this.state = load();
    }

    public synchronized User createUser(String username, String passwordHash) {
        String normalized = normalizeUsername(username);
        if (normalized == null) {
            throw new IllegalArgumentException("Username is required");
        }
        if (state.usersByUsername.containsKey(normalized)) {
            throw new IllegalArgumentException("Username already exists");
        }

        User user = new User(UUID.randomUUID(), normalized, passwordHash, 0, 0);
        state.usersByUsername.put(normalized, user);
        save();
        return user;
    }

    public synchronized User findUserByUsername(String username) {
        return state.usersByUsername.get(normalizeUsername(username));
    }

    public synchronized boolean allUsersExist(List<String> usernames) {
        for (String username : usernames) {
            if (!state.usersByUsername.containsKey(normalizeUsername(username))) {
                return false;
            }
        }
        return true;
    }

    public synchronized UUID createGame(UUID creatorId, List<String> usernames) {
        UUID gameId = UUID.randomUUID();
        LinkedHashMap<String, Integer> scores = new LinkedHashMap<>();
        for (String username : usernames) {
            scores.put(normalizeUsername(username), 0);
        }

        Game game = new Game(gameId, creatorId, Instant.now(), "active", new ArrayList<>(scores.keySet()), scores, new ArrayList<>());
        state.gamesById.put(gameId, game);
        save();
        return gameId;
    }

    public synchronized boolean canAccessGame(UUID gameId, String username) {
        Game game = state.gamesById.get(gameId);
        return game != null && game.players.contains(normalizeUsername(username));
    }

    public synchronized boolean isPlayerInGame(UUID gameId, String username) {
        Game game = state.gamesById.get(gameId);
        return game != null && game.scores.containsKey(normalizeUsername(username));
    }

    public synchronized void addScore(UUID gameId, String playerUsername, int points, String note) {
        Game game = requireGame(gameId);
        String normalized = normalizeUsername(playerUsername);
        if (!game.scores.containsKey(normalized)) {
            throw new IllegalArgumentException("Player is not in this game");
        }
        int current = game.scores.get(normalized);
        game.scores.put(normalized, current + points);
        game.events.add(0, new ScoreEvent(normalized, points, note, Instant.now()));
        save();
    }

    public synchronized GameView getGameView(UUID gameId) {
        Game game = requireGame(gameId);

        List<PlayerScore> players = new ArrayList<>();
        for (String username : game.players) {
            players.add(new PlayerScore(username, game.scores.getOrDefault(username, 0)));
        }

        List<ScoreEventView> events = new ArrayList<>();
        int max = Math.min(20, game.events.size());
        for (int i = 0; i < max; i++) {
            ScoreEvent event = game.events.get(i);
            events.add(new ScoreEventView(event.playerUsername, event.points, event.note, event.createdAt));
        }

        return new GameView(game.id, game.createdAt, game.status, players, events);
    }

    public synchronized List<GameSummary> listGamesForUser(String username) {
        String normalized = normalizeUsername(username);
        List<GameSummary> games = new ArrayList<>();

        for (Game game : state.gamesById.values()) {
            if (!game.players.contains(normalized)) {
                continue;
            }
            List<PlayerScore> players = new ArrayList<>();
            for (String player : game.players) {
                players.add(new PlayerScore(player, game.scores.getOrDefault(player, 0)));
            }
            games.add(new GameSummary(game.id, game.createdAt, game.status, players));
        }

        games.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return games;
    }

    public synchronized void finishGame(UUID gameId, String winnerUsername) {
        Game game = requireGame(gameId);
        if (!"active".equals(game.status)) {
            throw new IllegalStateException("Game is already finished");
        }

        String winner = normalizeUsername(winnerUsername);
        if (!game.players.contains(winner)) {
            throw new IllegalArgumentException("Winner must be a player in the game");
        }

        for (String player : game.players) {
            User user = state.usersByUsername.get(player);
            if (user == null) {
                continue;
            }
            if (player.equals(winner)) {
                user.wins += 1;
            } else {
                user.losses += 1;
            }
        }

        game.status = "finished";
        save();
    }

    public synchronized List<UserStats> leaderboard() {
        List<UserStats> stats = new ArrayList<>();
        for (User user : state.usersByUsername.values()) {
            stats.add(new UserStats(user.username, user.wins, user.losses));
        }
        stats.sort((a, b) -> {
            int byWins = Integer.compare(b.wins, a.wins);
            if (byWins != 0) {
                return byWins;
            }
            return Integer.compare(a.losses, b.losses);
        });
        return stats;
    }

    private Game requireGame(UUID gameId) {
        Game game = state.gamesById.get(gameId);
        if (game == null) {
            throw new IllegalArgumentException("Game not found");
        }
        return game;
    }

    private State load() {
        if (!Files.exists(storeFile)) {
            return new State();
        }

        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(storeFile))) {
            Object obj = input.readObject();
            if (obj instanceof State loaded) {
                return loaded;
            }
            return new State();
        } catch (IOException | ClassNotFoundException ex) {
            return new State();
        }
    }

    private void save() {
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(storeFile))) {
                output.writeObject(state);
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to save local store", ex);
        }
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static class State implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Map<String, User> usersByUsername = new HashMap<>();
        private final Map<UUID, Game> gamesById = new HashMap<>();
    }

    public static class User implements Serializable {
        private static final long serialVersionUID = 1L;

        public final UUID id;
        public final String username;
        public final String passwordHash;
        public int wins;
        public int losses;

        public User(UUID id, String username, String passwordHash, int wins, int losses) {
            this.id = id;
            this.username = username;
            this.passwordHash = passwordHash;
            this.wins = wins;
            this.losses = losses;
        }
    }

    private static class Game implements Serializable {
        private static final long serialVersionUID = 1L;

        public final UUID id;
        public final UUID createdBy;
        public final Instant createdAt;
        public String status;
        public final List<String> players;
        public final LinkedHashMap<String, Integer> scores;
        public final List<ScoreEvent> events;

        public Game(UUID id, UUID createdBy, Instant createdAt, String status, List<String> players,
                    LinkedHashMap<String, Integer> scores, List<ScoreEvent> events) {
            this.id = id;
            this.createdBy = createdBy;
            this.createdAt = createdAt;
            this.status = status;
            this.players = players;
            this.scores = scores;
            this.events = events;
        }
    }

    private static class ScoreEvent implements Serializable {
        private static final long serialVersionUID = 1L;

        public final String playerUsername;
        public final int points;
        public final String note;
        public final Instant createdAt;

        public ScoreEvent(String playerUsername, int points, String note, Instant createdAt) {
            this.playerUsername = playerUsername;
            this.points = points;
            this.note = note;
            this.createdAt = createdAt;
        }
    }

    public record UserStats(String username, int wins, int losses) {
    }

    public record PlayerScore(String username, int score) {
    }

    public record GameSummary(UUID id, Instant createdAt, String status, List<PlayerScore> players) {
    }

    public record ScoreEventView(String playerUsername, int points, String note, Instant createdAt) {
    }

    public record GameView(UUID id, Instant createdAt, String status, List<PlayerScore> players, List<ScoreEventView> events) {
    }
}

