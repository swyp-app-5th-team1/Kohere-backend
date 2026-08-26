package com.kohere.booking.application;

import com.kohere.booking.api.BookingEligibilityQueryService;
import com.kohere.booking.domain.BookingRepository;
import com.kohere.listing.api.BookingListingQueryService;
import com.kohere.user.api.UserBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방의 신청 배너에 사용할 입주 신청 가능 여부를 계산한다.
 *
 * <p>기존 신청을 사용자가 목록에서 숨겼더라도 신청 자체가 취소된 것은 아니므로 기존 신청으로 센다. 신청 가능 여부는 매번 현재 매물 상태를 조회하므로, 채팅방의 매물
 * 제목은 계속 보여도 매물이 비공개되거나 활성 방 상품이 사라지면 배너는 표시하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class BookingEligibilityQueryServiceImpl implements BookingEligibilityQueryService {

  private final BookingRepository bookingRepository;
  private final BookingListingQueryService listingQueryService;
  private final UserBlockService userBlockService;

  /** 신청 기록, 차단 관계, 현재 매물 상태를 차례로 확인해 불필요한 외부 저장소 조회를 줄인다. */
  @Override
  @Transactional(readOnly = true)
  public boolean canApply(long tenantId, String listingId, long landlordId) {
    // 목록에서 숨긴 신청도 DB에는 남아 있으므로 deleted_at 필터가 없는 존재 여부 조회를 사용한다.
    if (bookingRepository.existsByTenantIdAndListingId(tenantId, listingId)) {
      return false;
    }

    // 어느 방향이든 차단 관계가 있으면 실제 신청 API도 거부하므로 배너 역시 표시하지 않는다.
    if (userBlockService.isBlockedBetween(tenantId, landlordId)) {
      return false;
    }

    // 신청 시점의 정본은 현재 공개 매물과 활성 방 상품이므로 채팅방 snapshot만으로 판단하지 않는다.
    return listingQueryService.hasPublishedActiveRoomOffer(listingId);
  }
}
