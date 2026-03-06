package com.library.log.exception;

/**
 * 로그 라이브러리 전용 예외 타입.
 *
 * <p>락 라이브러리와 동일하게 라이브러리 내부 코드/메시지 체계를 별도로 유지해,
 * 소비 애플리케이션이 전역 핸들러에서 일관된 형식으로 응답할 수 있도록 한다.</p>
 */
public enum LogExceptionType {

    /**
     * 시스템 시간이 역행해 Snowflake ID 발급이 불가능한 경우.
     */
    LOG_ID_CLOCK_BACKWARD(21000, "로그 ID 생성 중 시간이 역행했어요."),
    /**
     * 로그 payload 직렬화 실패.
     */
    LOG_PAYLOAD_SERIALIZE_FAILED(21001, "로그 payload 직렬화에 실패했어요."),
    /**
     * 로그 payload 역직렬화 실패.
     */
    LOG_PAYLOAD_DESERIALIZE_FAILED(21002, "로그 payload 역직렬화에 실패했어요.");

    private final int code;
    private final String errorMessage;

    LogExceptionType(int code, String errorMessage) {
        this.code = code;
        this.errorMessage = errorMessage;
    }

    /**
     * 클라이언트 응답용 에러 코드.
     *
     * @return 에러 코드
     */
    public int getCode() {
        return code;
    }

    /**
     * 클라이언트 응답용 에러 메시지.
     *
     * @return 에러 메시지
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
