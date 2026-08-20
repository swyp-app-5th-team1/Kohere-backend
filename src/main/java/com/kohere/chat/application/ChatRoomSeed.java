package com.kohere.chat.application;

/**
 * 문의하기와 신청 이벤트가 같은 채팅방을 만들 때 공통으로 사용하는 최소 매물 정보다.
 *
 * <p>문의하기는 {@code listing :: api} 조회 결과에서 이 값을 만들고, 신청 처리는 {@code BookingCreatedEvent}에 저장된 신청 시점
 * 사본에서 만든다. 출처는 다르지만 네 값이 같으면 두 흐름 모두 동일한 {@code (listingId, tenantId, landlordId)} 방으로 수렴한다.
 *
 * @param listingId 대화 대상 매물 ID
 * @param landlordId 매물 소유자인 임대인의 users.id
 * @param title 채팅방 제목에 보존할 매물 제목
 * @param address 채팅방 헤더에 보존할 매물 주소
 */
public record ChatRoomSeed(String listingId, Long landlordId, String title, String address) {}
