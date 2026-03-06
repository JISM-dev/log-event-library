package com.library.log.utils;

/**
 * 문자열 변환 보조 유틸리티.
 */
public final class StringUtil {

    /**
     * 유틸 클래스 생성 방지.
     */
    private StringUtil() {
    }

    /**
     * Long 값을 null-safe하게 문자열로 변환한다.
     *
     * @param value 변환 대상 값
     * @return value가 null이면 null, 아니면 String 값
     */
    public static String toStringOrNull(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
