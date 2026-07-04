package com.kohere.listing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.FavoriteRepository;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.RecentListingRepository;
import com.kohere.listing.domain.SearchPlaceRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ListingService} 단위 테스트.
 *
 * <p>MongoDB 통합 테스트에서는 실제 저장·조회·정렬을 검증하고, 이 테스트에서는 저장소 예외가 발생했을 때 응용 계층이 어떤 계약을 지키는지 확인한다. 최근 본 기록은
 * 상세 조회의 부가 기능이므로 저장이나 정리 실패가 사용자에게 보여줄 상세 응답을 실패시키면 안 된다.
 */
@ExtendWith(MockitoExtension.class)
class ListingServiceTest {

  private static final String LISTING_ID = "6858e2000000000000000002";
  private static final String ROOM_OFFER_ID = "6858e2000000000000000102";

  @Mock private ListingRepository listingRepository;
  @Mock private FavoriteRepository favoriteRepository;
  @Mock private RecentListingRepository recentListingRepository;
  @Mock private SearchPlaceRepository searchPlaceRepository;

  private ListingService listingService;

  @BeforeEach
  void setUp() {
    listingService =
        new ListingService(
            listingRepository, favoriteRepository, recentListingRepository, searchPlaceRepository);
  }

  /** 최근 본 저장이 실패해도 상세 조회 응답은 정상 반환하고, 이후 정리 작업은 시도하지 않는다. */
  @Test
  void getListing_최근본_저장_실패는_상세조회를_실패시키지_않는다() {
    Listing listing = sampleListing();
    when(listingRepository.findById(LISTING_ID)).thenReturn(Optional.of(listing));
    when(favoriteRepository.findByUserIdAndListingId(1L, LISTING_ID)).thenReturn(Optional.empty());
    doThrow(new RuntimeException("recent upsert failed"))
        .when(recentListingRepository)
        .upsertViewedAt(eq(1L), eq(LISTING_ID), any(Instant.class));

    ListingDetailResponse response = listingService.getListing(1L, LISTING_ID);

    assertThat(response.listingId()).isEqualTo(LISTING_ID);
    assertThat(response.interaction().favorited()).isFalse();
    assertThat(response.interaction().favoriteCount()).isEqualTo(3);
    verify(recentListingRepository, never()).deleteOldByUserIdKeepingLatest(1L, 30);
  }

  /** 최근 본 저장은 성공했지만 오래된 기록 정리가 실패해도 상세 조회 응답은 정상 반환한다. */
  @Test
  void getListing_최근본_정리_실패는_상세조회를_실패시키지_않는다() {
    Listing listing = sampleListing();
    when(listingRepository.findById(LISTING_ID)).thenReturn(Optional.of(listing));
    when(favoriteRepository.findByUserIdAndListingId(1L, LISTING_ID)).thenReturn(Optional.empty());
    doThrow(new RuntimeException("recent cleanup failed"))
        .when(recentListingRepository)
        .deleteOldByUserIdKeepingLatest(1L, 30);

    ListingDetailResponse response = listingService.getListing(1L, LISTING_ID);

    assertThat(response.listingId()).isEqualTo(LISTING_ID);
    assertThat(response.interaction().favoriteCount()).isEqualTo(3);
    verify(recentListingRepository).upsertViewedAt(eq(1L), eq(LISTING_ID), any(Instant.class));
  }

  /** 테스트에서 사용할 공개 매물 도메인 객체다. */
  private static Listing sampleListing() {
    return Listing.builder()
        .id(LISTING_ID)
        .schemaVersion(1)
        .landlordId(1L)
        .title("테스트 고시원")
        .type(ListingType.GOSIWON)
        .status(Listing.ListingStatus.PUBLISHED)
        .location(new Listing.GeoPoint(126.951422, 37.459471))
        .address(new Listing.Address("SEOUL", "GWANAK_GU", "서울특별시 관악구 테스트로 1", null))
        .nearestTransit(new Listing.NearestTransit(Listing.TransitType.SUBWAY, "서울대입구역", 5))
        .nearbyPlacesDescription("편의점")
        .nearbyUniversityCodes(Set.of("SNU"))
        .building(
            new Listing.Building(
                Listing.BuildingType.VILLA, 1, 2, 4, true, true, Listing.HeatingSystem.CENTRAL))
        .propertyPolicies(new Listing.PropertyPolicies(false, true, true, true, false))
        .facilities(
            new Listing.Facilities(
                Set.of("COIN_LAUNDRY"),
                Set.of("WIFI"),
                Set.of("CCTV"),
                List.of(new Listing.CommonSpace(Listing.CommonSpaceType.SHARED_TOILET, 2)),
                Set.of("BEDDING")))
        .roomOffers(List.of(sampleRoomOffer()))
        .featureSummary(Set.of(ConditionTag.FEMALE_ONLY))
        .descriptions(new Listing.Descriptions("테스트 설명", "Test description"))
        .extraNotes("테스트")
        .imageUrls(List.of())
        .favoriteCount(3)
        .createdAt(Instant.parse("2026-06-24T00:00:00Z"))
        .updatedAt(Instant.parse("2026-06-24T00:00:00Z"))
        .build();
  }

  /** 상세 응답과 최근 본 카드 집계에 필요한 최소 방 상품이다. */
  private static Listing.RoomOffer sampleRoomOffer() {
    return new Listing.RoomOffer(
        ROOM_OFFER_ID,
        "스탠다드 1인실",
        Listing.RoomOfferStatus.ACTIVE,
        Listing.RentalType.MONTHLY_RENT,
        new Listing.Pricing(300000, 300000, 0, Listing.Currency.KRW),
        new Listing.Contract(
            2,
            6,
            new Listing.RefundPolicy(
                Listing.RefundPolicyCode.FULL_REFUND_BEFORE_7_DAYS, "입주 7일 전 전액 환불")),
        new Listing.Inventory(1, 1, LocalDate.parse("2026-07-01")),
        Listing.GenderPolicy.FEMALE_ONLY,
        Set.of(Listing.RoomFeature.SINGLE_ROOM),
        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.RESIDENT_REGISTRATION),
        List.of());
  }
}
