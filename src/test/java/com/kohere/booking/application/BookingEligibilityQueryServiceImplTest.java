package com.kohere.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.booking.domain.BookingRepository;
import com.kohere.listing.api.BookingListingQueryService;
import com.kohere.user.api.UserBlockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 채팅방 신청 배너에 사용하는 매물 단위 신청 가능 여부 규칙을 검증한다. */
@ExtendWith(MockitoExtension.class)
class BookingEligibilityQueryServiceImplTest {

  private static final long TENANT_ID = 7L;
  private static final long LANDLORD_ID = 42L;
  private static final String LISTING_ID = "6858e2000000000000000001";

  @Mock private BookingRepository bookingRepository;
  @Mock private BookingListingQueryService listingQueryService;
  @Mock private UserBlockService userBlockService;

  private BookingEligibilityQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new BookingEligibilityQueryServiceImpl(
            bookingRepository, listingQueryService, userBlockService);
  }

  @Test
  @DisplayName("신청 기록과 차단이 없고 공개 매물에 활성 방이 있으면 신청할 수 있다")
  void canApplyToAvailableListing() {
    given(bookingRepository.existsByTenantIdAndListingId(TENANT_ID, LISTING_ID)).willReturn(false);
    given(userBlockService.isBlockedBetween(TENANT_ID, LANDLORD_ID)).willReturn(false);
    given(listingQueryService.hasPublishedActiveRoomOffer(LISTING_ID)).willReturn(true);

    assertThat(service.canApply(TENANT_ID, LISTING_ID, LANDLORD_ID)).isTrue();
  }

  @Test
  @DisplayName("해당 매물에 신청한 기록이 있으면 배너를 표시하지 않는다")
  void cannotApplyWhenBookingAlreadyExistsForListing() {
    given(bookingRepository.existsByTenantIdAndListingId(TENANT_ID, LISTING_ID)).willReturn(true);

    assertThat(service.canApply(TENANT_ID, LISTING_ID, LANDLORD_ID)).isFalse();

    // 이미 신청했다면 뒤의 차단·MongoDB 매물 조회 결과와 관계없이 false다.
    verify(userBlockService, never()).isBlockedBetween(TENANT_ID, LANDLORD_ID);
    verify(listingQueryService, never()).hasPublishedActiveRoomOffer(LISTING_ID);
  }

  @Test
  @DisplayName("차단 관계가 있으면 신청할 수 없다")
  void cannotApplyWhenCounterpartIsBlocked() {
    given(bookingRepository.existsByTenantIdAndListingId(TENANT_ID, LISTING_ID)).willReturn(false);
    given(userBlockService.isBlockedBetween(TENANT_ID, LANDLORD_ID)).willReturn(true);

    assertThat(service.canApply(TENANT_ID, LISTING_ID, LANDLORD_ID)).isFalse();

    verify(listingQueryService, never()).hasPublishedActiveRoomOffer(LISTING_ID);
  }

  @Test
  @DisplayName("현재 공개 매물에 활성 방 상품이 없으면 신청할 수 없다")
  void cannotApplyWhenListingIsUnavailable() {
    given(bookingRepository.existsByTenantIdAndListingId(TENANT_ID, LISTING_ID)).willReturn(false);
    given(userBlockService.isBlockedBetween(TENANT_ID, LANDLORD_ID)).willReturn(false);
    given(listingQueryService.hasPublishedActiveRoomOffer(LISTING_ID)).willReturn(false);

    assertThat(service.canApply(TENANT_ID, LISTING_ID, LANDLORD_ID)).isFalse();
  }
}
