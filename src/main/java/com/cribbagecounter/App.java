package com.cribbagecounter;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class App {
    private static final String SESSION_USER_ID = "userId";
    private static final String SESSION_USERNAME = "username";

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

        app.get("/", ctx -> {
            if (isLoggedIn(ctx)) {
                ctx.redirect("/dashboard");
                return;
            }
            ctx.html(layout("Cribbage Counter", """
                <h1>Cribbage Counter</h1>
                <p>Track cribbage games locally with login and wins/losses history.</p>
                <p><a href=\"/login\">Login</a> or <a href=\"/register\">Register</a></p>
                """));
        });

        app.get("/register", ctx -> ctx.html(layout("Register", registerForm(null))));
        app.post("/register", ctx -> {
            String username = normalizeUsername(ctx.formParam("username"));
            String password = ctx.formParam("password");

            if (username == null || password == null || password.length() < 8) {
                ctx.html(layout("Register", registerForm("Username is required and password must be at least 8 characters.")));
                return;
            }

            try {
                if (store.findUserByUsername(username) != null) {
                    ctx.html(layout("Register", registerForm("That username is already taken.")));
                    return;
                }

                LocalStore.User user = store.createUser(username, BCrypt.hashpw(password, BCrypt.gensalt()));
                setSession(ctx, user.id, user.username);
                ctx.redirect("/dashboard");
            } catch (IllegalArgumentException ex) {
                ctx.html(layout("Register", registerForm(ex.getMessage())));
            }
        });

        app.get("/login", ctx -> ctx.html(layout("Login", loginForm(null))));
        app.post("/login", ctx -> {
            String username = normalizeUsername(ctx.formParam("username"));
            String password = ctx.formParam("password");

            if (username == null || password == null) {
                ctx.html(layout("Login", loginForm("Username and password are required.")));
                return;
            }

            LocalStore.User user = store.findUserByUsername(username);
            if (user == null || !BCrypt.checkpw(password, user.passwordHash)) {
                ctx.html(layout("Login", loginForm("Invalid username or password.")));
                return;
            }

            setSession(ctx, user.id, user.username);
            ctx.redirect("/dashboard");
        });

        app.post("/logout", ctx -> {
            ctx.req().getSession().invalidate();
            ctx.redirect("/");
        });

        app.get("/dashboard", ctx -> {
            if (!isLoggedIn(ctx)) {
                ctx.redirect("/login");
                return;
            }

            String username = currentUsername(ctx);
            List<LocalStore.GameSummary> games = store.listGamesForUser(username);
            List<LocalStore.UserStats> leaderboard = store.leaderboard();
            ctx.html(layout("Dashboard", dashboardHtml(username, games, leaderboard, null)));
        });

        app.post("/games", ctx -> {
            if (!isLoggedIn(ctx)) {
                ctx.redirect("/login");
                return;
            }

            String currentUsername = currentUsername(ctx);
            String playersCsv = ctx.formParam("players");
            List<String> players = PlayerParser.parseUsernames(playersCsv);
            if (!players.contains(currentUsername)) {
                players.add(0, currentUsername);
            }

            if (players.size() < 2 || players.size() > 4) {
                ctx.html(layout("Dashboard", dashboardHtml(currentUsername, store.listGamesForUser(currentUsername),
                    store.leaderboard(), "A game must have 2-4 unique players.")));
                return;
            }

            if (!store.allUsersExist(players)) {
                ctx.html(layout("Dashboard", dashboardHtml(currentUsername, store.listGamesForUser(currentUsername),
                    store.leaderboard(), "Every player must already have an account username.")));
                return;
            }

            UUID gameId = store.createGame(currentUserId(ctx), players);
            ctx.redirect("/games/" + gameId);
        });

        app.get("/games/{id}", ctx -> {
            if (!isLoggedIn(ctx)) {
                ctx.redirect("/login");
                return;
            }

            UUID gameId = UUID.fromString(ctx.pathParam("id"));
            String username = currentUsername(ctx);
            if (!store.canAccessGame(gameId, username)) {
                ctx.status(403).html(layout("Forbidden", "<p>You cannot view this game.</p>"));
                return;
            }

            LocalStore.GameView view = store.getGameView(gameId);
            ctx.html(layout("Game", gameHtml(view, null)));
        });

        app.post("/games/{id}/scores", ctx -> {
            if (!isLoggedIn(ctx)) {
                ctx.redirect("/login");
                return;
            }

            UUID gameId = UUID.fromString(ctx.pathParam("id"));
            String username = currentUsername(ctx);
            if (!store.canAccessGame(gameId, username)) {
                ctx.status(403).html(layout("Forbidden", "<p>You cannot update this game.</p>"));
                return;
            }

            try {
                String playerUsername = normalizeUsername(ctx.formParam("playerUsername"));
                int points = Integer.parseInt(ctx.formParam("points"));
                String note = ctx.formParam("note");

                if (playerUsername == null || !store.isPlayerInGame(gameId, playerUsername)) {
                    ctx.html(layout("Game", gameHtml(store.getGameView(gameId), "Selected player is not in this game.")));
                    return;
                }

                store.addScore(gameId, playerUsername, points, note);
                ctx.redirect("/games/" + gameId);
            } catch (NumberFormatException ex) {
                ctx.html(layout("Game", gameHtml(store.getGameView(gameId), "Points must be a valid integer.")));
            } catch (IllegalArgumentException ex) {
                ctx.html(layout("Game", gameHtml(store.getGameView(gameId), ex.getMessage())));
            }
        });

        app.post("/games/{id}/finish", ctx -> {
            if (!isLoggedIn(ctx)) {
                ctx.redirect("/login");
                return;
            }

            UUID gameId = UUID.fromString(ctx.pathParam("id"));
            String username = currentUsername(ctx);
            if (!store.canAccessGame(gameId, username)) {
                ctx.status(403).html(layout("Forbidden", "<p>You cannot finish this game.</p>"));
                return;
            }

            try {
                String winner = normalizeUsername(ctx.formParam("winnerUsername"));
                store.finishGame(gameId, winner);
                ctx.redirect("/games/" + gameId);
            } catch (RuntimeException ex) {
                ctx.html(layout("Game", gameHtml(store.getGameView(gameId), ex.getMessage())));
            }
        });

        app.start(port);
        System.out.println("Cribbage Counter started on http://localhost:" + port);
    }

    private boolean isLoggedIn(Context ctx) {
        return ctx.sessionAttribute(SESSION_USER_ID) != null && ctx.sessionAttribute(SESSION_USERNAME) != null;
    }

    private UUID currentUserId(Context ctx) {
        return UUID.fromString(ctx.sessionAttribute(SESSION_USER_ID));
    }

    private String currentUsername(Context ctx) {
        return ctx.sessionAttribute(SESSION_USERNAME);
    }

    private void setSession(Context ctx, UUID id, String username) {
        ctx.sessionAttribute(SESSION_USER_ID, id.toString());
        ctx.sessionAttribute(SESSION_USERNAME, username);
    }

    private String normalizeUsername(String raw) {
        if (raw == null) {
            return null;
        }
        String username = raw.trim().toLowerCase(Locale.ROOT);
        return username.isBlank() ? null : username;
    }

    private String dashboardHtml(String username, List<LocalStore.GameSummary> games,
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

        return """
            <h1>Dashboard</h1>
            <p>Logged in as <strong>%s</strong></p>
            <form method="post" action="/logout"><button type="submit">Logout</button></form>
            <h2>Create Game</h2>
            %s
            <form method="post" action="/games">
                <label>Players (comma separated usernames, 2-4 total):</label><br>
                <input name="players" placeholder="alice,bob,charlie" style="width:320px" />
                <button type="submit">Create Game</button>
            </form>
            <h2>Leaderboard (Local Wins/Losses)</h2>
            %s
            <h2>Your Games</h2>
            %s
            """.formatted(escapeHtml(username), errorHtml, stats, gameList);
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

        return """
            <p><a href="/dashboard">Back to dashboard</a></p>
            <h1>Game %s</h1>
            <p>Created: %s | Status: %s</p>
            <h2>Scoreboard</h2>
            %s
            <h2>Add Score</h2>
            %s
            <form method="post" action="/games/%s/scores">
                <label>Player:</label>
                <select name="playerUsername">%s</select><br>
                <label>Points:</label>
                <input name="points" type="number" value="0" /><br>
                <label>Note:</label>
                <input name="note" placeholder="hand, crib, pegging..." /><br>
                <button type="submit">Add Score</button>
            </form>
            <h2>Finish Game</h2>
            <form method="post" action="/games/%s/finish">
                <label>Winner:</label>
                <select name="winnerUsername">%s</select>
                <button type="submit">Finish Game</button>
            </form>
            <h2>Recent Score Events</h2>
            %s
            """.formatted(view.id(), view.createdAt(), escapeHtml(view.status()), rows, errorHtml, view.id(), options, view.id(), options, events);
    }

    private String registerForm(String error) {
        String errorHtml = error == null ? "" : "<p style='color:#b00020;'>" + escapeHtml(error) + "</p>";
        return """
            <h1>Create Account</h1>
            %s
            <form method="post" action="/register">
                <label>Username:</label><br>
                <input name="username" required /><br>
                <label>Password (8+ chars):</label><br>
                <input name="password" type="password" required /><br><br>
                <button type="submit">Register</button>
            </form>
            <p>Already have an account? <a href="/login">Login</a></p>
            """.formatted(errorHtml);
    }

    private String loginForm(String error) {
        String errorHtml = error == null ? "" : "<p style='color:#b00020;'>" + escapeHtml(error) + "</p>";
        return """
            <h1>Login</h1>
            %s
            <form method="post" action="/login">
                <label>Username:</label><br>
                <input name="username" required /><br>
                <label>Password:</label><br>
                <input name="password" type="password" required /><br><br>
                <button type="submit">Login</button>
            </form>
            <p>Need an account? <a href="/register">Register</a></p>
            """.formatted(errorHtml);
    }

    private String layout(String title, String body) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; max-width: 860px; margin: 24px auto; padding: 0 16px; }
                    input, select, button { margin-top: 6px; margin-bottom: 10px; }
                    a { color: #0057d8; text-decoration: none; }
                    a:hover { text-decoration: underline; }
                </style>
            </head>
            <body>
                %s
            </body>
            </html>
            """.formatted(escapeHtml(title), body);
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

