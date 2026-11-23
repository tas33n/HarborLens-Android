package com.harborlens.app;

import java.util.Locale;
import java.util.regex.Pattern;

final class ContainerNumber {
    static final Pattern CANDIDATE = Pattern.compile("(?<![A-Z0-9])[A-Z]{3}U[ -]*[0-9](?:[ -]*[0-9]){6}(?![A-Z0-9])");
    private static final Pattern FORMAT = Pattern.compile("[A-Z]{3}U[0-9]{7}");
    private static final String VALUES = "0123456789A BCDEFGHIJK LMNOPQRSTU VWXYZ";

    static String normalize(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
    }

    static boolean valid(String value) {
        if (!FORMAT.matcher(value).matches()) return false;
        int sum = 0;
        for (int i = 0; i < 10; i++) sum += VALUES.indexOf(value.charAt(i)) << i;
        return sum % 11 % 10 == value.charAt(10) - '0';
    }
}
