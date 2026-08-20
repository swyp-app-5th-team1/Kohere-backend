package com.kohere.listing.api;

/**
 * chat 모듈이 문의 채팅방을 만들 때 사용하는 공개 매물 요약.
 *
 * <p>채팅방은 생성 당시의 화면 정보를 보존해야 하므로 제목·주소를 채팅방 사본으로 복사한다. 임대인 번호는 클라이언트 요청에서 받지 않고 매물의 실제 소유자에서 가져와 상대
 * 사용자를 결정한다. listing 내부 도메인 객체를 그대로 노출하지 않고 원시 값만 전달해 두 모듈의 저장 구조가 서로 결합되지 않게 한다.
 *
 * @param listingId 조회한 매물의 식별자
 * @param landlordId 매물 소유자인 임대인의 {@code users.id}
 * @param title 채팅방 목록과 헤더에 표시할 매물 제목 사본
 * @param address 채팅방 헤더에 표시할 주소 사본
 */
public record ChatListingView(String listingId, Long landlordId, String title, String address) {}
