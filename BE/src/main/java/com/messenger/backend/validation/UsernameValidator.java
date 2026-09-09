package com.messenger.backend.validation;

import java.util.regex.Pattern;

// Централизирана whitelist проверка за username-и (регистрация И смяна на
// username). Auth вече е REST/JSON (AuthController), не "|"-делимитиран
// socket протокол, но DM стаите след auth-а СЕ ОЩЕ се парсват като
// "dm_userA_userB" (room.split("_", 3)) — username съдържащ "_" би счупил
// тоя парсър. Alnum-only whitelist елиминира проблема изцяло, вместо да се
// опитваме да escape-ваме навсякъде, където username-и пътуват по протокола.
public final class UsernameValidator {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9]{3,30}$");

    private UsernameValidator() {
    }

    public static boolean isValid(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }
}
