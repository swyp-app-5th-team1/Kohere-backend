package com.kohere.chat.application.dto;

import com.kohere.chat.domain.MessageType;
import java.time.Instant;

/**
 * 채팅방 목록 한 줄에 표시하는 마지막 메시지 요약이다.
 *
 * <p>빈 채팅방에는 억지로 빈 문자열을 만들지 않고 이 객체 자체를 {@code null}로 반환한다. 현재 목록 조회 단계의 TEXT 미리보기는 저장된 원문을 사용한다.
 * 자동 번역 단계에서 수신자용 번역 결과 저장소가 연결되면 번역 성공본을 우선하고 실패·미완료 시 같은 원문으로 폴백한다.
 */
public record ChatLastMessageResponse(
    /** 미리보기의 근거가 된 서버 메시지 식별자. */
    Long messageId,
    /** TEXT와 서버 생성 INQUIRY_CARD·BOOKING_CARD를 구분하는 메시지 종류. */
    MessageType type,
    /** 목록 표시용 문자열. 서버가 별도 축약본을 저장하지 않으므로 앱이 UI 폭에 맞게 한 줄 말줄임한다. */
    String preview,
    /** 해당 메시지가 서버에 저장된 시각. */
    Instant sentAt) {}
