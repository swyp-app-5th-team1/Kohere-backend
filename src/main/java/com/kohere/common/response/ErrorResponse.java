package com.kohere.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 에러 응답 상세. {@code code}로 클라이언트가 분기하고 {@code errors}에 입력 검증 실패 필드를 담는다.
 *
 * <p>{@code details}는 {@code errors[]}의 틀에 들어가지 않는 <b>코드별 부가 데이터</b>다 — {@code errors[]}가 「어느 요청
 * 필드가 왜 거절됐는가」로 용도가 고정된 반면, 이쪽은 그 형태가 아닌 값을 담는다(첫 사용처는 웹 로그인 실패의 누적 실패 횟수·잠금 상한이다). 어떤 키가 실리는지는 각
 * API 스펙이 정하며, <b>스펙에 적히지 않은 데이터를 임의로 싣지 않는다</b>. 클라이언트 분기는 여전히 {@code code}로 하고 {@code details}는
 * 표시용이다.
 *
 * <p><b>{@code details}에만 {@code @JsonInclude}를 건다.</b> 클래스 레벨에 걸면 나중에 {@code errors}가 nullable이 되는
 * 날 그것도 함께 사라진다 — 이 필드 하나만 영향받는 것이 보장돼야 기존 에러 응답의 외형이 바뀌지 않는다.
 *
 * <p><b>도메인 타입을 두지 않는다.</b> 여기는 공유 커널이라 특정 모듈의 어휘(예: {@code failedAttempts})를 필드로 새기면 모듈 경계가 깨진다.
 *
 * <p>docs/api/error-response-guide.md §1 · ADR-0004 Amended.
 */
public record ErrorResponse(
    String code,
    String message,
    List<FieldErrorDetail> errors,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> details) {}
