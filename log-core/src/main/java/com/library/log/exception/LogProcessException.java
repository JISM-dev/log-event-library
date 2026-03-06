package com.library.log.exception;

/**
 * 로그 라이브러리 처리 과정에서 발생하는 도메인 예외.
 *
 * <p>{@link LogExceptionType}를 함께 보관해 소비 애플리케이션의 전역 핸들러에서
 * code/message/type을 안정적으로 응답할 수 있게 한다.</p>
 */
public class LogProcessException extends RuntimeException {

    /**
     * 로그 실패 세부 유형.
     */
    private final LogExceptionType type;
    /**
     * 클라이언트 응답용 에러 코드.
     */
    private final int code;
    /**
     * 클라이언트 응답용 에러 메시지.
     */
    private final String errorMessage;

    /**
     * 예외 타입 기반으로 로그 처리 예외를 생성한다.
     *
     * @param type 로그 실패 원인
     */
    public LogProcessException(LogExceptionType type) {
        super(type.getErrorMessage());
        this.type = type;
        this.code = type.getCode();
        this.errorMessage = type.getErrorMessage();
    }

    /**
     * 로그 실패 세부 유형을 반환한다.
     *
     * @return {@link LogExceptionType}
     */
    public LogExceptionType getType() {
        return type;
    }

    /**
     * API 응답용 에러 코드를 반환한다.
     *
     * @return 에러 코드
     */
    public int getCode() {
        return code;
    }

    /**
     * API 응답용 에러 메시지를 반환한다.
     *
     * @return 에러 메시지
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
