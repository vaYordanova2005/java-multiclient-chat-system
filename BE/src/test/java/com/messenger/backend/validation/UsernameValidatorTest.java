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
        assertTrue(UsernameValidator.isValid("abc")); // minimum 3
    }

    @Test
    void rejectsNullOrOutOfBoundsLength() {
        assertFalse(UsernameValidator.isValid(null));
        assertFalse(UsernameValidator.isValid("ab"));           // below 3
        assertFalse(UsernameValidator.isValid("a".repeat(31))); // above 30
        assertFalse(UsernameValidator.isValid(""));
    }

    // The pre-auth protocol was "|"-delimited, and DM room names are parsed with
    // "_" — these two characters MUST be forbidden in a username, otherwise they break
    // both parsers (see ClientHandler.handlePreAuthMessage / room.split("_", 3)).
    @Test
    void rejectsProtocolDelimiterCharacters() {
        assertFalse(UsernameValidator.isValid("al|ice"));
        assertFalse(UsernameValidator.isValid("al_ice"));
        assertFalse(UsernameValidator.isValid("alice "));
        assertFalse(UsernameValidator.isValid(" alice"));
        assertFalse(UsernameValidator.isValid("ali$ce"));
    }
}
