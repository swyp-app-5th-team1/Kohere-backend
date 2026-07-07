package com.kohere.listing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.FavoriteRepository;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingSearchResult;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.RecentListingRepository;
import com.kohere.listing.domain.SearchPlaceRepository;
import com.kohere.listing.presentation.dto.ListingSearchRequest;
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
  private static final String SECOND_ROOM_OFFER_ID = "6858e2000000000000000103";
  private static final String INACTIVE_ROOM_OFFER_ID = "6858e2000000000000000104";

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
    assertThat(response.favorited()).isFalse();
    assertThat(response.favoriteCount()).isEqualTo(3);
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
    assertThat(response.favoriteCount()).isEqualTo(3);
    verify(recentListingRepository).upsertViewedAt(eq(1L), eq(LISTING_ID), any(Instant.class));
  }

  /** 상세 응답은 매물 공통 필드를 루트에 두고, UI에 노출 가능한 ACTIVE 방만 내려준다. */
  @Test
  void getListing_상세응답은_DB구조에_가깝게_루트필드와_ACTIVE_방을_반환한다() {
    Listing listing = sampleListing();
    when(listingRepository.findById(LISTING_ID)).thenReturn(Optional.of(listing));
    when(favoriteRepository.findByUserIdAndListingId(1L, LISTING_ID)).thenReturn(Optional.empty());

    ListingDetailResponse response = listingService.getListing(1L, LISTING_ID);

    assertThat(response.title()).isEqualTo("테스트 고시원");
    assertThat(response.rentalType()).isEqualTo(Listing.RentalType.MONTHLY_RENT);
    assertThat(response.contract().minStayMonths()).isEqualTo(1);
    assertThat(response.contract().maxStayMonths()).isEqualTo(12);
    assertThat(response.genderPolicy()).isEqualTo(Listing.GenderPolicy.FEMALE_ONLY);
    assertThat(response.propertyPolicies().arcRequired()).isFalse();
    assertThat(response.conditions())
        .containsExactlyInAnyOrder(
            ConditionTag.FEMALE_ONLY,
            ConditionTag.ADDRESS_REGISTRATION,
            ConditionTag.PRIVATE_BATH,
            ConditionTag.NO_MAINT_FEE,
            ConditionTag.NO_ARC);
    assertThat(response.conditions()).doesNotContain(ConditionTag.MOVE_IN_NOW);
    assertThat(response.facilities().heatingSystem())
        .containsExactly(Listing.HeatingSystem.CENTRAL);
    assertThat(response.imageUrls()).hasSize(2);
    assertThat(response.roomOffers())
        .extracting(ListingDetailResponse.RoomOfferResponse::roomOfferId)
        .containsExactly(ROOM_OFFER_ID, SECOND_ROOM_OFFER_ID);
    assertThat(response.roomOffers())
        .allSatisfy(
            roomOffer -> assertThat(roomOffer.status()).isEqualTo(Listing.RoomOfferStatus.ACTIVE));
  }

  /** 목록 카드의 conditions는 필터를 통과한 방만이 아니라 매물의 ACTIVE 방 전체 기준으로 계산한다. */
  @Test
  void getListings_conditions는_ACTIVE방_전체_합집합과_NO_ARC를_반환한다() {
    Listing listing = sampleListing();
    when(listingRepository.search(any()))
        .thenReturn(
            PageResponse.of(
                List.of(new ListingSearchResult(listing, List.of(sampleRoomOffer()))),
                new PageInfo(0, 20, 1, 1, false)));

    ListingSummaryResponse response =
        listingService.getListings(new ListingSearchRequest()).content().getFirst();

    assertThat(response.roomOffers())
        .extracting(ListingDetailResponse.RoomOfferResponse::roomOfferId)
        .containsExactly(ROOM_OFFER_ID);
    assertThat(response.conditions())
        .containsExactlyInAnyOrder(
            ConditionTag.FEMALE_ONLY,
            ConditionTag.ADDRESS_REGISTRATION,
            ConditionTag.PRIVATE_BATH,
            ConditionTag.NO_MAINT_FEE,
            ConditionTag.NO_ARC);
    assertThat(response.conditions()).doesNotContain(ConditionTag.MOVE_IN_NOW);
  }

  /** 진단 추천 view도 목록/상세와 같은 매물 단위 conditions 계산 규칙을 사용한다. */
  @Test
  void toRecommendedView_conditions는_ACTIVE방_전체_합집합과_NO_ARC를_반환한다() {
    RecommendedListingView response = ListingResponseMapper.toRecommendedView(sampleListing());

    assertThat(response.conditions())
        .containsExactlyInAnyOrder(
            "FEMALE_ONLY", "ADDRESS_REGISTRATION", "PRIVATE_BATH", "NO_MAINT_FEE", "NO_ARC");
    assertThat(response.conditions()).doesNotContain("MOVE_IN_NOW");
  }

  /** 테스트에서 사용할 공개 매물 도메인 객체다. */
  private static Listing sampleListing() {
    return Listing.builder()
        .id(LISTING_ID)
        .schemaVersion(2)
        .landlordId(1L)
        .title("테스트 고시원")
        .type(ListingType.GOSIWON)
        .status(Listing.ListingStatus.PUBLISHED)
        .rentalType(Listing.RentalType.MONTHLY_RENT)
        .refundPolicy(
            new Listing.RefundPolicy(
                Listing.RefundPolicyCode.FULL_REFUND_BEFORE_7_DAYS, "입주 7일 전 전액 환불"))
        .contract(new Listing.Contract(1, 12))
        .genderPolicy(Listing.GenderPolicy.FEMALE_ONLY)
        .location(new Listing.GeoPoint(126.951422, 37.459471))
        .address(new Listing.Address("SEOUL", "GWANAK_GU", "서울특별시 관악구 테스트로 1", null))
        .nearestTransit(new Listing.NearestTransit(Listing.TransitType.SUBWAY, "서울대입구역", 5, "편의점"))
        .nearbyUniversityCodes(Set.of("SNU"))
        .building(new Listing.Building(Listing.BuildingType.VILLA, 1, 2, 4, true, true))
        .propertyPolicies(new Listing.PropertyPolicies(false, true, true, true, false))
        .facilities(
            new Listing.Facilities(
                Set.of(Listing.HeatingSystem.CENTRAL),
                Set.of("공용 냉장고"),
                Set.of("COIN_LAUNDRY"),
                Set.of("WIFI"),
                Set.of("CCTV"),
                List.of(new Listing.CommonSpace(Listing.CommonSpaceType.SHARED_TOILET, 2)),
                Set.of("BEDDING")))
        .roomOffers(List.of(sampleRoomOffer(), secondActiveRoomOffer(), inactiveRoomOffer()))
        .descriptions(new Listing.Descriptions("테스트 설명", "Test description", "테스트"))
        .imageUrls(
            List.of(
                "https://cdn.kohere.app/listings/test/1.jpg",
                "https://cdn.kohere.app/listings/test/2.jpg"))
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
        new Listing.Pricing(300000, 300000, 0, Listing.Currency.KRW),
        new Listing.Inventory(1, 1, LocalDate.parse("2026-07-01")),
        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.ADDRESS_REGISTRATION),
        List.of());
  }

  /** 상세 요약의 최대값 계산에 쓰는 두 번째 ACTIVE 방 상품이다. */
  private static Listing.RoomOffer secondActiveRoomOffer() {
    return new Listing.RoomOffer(
        SECOND_ROOM_OFFER_ID,
        "프리미엄 1인실",
        Listing.RoomOfferStatus.ACTIVE,
        new Listing.Pricing(450000, 500000, 20000, Listing.Currency.KRW),
        new Listing.Inventory(2, 2, LocalDate.parse("2026-07-01")),
        Set.of(ConditionTag.PRIVATE_BATH, ConditionTag.NO_MAINT_FEE),
        List.of());
  }

  /** 상세 응답에서 제외되어야 하는 비활성 방 상품이다. */
  private static Listing.RoomOffer inactiveRoomOffer() {
    return new Listing.RoomOffer(
        INACTIVE_ROOM_OFFER_ID,
        "비노출 방",
        Listing.RoomOfferStatus.INACTIVE,
        new Listing.Pricing(100000, 100000, 0, Listing.Currency.KRW),
        new Listing.Inventory(1, 1, LocalDate.parse("2026-07-01")),
        Set.of(ConditionTag.MOVE_IN_NOW),
        List.of());
  }
}
