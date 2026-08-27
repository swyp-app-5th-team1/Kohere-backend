package com.kohere.chat.presentation.stomp.dto;

import com.kohere.chat.application.dto.BookingCardResponse;
import com.kohere.chat.application.dto.InquiryCardResponse;
import com.kohere.chat.domain.MessageType;
import java.time.Instant;
import java.util.UUID;

/**
 * MySQL commit 뒤 방 topic으로 전달하는 원문 메시지 이벤트다.
 *
 * <p>TEXT의 번역본은 사용자마다 다르므로 공용 room topic에 섞지 않는다. 이 payload에는 동일한 원문만 싣고 번역 최종 결과는 수신자의 개인 queue로
 * 별도 전송한다. INQUIRY_CARD와 BOOKING_CARD는 프런트엔드 SEND가 아니라 서버가 문의·신청 흐름을 검증한 뒤 생성한다.
 */
public record ChatMessageCreatedPayload(
    /** payload 계약 버전. 현재 값은 1이다. */
    int version,
    /** 이 payload가 저장 완료 메시지임을 나타내는 MESSAGE_CREATED. */
    ChatStompEventType eventType,
    /** MySQL이 발급한 최종 메시지 식별자. */
    Long messageId,
    /** TEXT를 만든 프런트엔드 UUID. 서버 생성 카드에는 null이다. */
    UUID clientMessageId,
    /** 메시지가 속한 채팅방 식별자. */
    Long chatRoomId,
    /** TEXT 발신 사용자 ID. 서버 생성 카드에는 null이다. */
    Long senderId,
    /** TEXT·INQUIRY_CARD·BOOKING_CARD를 구분한다. */
    MessageType type,
    /** 모든 참여자에게 동일하게 전달하는 TEXT 원문. 서버 카드에는 null이다. */
    String originalContent,
    /** 서버가 신청 정보로 만든 신청 카드 payload. 다른 타입에는 null이다. */
    BookingCardResponse bookingCard,
    /** 서버가 공개 매물 정보로 만든 문의서 payload. 다른 타입에는 null이다. */
    InquiryCardResponse inquiryCard,
    /** 메시지가 MySQL에 저장된 서버 시각. */
    Instant sentAt) {}
