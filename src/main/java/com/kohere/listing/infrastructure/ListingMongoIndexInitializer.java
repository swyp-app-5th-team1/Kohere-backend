package com.kohere.listing.infrastructure;

import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeospatialIndex;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** {@code listings} 조회 인덱스를 기동 시 멱등 적용한다. 매물 값 검증은 애플리케이션·도메인 계층이 담당한다. */
@Component
@ConditionalOnProperty(
    name = "app.mongo.indexes-enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
class ListingMongoIndexInitializer implements InitializingBean {

  private final MongoTemplate mongoTemplate;

  /** 애플리케이션 시작 시 지도·필터 조회용 MongoDB 인덱스를 준비한다. */
  @Override
  public void afterPropertiesSet() {
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
            .on("roomOffers.inventory.availableCount", ASC)
            .named("listings_status_room_available_count"));
    indexOperations.createIndex(
        new Index()
            .on("status", ASC)
            .on("propertyPolicies.arcRequired", ASC)
            .named("listings_status_arc_required"));
  }
}
