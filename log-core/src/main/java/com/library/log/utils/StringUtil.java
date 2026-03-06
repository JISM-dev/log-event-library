package com.library.log.utils;

public final class StringUtil {

    private StringUtil() {
    }

    public static String toStringOrNull(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
