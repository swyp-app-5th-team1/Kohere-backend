package com.kohere.listing.domain.catalog;

/**
 * {@code listingCatalog}에서 같은 코드 이름을 용도별로 구분하는 카테고리다.
 *
 * <p>예를 들어 {@code FEMALE_ONLY}는 방 필터 조건과 성별 정책에 모두 등장할 수 있다. 카테고리와 코드를 함께 키로 사용하면 두 용도의 번역을 서로
 * 충돌시키지 않고 관리할 수 있다.
 */
public enum ListingCatalogCategory {
  CONDITION_TAG,
  LISTING_TYPE,
  RENTAL_TYPE,
  GENDER_POLICY,
  BUILDING_TYPE,
  TRANSIT_TYPE,
  HEATING_SYSTEM,
  KITCHEN,
  LAUNDRY,
  LIVING_AMENITY,
  SECURITY_FEATURE,
  COMMON_SPACE,
  PROVIDED_SUPPLY
}
