package com.kohere.listing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.kohere.listing.domain.ArcRequirement;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.KitchenFacility;
import com.kohere.listing.domain.LaundryFacility;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingSearchResult;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.LivingAmenity;
import com.kohere.listing.domain.LocalizedText;
import com.kohere.listing.domain.Nationality;
import com.kohere.listing.domain.NearbyFacility;
import com.kohere.listing.domain.ProvidedSupply;
import com.kohere.listing.domain.SecurityFeature;
import com.kohere.listing.domain.SupportedLanguage;
import com.kohere.listing.domain.catalog.ListingCatalogCategory;
import com.kohere.listing.domain.catalog.ListingCatalogEntry;
import com.kohere.listing.domain.catalog.ListingCatalogRepository;
import com.kohere.listing.domain.favorite.FavoriteRepository;
import com.kohere.listing.domain.recent.RecentListingRepository;
import com.kohere.listing.presentation.dto.ListingSearchRequest;
import com.kohere.user.api.UserAccountService;
import java.time.Instant;
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

  @Mock private AppUserGuard appUserGuard;

  @Mock private ListingRepository listingRepository;
  @Mock private FavoriteRepository favoriteRepository;
  @Mock private RecentListingRepository recentListingRepository;
  @Mock private UserAccountService userAccountService;

  private ListingService listingService;

  @BeforeEach
  void setUp() {
    ListingCatalogRepository catalogRepository = ListingServiceTest::catalogEntries;
    listingService =
        new ListingService(
            listingRepository,
            favoriteRepository,
            recentListingRepository,
            new ListingLocalizationService(catalogRepository),
            userAccountService,
            appUserGuard);
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

  /**
   * 비로그인·온보딩 미완료 사용자는 컨트롤러에서 {@code userId=null}로 전달된다. 공개 상세는 그대로 반환하되, 사용자 식별자가 필요한 찜 조회와 최근 본
   * 저장은 호출하지 않아야 한다.
   */
  @Test
  void getListing_공개조회는_사용자전용_조회와_최근본기록을_건너뛴다() {
    Listing listing = sampleListing();
    when(listingRepository.findById(LISTING_ID)).thenReturn(Optional.of(listing));

    ListingDetailResponse response = listingService.getListing(null, LISTING_ID);

    assertThat(response.listingId()).isEqualTo(LISTING_ID);
    assertThat(response.title()).isEqualTo("Test Goshiwon");
    assertThat(response.favorited()).isFalse();
    assertThat(response.favoriteCount()).isEqualTo(3);
    verify(favoriteRepository, never()).findByUserIdAndListingId(any(), any());
    verify(recentListingRepository, never()).upsertViewedAt(any(), any(), any());
    verify(recentListingRepository, never()).deleteOldByUserIdKeepingLatest(any(), anyInt());
    verify(userAccountService, never()).getLanguage(anyLong());
  }

  /** 상세 응답은 매물 공통 필드를 루트에 두고, UI에 노출 가능한 ACTIVE 방만 내려준다. */
  @Test
  void getListing_상세응답은_DB구조에_가깝게_루트필드와_ACTIVE_방을_반환한다() {
    Listing listing = sampleListing();
    when(listingRepository.findById(LISTING_ID)).thenReturn(Optional.of(listing));
    when(favoriteRepository.findByUserIdAndListingId(1L, LISTING_ID)).thenReturn(Optional.empty());

    ListingDetailResponse response = listingService.getListing(1L, LISTING_ID);

    assertThat(response.title()).isEqualTo("Test Goshiwon");
    assertThat(response.rentalType().code()).isEqualTo("MONTHLY_RENT");
    assertThat(response.rentalType().label()).isEqualTo("Monthly Rent");
    assertThat(response.refundPolicy()).isEqualTo("Full refund before seven days");
    assertThat(response.genderPolicy().code()).isEqualTo("FEMALE_ONLY");
    assertThat(response.genderPolicy().label()).isEqualTo("Female Only");
    assertThat(response.nearestTransit().name()).isEqualTo("Seoul Nat'l Univ. Station");
    assertThat(response.conditions())
        .extracting(responseCondition -> responseCondition.code())
        .containsExactlyInAnyOrder(
            "FEMALE_ONLY", "ADDRESS_REGISTRATION", "PRIVATE_BATH", "NO_MAINT_FEE");
    assertThat(response.conditions())
        .filteredOn(condition -> condition.code().equals("NO_MAINT_FEE"))
        .singleElement()
        .satisfies(condition -> assertThat(condition.label()).isEqualTo("No Maint. Fee"));
    assertThat(response.conditions())
        .extracting(responseCondition -> responseCondition.code())
        .doesNotContain("MOVE_IN_NOW");
    assertThat(response.facilities().heatingSystem())
        .extracting(heating -> heating.code())
        .containsExactly("CENTRAL");
    assertThat(response.facilities().kitchen())
        .extracting(kitchen -> kitchen.label())
        .contains("Electric Kettle");
    assertThat(response.facilities().laundry())
        .extracting(laundry -> laundry.label())
        .contains("Clothes Dryer", "Iron");
    assertThat(response.facilities().commonSpaces())
        .extracting(commonSpace -> commonSpace.label())
        .contains("Meeting Room", "Rooftop");
    assertThat(response.facilities().providedSupplies())
        .extracting(supply -> supply.label())
        .contains("Toilet Paper");
    assertThat(response.imageUrls()).hasSize(2);
    assertThat(response.roomOffers())
        .extracting(ListingDetailResponse.RoomOfferResponse::roomOfferId)
        .containsExactly(ROOM_OFFER_ID, SECOND_ROOM_OFFER_ID);
    assertThat(response.roomOffers())
        .allSatisfy(
            roomOffer -> assertThat(roomOffer.status()).isEqualTo(Listing.RoomOfferStatus.ACTIVE));
    // v4에서 계약기간은 루트가 아니라 방 상품마다 달라진다.
    assertThat(response.roomOffers())
        .extracting(roomOffer -> roomOffer.contract())
        .containsExactly(new Listing.Contract(1, 12), new Listing.Contract(3, 24));
  }

  /** 목록 카드의 conditions는 필터를 통과한 방만이 아니라 매물의 ACTIVE 방 전체 기준으로 계산한다. */
  @Test
  void getListings_conditions는_필터통과방이_아니라_ACTIVE방_전체_합집합을_반환한다() {
    Listing listing = sampleListing();
    when(listingRepository.search(any()))
        .thenReturn(
            PageResponse.of(
                List.of(new ListingSearchResult(listing, List.of(sampleRoomOffer()))),
                new PageInfo(0, 20, 1, 1, false)));

    ListingSummaryResponse response =
        listingService.getListings(new ListingSearchRequest()).content().getFirst();

    assertThat(response.nearestTransit().name()).isEqualTo("Seoul Nat'l Univ. Sta.");
    assertThat(response.roomOffers())
        .extracting(ListingDetailResponse.RoomOfferResponse::roomOfferId)
        .containsExactly(ROOM_OFFER_ID);
    assertThat(response.conditions())
        .extracting(responseCondition -> responseCondition.code())
        .containsExactlyInAnyOrder(
            "FEMALE_ONLY", "ADDRESS_REGISTRATION", "PRIVATE_BATH", "NO_MAINT_FEE");
    assertThat(response.conditions())
        .extracting(responseCondition -> responseCondition.code())
        .doesNotContain("MOVE_IN_NOW");
  }

  /** 한국어 사용자에게는 같은 code를 유지하면서 label과 고유 문구만 한국어로 선택한다. */
  @Test
  void getListing_한국어사용자는_한국어문구와_동일한code를_받는다() {
    Listing listing = sampleListing();
    when(userAccountService.getLanguage(1L)).thenReturn("ko");
    when(listingRepository.findById(LISTING_ID)).thenReturn(Optional.of(listing));
    when(favoriteRepository.findByUserIdAndListingId(1L, LISTING_ID)).thenReturn(Optional.empty());

    ListingDetailResponse response = listingService.getListing(1L, LISTING_ID);

    assertThat(response.title()).isEqualTo("테스트 고시원");
    assertThat(response.type().code()).isEqualTo("GOSHIWON");
    assertThat(response.type().label()).isEqualTo("고시원");
    assertThat(response.genderPolicy().code()).isEqualTo("FEMALE_ONLY");
    assertThat(response.genderPolicy().label()).isEqualTo("여성 전용");
    assertThat(response.nearestTransit().name()).isEqualTo("서울대입구역");
    assertThat(response.roomOffers().getFirst().name()).isEqualTo("스탠다드 1인실");
    assertThat(response.description()).isEqualTo("테스트 설명");
    assertThat(response.extraNotes()).isEqualTo("테스트 주의사항");
    assertThat(response.refundPolicy()).isEqualTo("입주 7일 전 전액 환불");
  }

  /** 진단 추천 view도 목록/상세와 같은 매물 단위 conditions 계산 규칙을 사용한다. */
  @Test
  void toRecommendedView_conditions는_ACTIVE방_전체_합집합을_반환한다() {
    ListingLocalizationContext localization =
        new ListingLocalizationService(ListingServiceTest::catalogEntries).contextFor("en");
    RecommendedListingView response =
        ListingResponseMapper.toRecommendedView(sampleListing(), localization);

    assertThat(response.conditions())
        .extracting(condition -> condition.code())
        .containsExactlyInAnyOrder(
            "FEMALE_ONLY", "ADDRESS_REGISTRATION", "PRIVATE_BATH", "NO_MAINT_FEE");
    assertThat(response.conditions())
        .extracting(condition -> condition.code())
        .doesNotContain("MOVE_IN_NOW");
  }

  /** 테스트에서 사용할 공개 매물 도메인 객체다. */
  private static Listing sampleListing() {
    return Listing.builder()
        .id(LISTING_ID)
        .schemaVersion(4)
        .landlordId(1L)
        .contact(new Listing.Contact("김담당", "+82) 10-1111-2222"))
        .businessRegistrationNumber("1112233344")
        .blogUrl(null)
        .ageMin(20)
        .ageMax(35)
        .title(new LocalizedText("테스트 고시원", "Test Goshiwon"))
        .type(ListingType.GOSHIWON)
        .rentalType(Listing.RentalType.MONTHLY_RENT)
        .status(Listing.ListingStatus.PUBLISHED)
        .rejectionReason(null)
        .genderPolicy(Listing.GenderPolicy.FEMALE_ONLY)
        .languagesSupported(Set.of(SupportedLanguage.ENGLISH))
        .favoriteCount(3)
        .imageUrls(
            List.of(
                "https://cdn.kohere.app/listings/test/1.jpg",
                "https://cdn.kohere.app/listings/test/2.jpg"))
        .nearbyUniversityCodes(Set.of("SNU"))
        .createdAt(Instant.parse("2026-06-24T00:00:00Z"))
        .updatedAt(Instant.parse("2026-06-24T00:00:00Z"))
        .address(
            new Listing.Address(
                "SEOUL",
                "GWANAK_GU",
                new LocalizedText("서울특별시 관악구 테스트로 1", "1 Test-ro, Gwanak-gu, Seoul"),
                null))
        .building(new Listing.Building(Listing.BuildingType.VILLA, 1, 2, 4, true, true))
        .description(new LocalizedText("테스트 설명", "Test description"))
        .extraNotes(new LocalizedText("테스트 주의사항", "Test notes"))
        .facilities(
            new Listing.Facilities(
                Set.of(Listing.HeatingSystem.CENTRAL),
                Set.of(KitchenFacility.SHARED_REFRIGERATOR, KitchenFacility.ELECTRIC_KETTLE),
                Set.of(LaundryFacility.WASHER, LaundryFacility.DRYER, LaundryFacility.IRON),
                Set.of(LivingAmenity.WIFI),
                Set.of(SecurityFeature.CCTV),
                Set.of(
                    Listing.CommonSpaceType.SHARED_TOILET,
                    Listing.CommonSpaceType.MEETING_ROOM,
                    Listing.CommonSpaceType.ROOFTOP),
                Set.of(ProvidedSupply.BEDDING, ProvidedSupply.TISSUE)))
        .location(new Listing.GeoPoint(126.951422, 37.459471))
        .nearestTransit(
            new Listing.NearestTransit(
                Listing.TransitType.SUBWAY,
                new LocalizedText("서울대입구역", "Seoul Nat'l Univ. Station"),
                5))
        .nearbyFacilities(Set.of(NearbyFacility.CONVENIENCE_STORE))
        .arcRequired(ArcRequirement.NOT_REQUIRED)
        .refundPolicy(new LocalizedText("입주 7일 전 전액 환불", "Full refund before seven days"))
        .roomOffers(List.of(sampleRoomOffer(), secondActiveRoomOffer(), inactiveRoomOffer()))
        .preferredNationalities(Set.of(Nationality.JAPAN))
        .contractDifficulties(Set.of(ContractDifficulty.LANGUAGE))
        .serviceFeedback(null)
        .build();
  }

  /** 상세 응답과 최근 본 카드 집계에 필요한 최소 방 상품이다. */
  private static Listing.RoomOffer sampleRoomOffer() {
    return new Listing.RoomOffer(
        ROOM_OFFER_ID,
        new LocalizedText("스탠다드 1인실", "Standard Single Room"),
        Listing.RoomOfferStatus.ACTIVE,
        new Listing.Contract(1, 12),
        new Listing.Pricing(300000, 300000, 0, Listing.Currency.KRW),
        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.ADDRESS_REGISTRATION),
        List.of());
  }

  /** 상세 요약의 최대값 계산에 쓰는 두 번째 ACTIVE 방 상품이다. */
  private static Listing.RoomOffer secondActiveRoomOffer() {
    return new Listing.RoomOffer(
        SECOND_ROOM_OFFER_ID,
        new LocalizedText("프리미엄 1인실", "Premium Single Room"),
        Listing.RoomOfferStatus.ACTIVE,
        new Listing.Contract(3, 24),
        new Listing.Pricing(450000, 500000, 20000, Listing.Currency.KRW),
        Set.of(ConditionTag.PRIVATE_BATH, ConditionTag.NO_MAINT_FEE),
        List.of());
  }

  /** 상세 응답에서 제외되어야 하는 비활성 방 상품이다. */
  private static Listing.RoomOffer inactiveRoomOffer() {
    return new Listing.RoomOffer(
        INACTIVE_ROOM_OFFER_ID,
        new LocalizedText("비노출 방", "Hidden Room"),
        Listing.RoomOfferStatus.INACTIVE,
        new Listing.Contract(1, 12),
        new Listing.Pricing(100000, 100000, 0, Listing.Currency.KRW),
        Set.of(ConditionTag.MOVE_IN_NOW),
        List.of());
  }

  /** 단위 테스트에 필요한 공통 코드 번역 사전이다. */
  private static List<ListingCatalogEntry> catalogEntries() {
    return List.of(
        catalog(ListingCatalogCategory.LISTING_TYPE, "GOSHIWON", "고시원", "Goshiwon"),
        catalog(ListingCatalogCategory.RENTAL_TYPE, "MONTHLY_RENT", "월세", "Monthly Rent"),
        catalog(ListingCatalogCategory.GENDER_POLICY, "FEMALE_ONLY", "여성 전용", "Female Only"),
        catalog(ListingCatalogCategory.HEATING_SYSTEM, "CENTRAL", "중앙난방", "Central Heating"),
        catalog(ListingCatalogCategory.KITCHEN, "ELECTRIC_KETTLE", "전기포트", "Electric Kettle"),
        catalog(ListingCatalogCategory.LAUNDRY, "DRYER", "건조기", "Clothes Dryer"),
        catalog(ListingCatalogCategory.LAUNDRY, "IRON", "다리미", "Iron"),
        catalog(ListingCatalogCategory.COMMON_SPACE, "MEETING_ROOM", "회의실", "Meeting Room"),
        catalog(ListingCatalogCategory.COMMON_SPACE, "ROOFTOP", "옥상", "Rooftop"),
        catalog(ListingCatalogCategory.PROVIDED_SUPPLY, "TISSUE", "휴지", "Toilet Paper"),
        catalog(ListingCatalogCategory.CONDITION_TAG, "FEMALE_ONLY", "여성 전용", "Female Only"),
        catalog(
            ListingCatalogCategory.CONDITION_TAG,
            "ADDRESS_REGISTRATION",
            "전입신고 가능",
            "Address Registration"),
        catalog(ListingCatalogCategory.CONDITION_TAG, "PRIVATE_BATH", "개인 욕실", "Private Bath"),
        catalog(ListingCatalogCategory.CONDITION_TAG, "NO_MAINT_FEE", "관리비 없음", "No Maint. Fee"));
  }

  /** 간결한 테스트 카탈로그 항목 생성 헬퍼다. */
  private static ListingCatalogEntry catalog(
      ListingCatalogCategory category, String code, String ko, String en) {
    return new ListingCatalogEntry(category, code, new LocalizedText(ko, en));
  }
}
