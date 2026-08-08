package com.kohere.listing.infrastructure.migration;

/** Listing Mongock 마이그레이션에서 사용하는 MongoDB 컬렉션 이름. */
final class ListingMigrationCollections {

  static final String LISTINGS = "listings";
  static final String LISTING_CATALOG = "listingCatalog";
  static final String SEARCH_PLACES = "searchPlaces";

  private ListingMigrationCollections() {}
}
