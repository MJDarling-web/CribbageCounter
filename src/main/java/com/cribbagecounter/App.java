package com.cribbagecounter;

import io.javalin.Javalin;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class App {
    private final LocalStore store;

    public App(LocalStore store) {
        this.store = store;
    }

    public static void main(String[] args) {
        int port = Env.getInt("PORT", 7070);
        Path dataFile = Paths.get(Env.get("DATA_FILE") == null ? "data/local-store.ser" : Env.get("DATA_FILE"));

        App app = new App(new LocalStore(dataFile));
        app.start(port);
    }

    public void start(int port) {
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);

        app.get("/", ctx -> ctx.redirect("/dashboard"));

        app.get("/dashboard", ctx -> {
            List<LocalStore.GameSummary> games = store.listGames();
            List<LocalStore.UserStats> leaderboard = store.leaderboard();
            ctx.html(layout("Dashboard", dashboardHtml(games, leaderboard, null)));
        });

        app.post("/games", ctx -> {
            String playerOne = normalizeName(ctx.formParam("playerOne"));
            String playerTwo = normalizeName(ctx.formParam("playerTwo"));

            if (playerOne == null || playerTwo == null) {
                ctx.html(layout("Dashboard", dashboardHtml(store.listGames(), store.leaderboard(),
                    "Both player names are required.")));
                return;
            }
            if (playerOne.equals(playerTwo)) {
                ctx.html(layout("Dashboard", dashboardHtml(store.listGames(), store.leaderboard(),
                    "Player names must be different.")));
                return;
            }

            UUID gameId = store.createGame(playerOne, playerTwo);
            ctx.redirect("/games/" + gameId);
        });

        app.get("/games/{id}", ctx -> {
            UUID gameId = UUID.fromString(ctx.pathParam("id"));
            LocalStore.GameView view = store.getGameView(gameId);
            ctx.html(layout("Game", gameHtml(view, null)));
        });

        app.post("/games/{id}/scores", ctx -> {
            UUID gameId = UUID.fromString(ctx.pathParam("id"));
            try {
                String playerName = normalizeName(ctx.formParam("playerName"));
                int points = Integer.parseInt(ctx.formParam("points"));
                String note = ctx.formParam("note");

                if (playerName == null || !store.isPlayerInGame(gameId, playerName)) {
                    ctx.html(layout("Game", gameHtml(store.getGameView(gameId), "Selected player is not in this game.")));
                    return;
                }

                store.addScore(gameId, playerName, points, note);
                ctx.redirect("/games/" + gameId);
            } catch (NumberFormatException ex) {
                ctx.html(layout("Game", gameHtml(store.getGameView(gameId), "Points must be a valid integer.")));
            } catch (IllegalArgumentException ex) {
                ctx.html(layout("Game", gameHtml(store.getGameView(gameId), ex.getMessage())));
            }
        });

        app.post("/games/{id}/finish", ctx -> {
            UUID gameId = UUID.fromString(ctx.pathParam("id"));
            try {
                String winner = normalizeName(ctx.formParam("winnerName"));
                store.finishGame(gameId, winner);
                ctx.redirect("/games/" + gameId);
            } catch (RuntimeException ex) {
                ctx.html(layout("Game", gameHtml(store.getGameView(gameId), ex.getMessage())));
            }
        });

        app.start(port);
        System.out.println("Cribbage Counter started on http://localhost:" + port);
    }

    private String normalizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? null : value;
    }

    private String dashboardHtml(List<LocalStore.GameSummary> games,
                                 List<LocalStore.UserStats> leaderboard, String error) {
        StringBuilder gameList = new StringBuilder();
        if (games.isEmpty()) {
            gameList.append("<p>No games yet. Start one below.</p>");
        } else {
            gameList.append("<ul>");
            for (LocalStore.GameSummary game : games) {
                gameList.append("<li>")
                    .append("<a href=\"/games/").append(game.id()).append("\">Game ").append(game.id()).append("</a>")
                    .append(" (created ").append(game.createdAt()).append(") - ")
                    .append(escapeHtml(game.status()))
                    .append("<br>");

                for (LocalStore.PlayerScore player : game.players()) {
                    gameList.append(escapeHtml(player.username())).append(": ").append(player.score()).append(" ");
                }
                gameList.append("</li>");
            }
            gameList.append("</ul>");
        }

        StringBuilder stats = new StringBuilder("<ul>");
        for (LocalStore.UserStats row : leaderboard) {
            stats.append("<li>")
                .append(escapeHtml(row.username()))
                .append(" - Wins: ").append(row.wins())
                .append(" | Losses: ").append(row.losses())
                .append("</li>");
        }
        stats.append("</ul>");

        String errorHtml = error == null ? "" : "<p style='color:#b00020;'>" + escapeHtml(error) + "</p>";

        String template = "<h1>Cribbage Counter</h1>"
            + "<h2>New 2-Player Game</h2>"
            + "%s"
            + "<form method=\"post\" action=\"/games\">"
            + "<label>Player 1:</label><br>"
            + "<input name=\"playerOne\" placeholder=\"alice\" required /><br>"
            + "<label>Player 2:</label><br>"
            + "<input name=\"playerTwo\" placeholder=\"bob\" required /><br>"
            + "<button type=\"submit\">Create Game</button>"
            + "</form>"
            + "<h2>Leaderboard</h2>"
            + "%s"
            + "<h2>Recent Games</h2>"
            + "%s";

        return String.format(template, errorHtml, stats, gameList);
    }

    private String gameHtml(LocalStore.GameView view, String error) {
        StringBuilder rows = new StringBuilder("<ul>");
        for (LocalStore.PlayerScore player : view.players()) {
            rows.append("<li>").append(escapeHtml(player.username())).append(": ").append(player.score()).append("</li>");
        }
        rows.append("</ul>");

        StringBuilder options = new StringBuilder();
        for (LocalStore.PlayerScore player : view.players()) {
            options.append("<option value=\"")
                .append(escapeHtml(player.username()))
                .append("\">")
                .append(escapeHtml(player.username()))
                .append("</option>");
        }

        StringBuilder events = new StringBuilder();
        if (view.events().isEmpty()) {
            events.append("<p>No score events yet.</p>");
        } else {
            events.append("<ul>");
            for (LocalStore.ScoreEventView event : view.events()) {
                events.append("<li>")
                    .append(escapeHtml(event.playerUsername()))
                    .append(" ")
                    .append(event.points() >= 0 ? "+" : "")
                    .append(event.points())
                    .append(" at ")
                    .append(event.createdAt())
                    .append(event.note() == null || event.note().isBlank() ? "" : " (" + escapeHtml(event.note()) + ")")
                    .append("</li>");
            }
            events.append("</ul>");
        }

        String errorHtml = error == null ? "" : "<p style='color:#b00020;'>" + escapeHtml(error) + "</p>";

        String template = "<p><a href=\"/dashboard\">Back to dashboard</a></p>"
            + "<h1>Game %s</h1>"
            + "<p>Created: %s | Status: %s</p>"
            + "<h2>Scoreboard</h2>"
            + "%s"
            + "<h2>Add Score</h2>"
            + "%s"
            + "<form method=\"post\" action=\"/games/%s/scores\">"
            + "<label>Player:</label>"
            + "<select name=\"playerName\">%s</select><br>"
            + "<label>Points:</label>"
            + "<input name=\"points\" type=\"number\" value=\"0\" /><br>"
            + "<label>Note:</label>"
            + "<input name=\"note\" placeholder=\"hand, crib, pegging...\" /><br>"
            + "<button type=\"submit\">Add Score</button>"
            + "</form>"
            + "<h2>Finish Game</h2>"
            + "<form method=\"post\" action=\"/games/%s/finish\">"
            + "<label>Winner:</label>"
            + "<select name=\"winnerName\">%s</select>"
            + "<button type=\"submit\">Finish Game</button>"
            + "</form>"
            + "<h2>Recent Score Events</h2>"
            + "%s";

        return String.format(template,
            view.id(), view.createdAt(), escapeHtml(view.status()), rows, errorHtml,
            view.id(), options, view.id(), options, events);
    }

    private String layout(String title, String body) {
        String template = "<!doctype html>"
            + "<html lang=\"en\">"
            + "<head>"
            + "<meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>%s</title>"
            + "<style>"
            + "body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; max-width: 860px; margin: 24px auto; padding: 0 16px; }"
            + "input, select, button { margin-top: 6px; margin-bottom: 10px; }"
            + "a { color: #0057d8; text-decoration: none; }"
            + "a:hover { text-decoration: underline; }"
            + "</style>"
            + "</head>"
            + "<body>"
            + "%s"
            + "</body>"
            + "</html>";
        return String.format(template, escapeHtml(title), body);
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
