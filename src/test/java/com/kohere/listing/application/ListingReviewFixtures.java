package com.kohere.listing.application;

import com.kohere.listing.domain.ArcRequirement;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.KitchenFacility;
import com.kohere.listing.domain.LaundryFacility;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.LivingAmenity;
import com.kohere.listing.domain.LocalizedText;
import com.kohere.listing.domain.Nationality;
import com.kohere.listing.domain.NearbyFacility;
import com.kohere.listing.domain.ProvidedSupply;
import com.kohere.listing.domain.SecurityFeature;
import com.kohere.listing.domain.SupportedLanguage;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 관리자 심사 테스트가 쓰는 매물 픽스처다.
 *
 * <p>심사 응답은 세입자 상세와 같은 매퍼를 타므로 <b>라벨을 붙이는 모든 코드 필드가 채워져 있어야</b> 한다. 일부만 채우면 응답 조립에서 {@code
 * NullPointerException}이 나 정작 검증하려던 인가·상태 전이가 가려진다.
 */
final class ListingReviewFixtures {

  private static final String LISTING_ID = "68e0000000000000000000a1";
  private static final String ROOM_OFFER_ID = "68e0000000000000000001a1";
  private static final String SECOND_ROOM_OFFER_ID = "68e0000000000000000001a2";
  private static final String INACTIVE_ROOM_OFFER_ID = "68e0000000000000000001a3";

  private ListingReviewFixtures() {}

  /** 심사 응답 조립에 필요한 필드를 모두 채운 심사 대기 매물이다. */
  static Listing pendingListing() {
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
        .status(Listing.ListingStatus.PENDING)
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
}
