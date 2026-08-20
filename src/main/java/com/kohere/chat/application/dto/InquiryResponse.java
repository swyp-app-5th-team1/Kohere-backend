package com.kohere.chat.application.dto;

/**
 * 매물 문의로 보장된 채팅방을 알려 주는 결과다.
 *
 * <p>매물 ID는 요청 경로에 이미 있고 상대 정보는 채팅방 상세 조회에서 제공하므로 중복 반환하지 않는다. 같은 참여자·매물 조합으로 여러 번 호출해도 같은 방을 반환하며,
 * {@code created}는 HTTP status를 201 또는 200으로 고르는 데만 사용한다.
 *
 * <p>계약: docs/architecture/chat/02-api-contracts.md §5.1.
 */
public record InquiryResponse(
    /** 조회했거나 새로 만든 채팅방의 서버 식별자. */
    Long chatRoomId,
    /** 이번 요청이 방을 새로 만들었으면 true, 이미 있던 방을 반환했으면 false. */
    boolean created) {}
