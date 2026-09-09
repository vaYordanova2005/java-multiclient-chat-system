package com.messenger.backend.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernameValidatorTest {

    @Test
    void acceptsPlainAlphanumericWithinLengthBounds() {
        assertTrue(UsernameValidator.isValid("alice"));
        assertTrue(UsernameValidator.isValid("Bob123"));
        assertTrue(UsernameValidator.isValid("a".repeat(30)));
        assertTrue(UsernameValidator.isValid("abc")); // минимум 3
    }

    @Test
    void rejectsNullOrOutOfBoundsLength() {
        assertFalse(UsernameValidator.isValid(null));
        assertFalse(UsernameValidator.isValid("ab"));           // под 3
        assertFalse(UsernameValidator.isValid("a".repeat(31))); // над 30
        assertFalse(UsernameValidator.isValid(""));
    }

    // Pre-auth протоколът е "|"-делимитиран, а DM room-имената се парсват с
    // "_" — тия два символа МУСИ да са забранени в username, иначе чупят и
    // двата парсъра (виж ClientHandler.handlePreAuthMessage / room.split("_", 3)).
    @Test
    void rejectsProtocolDelimiterCharacters() {
        assertFalse(UsernameValidator.isValid("al|ice"));
        assertFalse(UsernameValidator.isValid("al_ice"));
        assertFalse(UsernameValidator.isValid("alice "));
        assertFalse(UsernameValidator.isValid(" alice"));
        assertFalse(UsernameValidator.isValid("ali$ce"));
    }
}
