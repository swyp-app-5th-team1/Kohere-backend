package com.kohere.chat.application.dto;

import com.kohere.chat.domain.MessageType;
import java.time.Instant;

/**
 * 채팅방 목록 한 줄에 표시하는 마지막 메시지 요약이다.
 *
 * <p>빈 채팅방에는 억지로 빈 문자열을 만들지 않고 이 객체 자체를 {@code null}로 반환한다. TEXT 미리보기는 요청자에게 저장된 번역본이 있으면 번역본을, 없으면
 * 원문을 사용하므로 목록도 메시지 이력과 같은 언어 표시 규칙을 따른다.
 */
public record ChatLastMessageResponse(
    /** 미리보기의 근거가 된 서버 메시지 식별자. */
    Long messageId,
    /** TEXT와 서버 생성 BOOKING_CARD를 구분하는 메시지 종류. */
    MessageType type,
    /** 목록 표시용 짧은 문자열. 전체 원문을 대신하는 저장 필드가 아니다. */
    String preview,
    /** 해당 메시지가 서버에 저장된 시각. */
    Instant sentAt) {}
