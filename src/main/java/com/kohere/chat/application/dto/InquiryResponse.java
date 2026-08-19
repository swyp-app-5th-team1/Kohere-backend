package com.kohere.chat.application.dto;

/**
 * 문의 결과 DTO. 매물 임대인과의 채팅방 번호와 이번 요청에서 새로 생성했는지를 반환한다. 매물 ID는 요청 경로에 이미 있고 상대 정보는 채팅방 상세 조회에서 제공하므로
 * 중복해서 반환하지 않는다.
 *
 * <p>{@code created}가 {@code true}면 신규 생성(201), {@code false}면 기존 방 반환(200)이다.
 * docs/architecture/chat/02-api-contracts.md §5.1.
 */
public record InquiryResponse(Long chatRoomId, boolean created) {}
