package com.messenger.backend.validation;

import java.util.regex.Pattern;

// Centralized whitelist check for usernames (registration AND username
// change). Auth is now REST/JSON (AuthController), not a "|"-delimited
// socket protocol, but DM rooms after auth are STILL parsed as
// "dm_userA_userB" (room.split("_", 3)) — a username containing "_" would break
// that parser. An alnum-only whitelist eliminates the problem entirely, instead of
// trying to escape it everywhere usernames travel through the protocol.
public final class UsernameValidator {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9]{3,30}$");

    private UsernameValidator() {
    }

    public static boolean isValid(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }
}
