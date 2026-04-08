# Cribbage Counter (Java, Local Storage)

A Java web app for tracking cribbage games locally with score history and exactly 2 players per game.

## Features

- No login required
- Local file persistence for games, score events, and player-name **wins/losses**
- Create new games with exactly **2 players**
- Live scoreboard updates by adding score events
- Finish a game and store winner/loser records locally

## Tech Stack

- Java 11
- Javalin (web server)
- Maven

## Setup

1. Copy `.env.example` to `.env`.
2. Optionally change `DATA_FILE` and `PORT`.

```bash
cp .env.example .env
```

Environment variables:

- `DATA_FILE` (optional, defaults to `data/local-store.ser`)
- `PORT` (optional, defaults to `7070`)

## Run

```bash
mvn test
mvn compile exec:java
```

Then open: `http://localhost:7070`

## Deploy (Render)

This repo now includes `render.yaml` and a `Dockerfile`.

1. Push this repo to GitHub.
2. In Render, create a **Blueprint** from the repo.
3. Render reads `render.yaml` and creates:
   - a web service
   - a persistent disk mounted at `/var/data`
4. Confirm env vars in Render:
   - `PORT=10000`
   - `DATA_FILE=/var/data/local-store.ser`

Your game/user stats persist on the disk across deploys and restarts.

## Build Artifact

`mvn package` creates a runnable fat jar at:

- `target/cribbage-counter.jar`

## Persistence

All data is stored on disk in one local file (`data/local-store.ser` by default):

- games
- score events
- player-name stats (wins/losses)

If you want a fresh start, stop the app and delete the data file.
