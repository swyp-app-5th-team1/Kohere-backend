package com.kohere.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 표준 에러 코드 카탈로그. 코드 식별자({@link #getCode()})는 enum 상수명(UPPER_SNAKE_CASE)과 같다.
 *
 * <p>새 에러는 상수 추가로 등록하고 docs/api/error-response-guide.md §4 카탈로그에 누적한다. status 매핑은 §3을 따른다. 도메인 코드는
 * 모듈별 prefix(AUTH/USER/DIAGNOSIS/LISTING/BOOKING/CHAT/POST/COMMENT/QUIZ/REPORT)를 쓴다.
 */
public enum ErrorCode {

  // --- 공통 (common) ---
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 본문을 해석할 수 없습니다."),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
  TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 메서드입니다."),
  TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다."),
  UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "외부 연동에 실패했습니다."),
  SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다."),

  // --- 인증·회원 (auth/user) — docs/api/specs/01-auth-onboarding.md ---
  AUTH_MISSING_CREDENTIAL(HttpStatus.BAD_REQUEST, "소셜 로그인에 필요한 자격 정보가 누락되었습니다."),
  AUTH_INVALID_SOCIAL_TOKEN(HttpStatus.UNAUTHORIZED, "소셜 토큰 검증에 실패했습니다."),
  AUTH_REQUIRED_AGREEMENT_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, "필수 약관에 동의해야 합니다."),
  AUTH_TERMS_AGREEMENT_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "약관 동의가 선행되어야 합니다."),
  AUTH_EMAIL_VERIFICATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "이메일 인증번호가 올바르지 않거나 만료되었습니다."),
  AUTH_EMAIL_NOT_VERIFIED(HttpStatus.UNPROCESSABLE_ENTITY, "이메일 인증이 필요합니다."),
  AUTH_PHONE_VERIFICATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "연락처 인증번호가 올바르지 않거나 만료되었습니다."),
  AUTH_PHONE_NOT_VERIFIED(HttpStatus.UNPROCESSABLE_ENTITY, "연락처 인증이 필요합니다."),
  AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED(
      HttpStatus.UNPROCESSABLE_ENTITY, "사업자등록번호가 미등록·휴폐업이거나 검증에 실패했습니다."),
  AUTH_ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN, "온보딩을 완료해야 합니다."),
  AUTH_ONBOARDING_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 온보딩을 완료했습니다."),
  AUTH_INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh 토큰입니다."),
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
  USER_ALREADY_WITHDRAWN(HttpStatus.CONFLICT, "이미 탈퇴한 사용자입니다."),

  // --- 진단 (diagnosis) — docs/api/specs/02-diagnosis-recommendation.md ---
  DIAGNOSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "진단을 찾을 수 없습니다."),
  DIAGNOSIS_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "동일 키로 다른 요청이 접수되었습니다."),

  // --- 매물 (listing) — docs/api/specs/03-listings-favorites.md ---
  LISTING_NOT_FOUND(HttpStatus.NOT_FOUND, "매물을 찾을 수 없습니다."),
  LISTING_INVALID_SORT_PARAM(HttpStatus.BAD_REQUEST, "정렬 파라미터가 올바르지 않습니다."),
  LISTING_INVALID_BBOX(HttpStatus.BAD_REQUEST, "지도 좌표 범위가 올바르지 않습니다."),
  LISTING_AREA_TOO_LARGE(HttpStatus.BAD_REQUEST, "검색 범위가 너무 넓습니다."),

  // --- 신청·채팅 (booking/chat) — docs/api/specs/04-booking-inquiry-chat.md ---
  BOOKING_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 신청한 매물입니다."),
  BOOKING_INVALID_MOVE_IN_DATE(HttpStatus.UNPROCESSABLE_ENTITY, "입주 희망일이 올바르지 않습니다."),
  BOOKING_SELF_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "본인 소유 매물에는 신청할 수 없습니다."),
  CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
  CHAT_SELF_INQUIRY_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "본인 소유 매물에는 문의할 수 없습니다."),
  CHAT_ROOM_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "비활성 채팅방입니다."),
  CHAT_MESSAGE_NOT_IN_ROOM(HttpStatus.UNPROCESSABLE_ENTITY, "해당 방의 메시지가 아닙니다."),

  // --- 커뮤니티 (community) — docs/api/specs/05-community.md ---
  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
  COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
  POST_CHAT_SELF_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "본인 게시글에는 채팅을 시작할 수 없습니다."),
  POST_CHAT_AUTHOR_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "작성자와 채팅할 수 없습니다."),
  POST_CHAT_BLOCKED(HttpStatus.FORBIDDEN, "차단 관계로 채팅을 시작할 수 없습니다."),

  // --- 게이미피케이션 (gamification) — docs/api/specs/06-gamification.md ---
  QUIZ_NOT_FOUND(HttpStatus.NOT_FOUND, "퀴즈를 찾을 수 없습니다."),

  // --- 신고 (report) — docs/api/specs/07-reports.md ---
  REPORT_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "신고 대상을 찾을 수 없습니다."),
  REPORT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 신고한 대상입니다."),
  REPORT_SELF_TARGET(HttpStatus.UNPROCESSABLE_ENTITY, "본인 콘텐츠는 신고할 수 없습니다.");

  private final HttpStatus httpStatus;
  private final String defaultMessage;

  ErrorCode(HttpStatus httpStatus, String defaultMessage) {
    this.httpStatus = httpStatus;
    this.defaultMessage = defaultMessage;
  }

  /** 클라이언트 분기 기준이 되는 코드 문자열. enum 상수명과 동일하다. */
  public String getCode() {
    return name();
  }

  public HttpStatus getHttpStatus() {
    return httpStatus;
  }

  public String getDefaultMessage() {
    return defaultMessage;
  }
}
