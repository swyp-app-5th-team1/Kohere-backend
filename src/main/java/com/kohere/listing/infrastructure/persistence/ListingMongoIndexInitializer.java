package com.kohere.listing.infrastructure.persistence;

import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeospatialIndex;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * {@code listings} 조회 인덱스를 기동 시 멱등 생성한다.
 *
 * <p>문서 검증 규칙({@code $jsonSchema})은 이 클래스가 소유하지 않는다. validator 전이는 순서가 있는 사건이라 Mongock changeUnit이
 * 소유하며, v4 스키마는 {@code ListingV4BaselineChangeUnit} 안에 동결돼 있다.
 */
@Component
@ConditionalOnProperty(
    name = "app.mongo.indexes-enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class ListingMongoIndexInitializer implements ApplicationRunner {

  private final MongoTemplate mongoTemplate;

  /** 애플리케이션 시작 시 지도·필터 조회용 MongoDB 인덱스를 준비한다. */
  @Override
  public void run(ApplicationArguments args) {
    ensureIndexes();
  }

  /** 지도 조회와 필터 조회에 필요한 MongoDB 인덱스를 멱등하게 생성한다. */
  private void ensureIndexes() {
    var indexOperations = mongoTemplate.indexOps(ListingDocument.class);

    indexOperations.createIndex(
        new GeospatialIndex("location")
            .typed(GeoSpatialIndexType.GEO_2DSPHERE)
            .named("listings_location_2dsphere"));
    indexOperations.createIndex(
        new Index()
            .on("status", ASC)
            .on("type", ASC)
            .on("roomOffers.pricing.monthlyRent", ASC)
            .named("listings_status_type_rent"));
    indexOperations.createIndex(
        new Index()
            .on("landlordId", ASC)
            .on("status", ASC)
            .on("updatedAt", DESC)
            .named("listings_landlord_status_updated"));
    indexOperations.createIndex(
        new Index()
            .on("status", ASC)
            .on("roomOffers.filterTags", ASC)
            .named("listings_status_room_filter_tags"));
    indexOperations.createIndex(
        new Index()
            .on("status", ASC)
            .on("arcRequired", ASC)
            .named("listings_status_arc_requirement"));
    // 관리자 심사 목록의 유일한 접근 경로다. 세입자 조회와 달리 status로 좁힌 뒤 등록 최신순으로 정렬하므로
    // createdAt을 정렬 키로 함께 싣는다. status 조건이 없는 전체 조회는 createdAt 접두를 쓰지 못하지만,
    // 그 경로는 정렬만 인덱스로 처리하면 되고 심사 대기 목록이 주 사용처다.
    indexOperations.createIndex(
        new Index().on("status", ASC).on("createdAt", DESC).named("listings_status_created_at"));

    var universityIndexOperations = mongoTemplate.indexOps(UniversityDocument.class);

    // 등록이 매물 좌표로 반경 안의 대학을 훑는다. 원장이 14건이라 컬렉션 스캔도 답은 같지만,
    // 지오 인덱스를 두는 편이 조회 계획이 명확하고 원장이 커져도 그대로 간다.
    universityIndexOperations.createIndex(
        new GeospatialIndex("location")
            .typed(GeoSpatialIndexType.GEO_2DSPHERE)
            .named("universities_location_2dsphere"));

    var catalogIndexOperations = mongoTemplate.indexOps(ListingCatalogDocument.class);

    // category+code는 프론트에 전달할 하나의 번역 항목을 식별한다. 같은 코드가 카테고리별로 다른 라벨을 가질 수 있다.
    catalogIndexOperations.createIndex(
        new Index()
            .on("category", ASC)
            .on("code", ASC)
            .unique()
            .named("listing_catalog_category_code"));

    var favoriteIndexOperations = mongoTemplate.indexOps(FavoriteDocument.class);

    // 사용자 한 명이 같은 매물을 여러 번 찜할 수 없게 DB 레벨에서 보장한다.
    // 서비스는 중복 키 예외를 "이미 찜한 상태"로 바꿔 멱등한 200 OK 응답을 만든다.
    favoriteIndexOperations.createIndex(
        new Index()
            .on("userId", ASC)
            .on("listingId", ASC)
            .unique()
            .named("favorites_user_listing"));

    // 내 찜 목록은 MVP에서 favoritedAt desc 고정이므로 사용자+찜시각 인덱스로 최신순 페이지 조회를 지원한다.
    favoriteIndexOperations.createIndex(
        new Index().on("userId", ASC).on("favoritedAt", DESC).named("favorites_user_favoritedAt"));

    var recentListingIndexOperations = mongoTemplate.indexOps(RecentListingDocument.class);

    // 같은 사용자가 같은 매물을 여러 번 봐도 최근 본 목록에는 한 번만 남도록 DB 레벨에서 보장한다.
    recentListingIndexOperations.createIndex(
        new Index()
            .on("userId", ASC)
            .on("listingId", ASC)
            .unique()
            .named("recentListings_user_listing"));

    // 최근 본 목록 조회와 사용자별 30개 보관 정리는 모두 viewedAt desc 순서를 사용한다.
    recentListingIndexOperations.createIndex(
        new Index().on("userId", ASC).on("viewedAt", DESC).named("recentListings_user_viewedAt"));
  }
}
