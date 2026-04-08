package com.cribbagecounter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalStoreTest {

    @Test
    void finishGameUpdatesWinsAndLosses() throws Exception {
        Path tempFile = Files.createTempFile("cribbage-store", ".ser");
        try {
            LocalStore store = new LocalStore(tempFile);

            UUID gameId = store.createGame("alice", "bob");
            store.finishGame(gameId, "alice");

            List<LocalStore.UserStats> stats = store.leaderboard();
            LocalStore.UserStats alice = stats.stream().filter(s -> s.username().equals("alice")).findFirst().orElseThrow();
            LocalStore.UserStats bob = stats.stream().filter(s -> s.username().equals("bob")).findFirst().orElseThrow();

            assertEquals(1, alice.wins());
            assertEquals(0, alice.losses());
            assertEquals(0, bob.wins());
            assertEquals(1, bob.losses());
            assertEquals("finished", store.getGameView(gameId).status());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
