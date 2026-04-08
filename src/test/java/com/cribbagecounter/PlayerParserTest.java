package com.cribbagecounter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerParserTest {

    @Test
    void parseUsernamesTrimsLowercasesAndDeduplicates() {
        List<String> players = PlayerParser.parseUsernames(" Alice, bob,alice,  CHARLIE ");
        assertEquals(List.of("alice", "bob", "charlie"), players);
    }

    @Test
    void parseUsernamesHandlesBlankInput() {
        List<String> players = PlayerParser.parseUsernames("  ");
        assertEquals(List.of(), players);
    }
}

