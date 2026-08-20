package com.kohere.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 표준 에러 코드 카탈로그. 코드 식별자({@link #getCode()})는 enum 상수명(UPPER_SNAKE_CASE)과 같다.
 *
 * <p>새 에러는 상수 추가로 등록하고 docs/api/error-response-guide.md §4 카탈로그에 누적한다. status 매핑은 §3을 따른다. 도메인 코드는
 * 모듈별 prefix(AUTH/USER/DIAGNOSIS/LISTING/BOOKING/CHAT/POST/COMMENT/QUIZ/REPORT/LIFE_TIP)를 쓴다.
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
  // 거의 동시에 도착한 다른 요청과 UNIQUE 제약에서 충돌했다 — 재시도가 유효한 복구다.
  // 도메인이 미리 판정할 수 있는 충돌은 각자의 코드(BOOKING_ALREADY_EXISTS 등)를 쓰고, 이 코드는
  // 애플리케이션 조회로 막을 수 없는 경합만 받는다. 전역 핸들러가 문서화된 UNIQUE 제약(V22·V23) 위반만
  // 골라 번역하며, 그 밖의 제약 위반(NOT NULL·길이 초과 등)은 서버 버그라 500 그대로다
  // (GlobalExceptionHandler#RETRYABLE_UNIQUE_CONSTRAINTS).
  RESOURCE_CONFLICT(HttpStatus.CONFLICT, "다른 요청과 충돌했습니다. 잠시 후 다시 시도해 주세요."),
  TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다."),
  PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "요청 크기가 허용 범위를 넘었습니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다."),
  UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "외부 연동에 실패했습니다."),
  SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다."),

  // --- 인증·회원 (auth/user) — docs/api/specs/01-auth-onboarding.md ---
  AUTH_MISSING_CREDENTIAL(HttpStatus.BAD_REQUEST, "소셜 로그인에 필요한 자격 정보가 누락되었습니다."),
  AUTH_INVALID_SOCIAL_TOKEN(HttpStatus.UNAUTHORIZED, "소셜 토큰 검증에 실패했습니다."),
  AUTH_EMAIL_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "이메일 정보가 필요합니다."),
  AUTH_EMAIL_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "요청 이메일이 소셜 계정 이메일과 일치하지 않습니다."),
  AUTH_REQUIRED_AGREEMENT_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, "필수 약관에 동의해야 합니다."),
  AUTH_TERMS_AGREEMENT_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "약관 동의가 선행되어야 합니다."),
  AUTH_EMAIL_VERIFICATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "이메일 인증번호가 올바르지 않거나 만료되었습니다."),
  AUTH_PHONE_VERIFICATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "연락처 인증번호가 올바르지 않거나 만료되었습니다."),
  AUTH_PHONE_NOT_VERIFIED(HttpStatus.UNPROCESSABLE_ENTITY, "연락처 인증이 필요합니다."),
  AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED(
      HttpStatus.UNPROCESSABLE_ENTITY, "사업자등록번호가 미등록·휴폐업이거나 검증에 실패했습니다."),
  AUTH_ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN, "온보딩을 완료해야 합니다."),
  AUTH_ONBOARDING_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 온보딩을 완료했습니다."),
  AUTH_INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh 토큰입니다."),
  // 임대인 웹 로컬 자격증명(ADR-0047). 로그인 실패는 이메일 미존재와 비밀번호 불일치를 한 코드로 묶는다(계정 존재 여부 비노출).
  AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
  AUTH_ACCOUNT_LOCKED(HttpStatus.LOCKED, "비밀번호를 여러 번 틀려 계정이 잠겼습니다."),
  AUTH_EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
  AUTH_WEB_ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 웹 계정이 존재합니다. 로그인해 주세요."),
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
  USER_ALREADY_WITHDRAWN(HttpStatus.CONFLICT, "이미 탈퇴한 사용자입니다."),

  // --- 진단 (diagnosis) — docs/api/specs/02-diagnosis-recommendation.md ---
  DIAGNOSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "진단을 찾을 수 없습니다."),
  DIAGNOSIS_SESSION_NOT_FOUND(HttpStatus.BAD_REQUEST, "진행 중인 진단이 없습니다. 진단을 다시 시작해 주세요."),

  // --- 매물 (listing) — docs/api/specs/03-listings-favorites.md ---
  LISTING_NOT_FOUND(HttpStatus.NOT_FOUND, "매물을 찾을 수 없습니다."),
  LISTING_INVALID_SORT_PARAM(HttpStatus.BAD_REQUEST, "정렬 파라미터가 올바르지 않습니다."),
  LISTING_INVALID_BBOX(HttpStatus.BAD_REQUEST, "지도 좌표 범위가 올바르지 않습니다."),
  LISTING_AREA_TOO_LARGE(HttpStatus.BAD_REQUEST, "검색 범위가 너무 넓습니다."),
  LISTING_UNKNOWN_CATALOG_CODE(HttpStatus.BAD_REQUEST, "지원하지 않는 코드가 포함되어 있습니다."),
  // 사진 4종은 위반 대상이 JSON 필드가 아니라 파일 part나 저장 키라 INVALID_INPUT의 errors[]에 담을 경로가 없다(ADR-0041).
  LISTING_IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "매물 사진 장수가 올바르지 않습니다."),
  LISTING_IMAGE_KEY_NOT_FOUND(HttpStatus.BAD_REQUEST, "사진을 찾을 수 없습니다. 다시 올려 주세요."),
  LISTING_IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "사진 한 장의 크기가 허용 범위를 넘었습니다."),
  LISTING_IMAGE_UNSUPPORTED_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 사진 형식입니다."),

  // --- 신청·채팅 (booking/chat) — docs/api/specs/04-booking-inquiry-chat.md ---
  BOOKING_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 신청한 매물입니다."),
  BOOKING_INVALID_MOVE_IN_DATE(HttpStatus.UNPROCESSABLE_ENTITY, "입주 희망일이 올바르지 않습니다."),
  BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
  CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
  CHAT_SELF_INQUIRY_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "본인 소유 매물에는 문의할 수 없습니다."),
  CHAT_UNAVAILABLE(HttpStatus.FORBIDDEN, "현재 상대방과 채팅을 시작할 수 없습니다."),
  CHAT_ROOM_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "비활성 채팅방입니다."),
  CHAT_MESSAGE_TOO_LONG(HttpStatus.BAD_REQUEST, "메시지는 3,000자 이하여야 합니다."),
  CHAT_CLIENT_MESSAGE_CONFLICT(HttpStatus.CONFLICT, "같은 메시지 ID를 다른 본문에 사용할 수 없습니다."),

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
  REPORT_SELF_TARGET(HttpStatus.UNPROCESSABLE_ENTITY, "본인 콘텐츠는 신고할 수 없습니다."),

  // --- 생활 팁 (lifetip) — docs/api/specs/08-life-tips.md ---
  LIFE_TIP_TOPIC_NOT_FOUND(HttpStatus.NOT_FOUND, "생활 팁 주제를 찾을 수 없습니다.");

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
