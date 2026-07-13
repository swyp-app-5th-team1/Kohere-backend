package com.kohere.listing.infrastructure;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 로컬 개발과 테스트에서 사용할 매물 seed 도메인 객체를 만든다. */
final class ListingSeedFixtures {

  static final String GOSHIWON_001_ID = "6858e2000000000000000001";
  static final String GOSHIWON_001_ROOM_OFFER_ID = "6858e2000000000000000101";

  private ListingSeedFixtures() {}

  /** seed로 적재할 매물 목록이다. 새 임시 매물은 이 목록에 추가한다. */
  static List<Listing> listings() {
    return List.of(goshiwon001());
  }

  /** Numbers 첫 번째 예시 행을 바탕으로 만든 고시원 seed 매물이다. */
  static Listing goshiwon001() {
    Listing.RoomOffer roomOffer =
        new Listing.RoomOffer(
            GOSHIWON_001_ROOM_OFFER_ID,
            "스탠다드 1인실",
            Listing.RoomOfferStatus.ACTIVE,
            new Listing.Pricing(300000, 300000, 0, Listing.Currency.KRW),
            new Listing.Inventory(10, 0, null),
            Set.of(
                ConditionTag.FEMALE_ONLY,
                ConditionTag.ADDRESS_REGISTRATION,
                ConditionTag.NO_MAINT_FEE,
                ConditionTag.MEALS_INCLUDED),
            List.of());

    return Listing.builder()
        .id(GOSHIWON_001_ID)
        .schemaVersion(2)
        .landlordId(1L)
        .title("고시원001")
        .type(ListingType.GOSHIWON)
        .status(Listing.ListingStatus.PUBLISHED)
        .rentalType(Listing.RentalType.MONTHLY_RENT)
        .refundPolicy(
            new Listing.RefundPolicy(
                Listing.RefundPolicyCode.FULL_REFUND_BEFORE_7_DAYS, "입주 7일 전 취소 시 전액 환불"))
        .contract(new Listing.Contract(2, 6))
        .genderPolicy(Listing.GenderPolicy.FEMALE_ONLY)
        .location(new Listing.GeoPoint(126.951422, 37.459471))
        .address(
            new Listing.Address("SEOUL", "GWANAK_GU", "서울특별시 Gwanak-gu Sillim-dong 나로 56-15", null))
        .nearestTransit(
            new Listing.NearestTransit(
                Listing.TransitType.SUBWAY, "Seoul Nat'l Univ.", 5, "CU, 스타벅스, 약국, 헬스장"))
        .nearbyUniversityCodes(Set.of("SNU", "CAU", "SOONGSIL"))
        .building(new Listing.Building(Listing.BuildingType.VILLA, 1, 2, 4, true, true))
        .propertyPolicies(new Listing.PropertyPolicies(false, true, true, true, false))
        .facilities(
            new Listing.Facilities(
                Set.of(Listing.HeatingSystem.CENTRAL),
                Set.of("Shared Refrigerator", "Microwave"),
                Set.of("COIN_LAUNDRY"),
                Set.of("MICROWAVE", "SOFA", "TV", "WIFI"),
                Set.of("CCTV", "ENTRANCE_DOOR_LOCK", "FIRE_EXTINGUISHER"),
                List.of(
                    new Listing.CommonSpace(Listing.CommonSpaceType.SHARED_TOILET, 6),
                    new Listing.CommonSpace(Listing.CommonSpaceType.STUDY_ROOM, null)),
                Set.of("Shower Room", "Laundry Detergent", "Toilet Paper", "Slippers")))
        .roomOffers(List.of(roomOffer))
        .descriptions(
            new Listing.Descriptions(
                "지하철역 도보 5분 이내, 교통이 편리한 위치의 코리빙 하우스입니다.",
                "A well-maintained gosiwon welcoming foreign residents. English support available.",
                "외국인 환영, 영어 안내 가능합니다."))
        .imageUrls(List.of())
        .favoriteCount(0)
        .createdAt(Instant.parse("2024-09-15T00:00:00Z"))
        .updatedAt(Instant.parse("2024-10-04T00:35:00Z"))
        .build();
  }
}
