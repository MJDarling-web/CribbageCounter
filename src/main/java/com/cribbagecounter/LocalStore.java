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
    private final State state;

    public LocalStore(Path storeFile) {
        this.storeFile = storeFile;
        this.state = load();
        normalizeState();
    }

    public synchronized UUID createGame(String playerOne, String playerTwo) {
        normalizeState();
        String p1 = normalizeName(playerOne);
        String p2 = normalizeName(playerTwo);
        if (p1 == null || p2 == null) {
            throw new IllegalArgumentException("Both player names are required");
        }
        if (p1.equals(p2)) {
            throw new IllegalArgumentException("Player names must be different");
        }

        UUID gameId = UUID.randomUUID();
        LinkedHashMap<String, Integer> scores = new LinkedHashMap<String, Integer>();
        scores.put(p1, 0);
        scores.put(p2, 0);

        Game game = new Game(gameId, Instant.now(), "active", new ArrayList<String>(scores.keySet()), scores, new ArrayList<ScoreEvent>());
        state.gamesById.put(gameId, game);
        save();
        return gameId;
    }

    public synchronized boolean isPlayerInGame(UUID gameId, String playerName) {
        normalizeState();
        Game game = state.gamesById.get(gameId);
        return game != null && game.scores.containsKey(normalizeName(playerName));
    }

    public synchronized void addScore(UUID gameId, String playerName, int points, String note) {
        normalizeState();
        Game game = requireGame(gameId);
        String normalized = normalizeName(playerName);
        if (!game.scores.containsKey(normalized)) {
            throw new IllegalArgumentException("Player is not in this game");
        }
        int current = game.scores.get(normalized);
        game.scores.put(normalized, current + points);
        game.events.add(0, new ScoreEvent(normalized, points, note, Instant.now()));
        save();
    }

    public synchronized GameView getGameView(UUID gameId) {
        normalizeState();
        Game game = requireGame(gameId);

        List<PlayerScore> players = new ArrayList<PlayerScore>();
        for (String username : game.players) {
            players.add(new PlayerScore(username, game.scores.getOrDefault(username, 0)));
        }

        List<ScoreEventView> events = new ArrayList<ScoreEventView>();
        int max = Math.min(20, game.events.size());
        for (int i = 0; i < max; i++) {
            ScoreEvent event = game.events.get(i);
            events.add(new ScoreEventView(event.playerName, event.points, event.note, event.createdAt));
        }

        return new GameView(game.id, game.createdAt, game.status, players, events);
    }

    public synchronized List<GameSummary> listGames() {
        normalizeState();
        List<GameSummary> games = new ArrayList<GameSummary>();

        for (Game game : state.gamesById.values()) {
            List<PlayerScore> players = new ArrayList<PlayerScore>();
            for (String player : game.players) {
                players.add(new PlayerScore(player, game.scores.getOrDefault(player, 0)));
            }
            games.add(new GameSummary(game.id, game.createdAt, game.status, players));
        }

        games.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return games;
    }

    public synchronized void finishGame(UUID gameId, String winnerName) {
        normalizeState();
        Game game = requireGame(gameId);
        if (!"active".equals(game.status)) {
            throw new IllegalStateException("Game is already finished");
        }

        String winner = normalizeName(winnerName);
        if (!game.players.contains(winner)) {
            throw new IllegalArgumentException("Winner must be a player in the game");
        }

        for (String player : game.players) {
            PlayerStats stats = state.statsByPlayerName.computeIfAbsent(player, key -> new PlayerStats());
            if (player.equals(winner)) {
                stats.wins += 1;
            } else {
                stats.losses += 1;
            }
        }

        game.status = "finished";
        save();
    }

    public synchronized List<UserStats> leaderboard() {
        normalizeState();
        List<UserStats> stats = new ArrayList<UserStats>();
        for (Map.Entry<String, PlayerStats> entry : state.statsByPlayerName.entrySet()) {
            stats.add(new UserStats(entry.getKey(), entry.getValue().wins, entry.getValue().losses));
        }
        stats.sort((a, b) -> {
            int byWins = Integer.compare(b.wins(), a.wins());
            if (byWins != 0) {
                return byWins;
            }
            return Integer.compare(a.losses(), b.losses());
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
            if (obj instanceof State) {
                return (State) obj;
            }
            return new State();
        } catch (IOException | ClassNotFoundException ex) {
            return new State();
        }
    }

    private void normalizeState() {
        if (state.gamesById == null) {
            state.gamesById = new HashMap<UUID, Game>();
        }
        if (state.statsByPlayerName == null) {
            state.statsByPlayerName = new HashMap<String, PlayerStats>();
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

    private String normalizeName(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static class State implements Serializable {
        private static final long serialVersionUID = 1L;

        private Map<UUID, Game> gamesById = new HashMap<UUID, Game>();
        private Map<String, PlayerStats> statsByPlayerName = new HashMap<String, PlayerStats>();
    }

    private static class PlayerStats implements Serializable {
        private static final long serialVersionUID = 1L;

        private int wins;
        private int losses;
    }

    private static class Game implements Serializable {
        private static final long serialVersionUID = 1L;

        public final UUID id;
        public final Instant createdAt;
        public String status;
        public final List<String> players;
        public final LinkedHashMap<String, Integer> scores;
        public final List<ScoreEvent> events;

        public Game(UUID id, Instant createdAt, String status, List<String> players,
                    LinkedHashMap<String, Integer> scores, List<ScoreEvent> events) {
            this.id = id;
            this.createdAt = createdAt;
            this.status = status;
            this.players = players;
            this.scores = scores;
            this.events = events;
        }
    }

    private static class ScoreEvent implements Serializable {
        private static final long serialVersionUID = 1L;

        public final String playerName;
        public final int points;
        public final String note;
        public final Instant createdAt;

        public ScoreEvent(String playerName, int points, String note, Instant createdAt) {
            this.playerName = playerName;
            this.points = points;
            this.note = note;
            this.createdAt = createdAt;
        }
    }

    public static final class UserStats {
        private final String username;
        private final int wins;
        private final int losses;

        public UserStats(String username, int wins, int losses) {
            this.username = username;
            this.wins = wins;
            this.losses = losses;
        }

        public String username() {
            return username;
        }

        public int wins() {
            return wins;
        }

        public int losses() {
            return losses;
        }
    }

    public static final class PlayerScore {
        private final String username;
        private final int score;

        public PlayerScore(String username, int score) {
            this.username = username;
            this.score = score;
        }

        public String username() {
            return username;
        }

        public int score() {
            return score;
        }
    }

    public static final class GameSummary {
        private final UUID id;
        private final Instant createdAt;
        private final String status;
        private final List<PlayerScore> players;

        public GameSummary(UUID id, Instant createdAt, String status, List<PlayerScore> players) {
            this.id = id;
            this.createdAt = createdAt;
            this.status = status;
            this.players = players;
        }

        public UUID id() {
            return id;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public String status() {
            return status;
        }

        public List<PlayerScore> players() {
            return players;
        }
    }

    public static final class ScoreEventView {
        private final String playerUsername;
        private final int points;
        private final String note;
        private final Instant createdAt;

        public ScoreEventView(String playerUsername, int points, String note, Instant createdAt) {
            this.playerUsername = playerUsername;
            this.points = points;
            this.note = note;
            this.createdAt = createdAt;
        }

        public String playerUsername() {
            return playerUsername;
        }

        public int points() {
            return points;
        }

        public String note() {
            return note;
        }

        public Instant createdAt() {
            return createdAt;
        }
    }

    public static final class GameView {
        private final UUID id;
        private final Instant createdAt;
        private final String status;
        private final List<PlayerScore> players;
        private final List<ScoreEventView> events;

        public GameView(UUID id, Instant createdAt, String status, List<PlayerScore> players, List<ScoreEventView> events) {
            this.id = id;
            this.createdAt = createdAt;
            this.status = status;
            this.players = players;
            this.events = events;
        }

        public UUID id() {
            return id;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public String status() {
            return status;
        }

        public List<PlayerScore> players() {
            return players;
        }

        public List<ScoreEventView> events() {
            return events;
        }
    }
}
