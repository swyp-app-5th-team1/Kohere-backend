package com.kohere.listing.infrastructure.migration;

import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.catalog.ListingCatalogCategory;
import java.util.List;
import java.util.Map;

/**
 * Listing UI에서 사용자에게 표시할 수 있는 전체 공통 코드의 한국어·영어 정본을 만든다.
 *
 * <p>이 목록은 특정 매물 한 건에 들어 있는 값만 담지 않는다. 현재 매물이 사용하지 않더라도 필터 화면이나 앞으로 등록될 매물에서 사용할 수 있는 전체 허용 코드를
 * 포함한다. 내부 상태({@code PUBLISHED}, {@code ACTIVE})처럼 현재 UI에 표시하지 않는 코드는 의도적으로 제외한다.
 */
final class ListingCatalogSeedFixtures {

  private ListingCatalogSeedFixtures() {}

  /** Mongock 시드와 테스트가 공유할 전체 카탈로그 문서 목록을 반환한다. */
  static List<ListingCatalogSeedDocument> documents() {
    return List.of(
        // 검색 필터·매물 카드·상세 Features에서 공통으로 쓰는 조건 코드다.
        entry(ListingCatalogCategory.CONDITION_TAG, "MOVE_IN_NOW", "즉시 입주", "Move-in Now"),
        entry(ListingCatalogCategory.CONDITION_TAG, "FEMALE_ONLY", "여성 전용", "Female Only"),
        entry(ListingCatalogCategory.CONDITION_TAG, "MEALS_INCLUDED", "식사 제공", "Meals Included"),
        entry(ListingCatalogCategory.CONDITION_TAG, "DOUBLE_ROOM", "2인실", "Double Room"),
        entry(ListingCatalogCategory.CONDITION_TAG, "PRIVATE_BATH", "개인 욕실", "Private Bath"),
        entry(ListingCatalogCategory.CONDITION_TAG, "ENGLISH_OK", "영어 안내 가능", "English OK"),
        entry(
            ListingCatalogCategory.CONDITION_TAG,
            "ADDRESS_REGISTRATION",
            "전입신고 가능",
            "Address Registration"),
        // 앱의 좁은 feature chip에 그대로 표시되는 Figma 확정 문구이므로 축약형을 정본으로 사용한다.
        entry(ListingCatalogCategory.CONDITION_TAG, "NO_MAINT_FEE", "관리비 없음", "No Maint. Fee"),
        entry(ListingCatalogCategory.CONDITION_TAG, "NO_ARC", "외국인등록증 없이 가능", "No ARC"),

        // 필터와 상세 정보 표에 표시하는 Listing 루트 enum 코드다.
        entry(ListingCatalogCategory.LISTING_TYPE, ListingType.GOSHIWON.name(), "고시원", "Goshiwon"),
        entry(
            ListingCatalogCategory.LISTING_TYPE, ListingType.CO_LIVING.name(), "코리빙", "Co-living"),
        entry(
            ListingCatalogCategory.LISTING_TYPE,
            ListingType.SHARE_HOUSE.name(),
            "쉐어하우스",
            "Share House"),
        entry(ListingCatalogCategory.LISTING_TYPE, ListingType.OTHER.name(), "기타", "Other"),
        entry(
            ListingCatalogCategory.RENTAL_TYPE,
            Listing.RentalType.MONTHLY_RENT.name(),
            "월세",
            "Monthly Rent"),
        entry(ListingCatalogCategory.RENTAL_TYPE, Listing.RentalType.JEONSE.name(), "전세", "Jeonse"),
        entry(
            ListingCatalogCategory.GENDER_POLICY,
            Listing.GenderPolicy.ANY.name(),
            "성별 제한 없음",
            "Any Gender"),
        entry(
            ListingCatalogCategory.GENDER_POLICY,
            Listing.GenderPolicy.FEMALE_ONLY.name(),
            "여성 전용",
            "Female Only"),
        entry(
            ListingCatalogCategory.GENDER_POLICY,
            Listing.GenderPolicy.MALE_ONLY.name(),
            "남성 전용",
            "Male Only"),
        entry(
            ListingCatalogCategory.GENDER_POLICY,
            Listing.GenderPolicy.GENDER_SEPARATED.name(),
            "남녀 분리",
            "Gender-Separated"),
        entry(
            ListingCatalogCategory.BUILDING_TYPE,
            Listing.BuildingType.GOSHIWON.name(),
            "고시원",
            "Goshiwon"),
        entry(
            ListingCatalogCategory.BUILDING_TYPE, Listing.BuildingType.VILLA.name(), "빌라", "Villa"),
        entry(
            ListingCatalogCategory.BUILDING_TYPE,
            Listing.BuildingType.APARTMENT.name(),
            "아파트",
            "Apartment"),
        entry(
            ListingCatalogCategory.BUILDING_TYPE,
            Listing.BuildingType.OFFICETEL.name(),
            "오피스텔",
            "Officetel"),
        entry(
            ListingCatalogCategory.BUILDING_TYPE, Listing.BuildingType.OTHER.name(), "기타", "Other"),
        entry(
            ListingCatalogCategory.TRANSIT_TYPE,
            Listing.TransitType.SUBWAY.name(),
            "지하철",
            "Subway"),
        entry(ListingCatalogCategory.TRANSIT_TYPE, Listing.TransitType.BUS.name(), "버스", "Bus"),

        // 상세 Facilities 섹션의 난방·주방·세탁·생활 편의 코드다.
        entry(
            ListingCatalogCategory.HEATING_SYSTEM,
            Listing.HeatingSystem.CENTRAL.name(),
            "중앙난방",
            "Central Heating"),
        entry(
            ListingCatalogCategory.HEATING_SYSTEM,
            Listing.HeatingSystem.INDIVIDUAL.name(),
            "개별난방",
            "Individual Heating"),
        entry(
            ListingCatalogCategory.HEATING_SYSTEM,
            Listing.HeatingSystem.DISTRICT.name(),
            "지역난방",
            "District Heating"),
        entry(
            ListingCatalogCategory.HEATING_SYSTEM,
            Listing.HeatingSystem.OTHER.name(),
            "기타 난방",
            "Other Heating"),
        entry(ListingCatalogCategory.KITCHEN, "GAS_STOVE", "가스레인지", "Gas Stove"),
        entry(ListingCatalogCategory.KITCHEN, "MICROWAVE", "전자레인지", "Microwave"),
        // 향후 임대인 매물 등록에서 선택할 수 있도록 Figma의 주방시설 코드를 미리 제공한다.
        entry(ListingCatalogCategory.KITCHEN, "ELECTRIC_KETTLE", "전기포트", "Electric Kettle"),
        entry(
            ListingCatalogCategory.KITCHEN,
            "SHARED_KITCHEN_FULL_OPTION",
            "공용주방 풀옵션",
            "Fully Equipped Shared Kitchen"),
        entry(
            ListingCatalogCategory.KITCHEN, "SHARED_REFRIGERATOR", "공용 냉장고", "Shared Refrigerator"),
        entry(ListingCatalogCategory.LAUNDRY, "COIN_LAUNDRY", "코인 세탁기", "Coin Laundry"),
        // DRYER는 기존 코드와 한국어 의미를 유지하고 영어 표시명만 Figma의 구체적인 표현에 맞춘다.
        entry(ListingCatalogCategory.LAUNDRY, "DRYER", "건조기", "Clothes Dryer"),
        entry(ListingCatalogCategory.LAUNDRY, "IRON", "다리미", "Iron"),
        entry(ListingCatalogCategory.LAUNDRY, "PRIVATE_WASHER", "개인 세탁기", "Private Washer"),
        entry(ListingCatalogCategory.LAUNDRY, "SHARED_WASHER", "공용 세탁기", "Shared Washer"),
        entry(ListingCatalogCategory.LIVING_AMENITY, "AIR_CONDITIONER", "에어컨", "Air Conditioner"),
        entry(ListingCatalogCategory.LIVING_AMENITY, "GAS_STOVE", "가스레인지", "Gas Stove"),
        entry(ListingCatalogCategory.LIVING_AMENITY, "MICROWAVE", "전자레인지", "Microwave"),
        entry(
            ListingCatalogCategory.LIVING_AMENITY,
            "SHARED_KITCHEN_FULL_OPTION",
            "공용주방 풀옵션",
            "Fully Equipped Shared Kitchen"),
        entry(ListingCatalogCategory.LIVING_AMENITY, "SHARED_PC", "공용 PC", "Shared PC"),
        entry(
            ListingCatalogCategory.LIVING_AMENITY,
            "SHARED_REFRIGERATOR",
            "공용 냉장고",
            "Shared Refrigerator"),
        entry(ListingCatalogCategory.LIVING_AMENITY, "SOFA", "소파", "Sofa"),
        entry(ListingCatalogCategory.LIVING_AMENITY, "TV", "TV", "TV"),
        entry(ListingCatalogCategory.LIVING_AMENITY, "WIFI", "와이파이", "Wi-Fi"),

        // 상세 Facilities 섹션의 안전시설·공용공간·제공 비품 코드다.
        entry(ListingCatalogCategory.SECURITY_FEATURE, "CCTV", "CCTV", "CCTV"),
        entry(ListingCatalogCategory.SECURITY_FEATURE, "DOOR_LOCK", "도어락", "Door Lock"),
        entry(
            ListingCatalogCategory.SECURITY_FEATURE,
            "ENTRANCE_DOOR_LOCK",
            "공동현관 도어락",
            "Entrance Door Lock"),
        entry(ListingCatalogCategory.SECURITY_FEATURE, "FIRE_ALARM", "화재경보기", "Fire Alarm"),
        entry(
            ListingCatalogCategory.SECURITY_FEATURE,
            "FIRE_EXTINGUISHER",
            "소화기",
            "Fire Extinguisher"),
        entry(ListingCatalogCategory.SECURITY_FEATURE, "SECURITY_GUARD", "경비원", "Security Guard"),
        entry(
            ListingCatalogCategory.COMMON_SPACE,
            Listing.CommonSpaceType.SHARED_KITCHEN.name(),
            "공용 주방",
            "Shared Kitchen"),
        entry(
            ListingCatalogCategory.COMMON_SPACE,
            Listing.CommonSpaceType.SHARED_TOILET.name(),
            "공용 화장실",
            "Shared Toilet"),
        entry(
            ListingCatalogCategory.COMMON_SPACE,
            Listing.CommonSpaceType.SHARED_BATH.name(),
            "공용 샤워실",
            "Shared Bathroom"),
        entry(
            ListingCatalogCategory.COMMON_SPACE,
            Listing.CommonSpaceType.LOUNGE.name(),
            "라운지",
            "Lounge"),
        entry(
            ListingCatalogCategory.COMMON_SPACE,
            Listing.CommonSpaceType.STUDY_ROOM.name(),
            "스터디룸",
            "Study Room"),
        // 공용공간은 Listing.CommonSpaceType과 같은 코드를 사용해야 MongoDB 저장·조회 시 안전하다.
        entry(
            ListingCatalogCategory.COMMON_SPACE,
            Listing.CommonSpaceType.MEETING_ROOM.name(),
            "회의실",
            "Meeting Room"),
        entry(
            ListingCatalogCategory.COMMON_SPACE,
            Listing.CommonSpaceType.ROOFTOP.name(),
            "옥상",
            "Rooftop"),
        entry(ListingCatalogCategory.PROVIDED_SUPPLY, "BEDDING", "침구류", "Bedding"),
        entry(
            ListingCatalogCategory.PROVIDED_SUPPLY,
            "LAUNDRY_DETERGENT",
            "세탁세제",
            "Laundry Detergent"),
        entry(ListingCatalogCategory.PROVIDED_SUPPLY, "SEASONING", "조미료", "Seasoning"),
        entry(ListingCatalogCategory.PROVIDED_SUPPLY, "SLIPPERS", "실내화", "Slippers"),
        // TISSUE 코드는 유지하되 숙소 제공비품의 의미가 명확한 Figma 문구를 영어 정본으로 사용한다.
        entry(ListingCatalogCategory.PROVIDED_SUPPLY, "TISSUE", "휴지", "Toilet Paper"),
        entry(ListingCatalogCategory.PROVIDED_SUPPLY, "TOWEL", "수건", "Towel"));
  }

  /** 카테고리와 코드가 같은 시드가 여러 번 실행되어도 동일 문서로 식별되도록 id를 결정한다. */
  private static ListingCatalogSeedDocument entry(
      ListingCatalogCategory category, String code, String ko, String en) {
    return ListingCatalogSeedDocument.builder()
        .id(category.name() + ":" + code)
        .category(category)
        .code(code)
        .label(Map.of("ko", ko, "en", en))
        .build();
  }
}
