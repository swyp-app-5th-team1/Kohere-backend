package com.kohere.chat.application.dto;

import com.kohere.chat.domain.MessageType;
import java.time.Instant;
import java.util.UUID;

/**
 * REST 메시지 이력 한 건이다.
 *
 * <p>TEXT는 원문을 항상 보존하고 로그인 사용자를 위한 번역본을 선택적으로 함께 반환한다. 번역이 실패하거나 아직 없더라도 원문 메시지를 잃지 않게 두 값을 분리한다.
 * 반면 {@link MessageType#INQUIRY_CARD}와 {@link MessageType#BOOKING_CARD}는 검증된 서버 흐름만 만들 수 있으며 텍스트·번역
 * 필드를 사용하지 않는다.
 */
public record MessageResponse(
    /** MySQL이 발급한 최종 메시지 식별자. REST·STOMP 중복 병합의 최종 기준이다. */
    Long messageId,
    /** TEXT 전송 직전에 프런트엔드가 만든 UUID. 네트워크 재시도에는 같은 값을 다시 보내 중복 저장을 막는다. 서버 생성 카드에는 null이다. */
    UUID clientMessageId,
    /** 메시지가 속한 채팅방 식별자. */
    Long chatRoomId,
    /** TEXT 발신 사용자 ID. 서버 생성 카드에는 null이다. */
    Long senderId,
    /** 현재 로그인 사용자가 보낸 TEXT인지 여부. 서버 생성 카드는 false다. */
    boolean mine,
    /** TEXT·INQUIRY_CARD·BOOKING_CARD를 구분한다. */
    MessageType type,
    /** 수정하지 않은 TEXT 원문. 카드 메시지에는 null이다. */
    String originalContent,
    /** 서버가 신청 정보를 검증해 만든 신청 카드 payload. 다른 타입에는 null이다. */
    BookingCardResponse bookingCard,
    /** 서버가 공개 매물 정보로 만든 문의서 payload. 다른 타입에는 null이다. */
    InquiryCardResponse inquiryCard,
    /** 현재 사용자의 표시 언어에 맞춘 번역 결과. 원문과 별도로 저장되며 없으면 null이다. */
    MessageTranslationResponse translation,
    /** 원문 또는 카드가 MySQL에 저장된 시각. */
    Instant sentAt) {}
