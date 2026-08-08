package com.kohere.listing.infrastructure.persistence;

import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.recent.RecentListing;
import com.kohere.listing.domain.recent.RecentListingRepository;
import com.kohere.listing.domain.recent.RecentListingView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * 최근 본 매물 MongoDB 영속 어댑터.
 *
 * <p>최근 본 목록은 {@code recentListings}에는 사용자·매물·조회시각만 저장하고, 조회할 때 {@code listings}를 lookup해 카드 응답에
 * 필요한 매물 정보를 가져온다. 이 방식은 제목/가격/주소가 바뀌었을 때 최근 본 기록에 복사본을 다시 맞출 필요가 없어 데이터가 단순하다.
 */
@Repository
@RequiredArgsConstructor
public class RecentListingRepositoryImpl implements RecentListingRepository {

  private static final String LISTING_ALIAS = "listing";

  private final MongoTemplate mongoTemplate;

  @Override
  public void upsertViewedAt(Long userId, String listingId, Instant viewedAt) {
    Query query =
        new Query(Criteria.where("userId").is(userId).and("listingId").is(toObjectId(listingId)));
    Update update =
        new Update()
            .set("userId", userId)
            .set("listingId", toObjectId(listingId))
            .set("viewedAt", viewedAt);

    // (userId, listingId) 유니크 인덱스와 같은 조건으로 upsert해 중복 기록 생성을 막는다.
    mongoTemplate.upsert(query, update, RecentListingDocument.class);
  }

  @Override
  public List<RecentListingView> findPublishedByUserIdOrderByViewedAtDesc(Long userId, int limit) {
    int safeLimit = Math.max(1, limit);
    List<Document> pipeline = new ArrayList<>();
    pipeline.add(new Document("$match", new Document("userId", userId)));
    // 같은 viewedAt이 생기는 테스트/동시 요청에서도 최신순이 흔들리지 않도록 _id를 보조 정렬한다.
    pipeline.add(new Document("$sort", new Document("viewedAt", -1).append("_id", -1)));
    pipeline.add(
        new Document(
            "$lookup",
            new Document("from", ListingDocument.COLLECTION_NAME)
                .append("localField", "listingId")
                .append("foreignField", "_id")
                .append("as", LISTING_ALIAS)));
    pipeline.add(new Document("$unwind", "$" + LISTING_ALIAS));
    pipeline.add(
        new Document(
            "$match",
            new Document(LISTING_ALIAS + ".status", Listing.ListingStatus.PUBLISHED.name())
                .append(
                    LISTING_ALIAS + ".roomOffers",
                    new Document(
                        "$elemMatch",
                        new Document("status", Listing.RoomOfferStatus.ACTIVE.name())))));
    pipeline.add(new Document("$limit", safeLimit));

    List<RecentListingView> results = new ArrayList<>();
    for (Document document :
        mongoTemplate.getCollection(RecentListingDocument.COLLECTION_NAME).aggregate(pipeline)) {
      RecentListingDocument recentDocument =
          mongoTemplate.getConverter().read(RecentListingDocument.class, document);
      ListingDocument listingDocument =
          mongoTemplate
              .getConverter()
              .read(ListingDocument.class, document.get(LISTING_ALIAS, Document.class));
      results.add(
          new RecentListingView(
              toDomain(recentDocument), ListingMongoMapper.toDomain(listingDocument)));
    }
    return results;
  }

  @Override
  public void deleteOldByUserIdKeepingLatest(Long userId, int keepCount) {
    int safeKeepCount = Math.max(0, keepCount);
    Query findOldIds =
        new Query(Criteria.where("userId").is(userId))
            .with(Sort.by(Sort.Order.desc("viewedAt"), Sort.Order.desc("_id")))
            .skip(safeKeepCount);
    findOldIds.fields().include("_id");

    List<ObjectId> oldIds =
        mongoTemplate.find(findOldIds, RecentListingDocument.class).stream()
            .map(RecentListingDocument::getId)
            .toList();
    if (oldIds.isEmpty()) {
      return;
    }

    mongoTemplate.remove(new Query(Criteria.where("_id").in(oldIds)), RecentListingDocument.class);
  }

  private static RecentListing toDomain(RecentListingDocument document) {
    return RecentListing.builder()
        .id(document.getId().toHexString())
        .userId(document.getUserId())
        .listingId(document.getListingId().toHexString())
        .viewedAt(document.getViewedAt())
        .build();
  }

  private static ObjectId toObjectId(String listingId) {
    return new ObjectId(listingId);
  }
}
