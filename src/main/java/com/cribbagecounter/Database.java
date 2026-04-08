package com.cribbagecounter;

/**
 * Legacy placeholder kept to avoid breaking references in old branches.
 * The app now uses LocalStore for persistence.
 */
@Deprecated
public class Database {
    public Database() {
        throw new UnsupportedOperationException("Database is no longer used. Use LocalStore instead.");
    }
}
