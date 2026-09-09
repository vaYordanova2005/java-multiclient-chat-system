package com.messenger.backend.validation;

import java.util.regex.Pattern;

// Централизирана whitelist проверка за username-и (регистрация И смяна на
// username). Пре-auth протоколът е "|"-делимитиран (AUTH_LOGIN|user|pass), а
// DM стаите се парсват като "dm_userA_userB" (room.split("_", 3)) — username
// съдържащ "|" или "_" би счупил и двата парсъра. Alnum-only whitelist
// елиминира проблема изцяло, вместо да се опитваме да escape-ваме навсякъде,
// където username-и пътуват по протокола.
public final class UsernameValidator {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9]{3,30}$");

    private UsernameValidator() {
    }

    public static boolean isValid(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }
}
