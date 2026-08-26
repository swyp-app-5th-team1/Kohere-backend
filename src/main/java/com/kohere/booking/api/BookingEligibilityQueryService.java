package com.kohere.booking.api;

/**
 * 다른 모듈이 입주 신청 버튼을 표시하기 전에 현재 신청 가능 여부를 물어보는 공개 조회 계약이다.
 *
 * <p>신청 가능 여부는 화면에서 만든 값이 아니라 서버의 기존 신청 기록, 차단 관계, 현재 매물 상태를 기준으로 계산한다. 이 조회 결과는 배너 노출을 위한 사전 판단이며,
 * 실제 신청 요청에서는 날짜와 방 상품을 포함한 전체 규칙을 다시 검증한다.
 */
public interface BookingEligibilityQueryService {

  /**
   * 임차인이 해당 매물에 지금 입주 신청할 수 있는지 확인한다.
   *
   * @param tenantId 신청하려는 임차인의 {@code users.id}
   * @param listingId 채팅방이 가리키는 매물 ID
   * @param landlordId 해당 매물 채팅방의 임대인 {@code users.id}
   * @return 기존 신청이 없고, 차단 관계가 없으며, 공개 매물에 활성 방 상품이 있으면 {@code true}
   */
  boolean canApply(long tenantId, String listingId, long landlordId);
}
