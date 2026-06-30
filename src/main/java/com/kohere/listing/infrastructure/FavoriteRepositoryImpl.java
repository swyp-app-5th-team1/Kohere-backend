package com.kohere.listing.infrastructure;

import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.domain.Favorite;
import com.kohere.listing.domain.FavoriteListing;
import com.kohere.listing.domain.FavoriteRepository;
import com.kohere.listing.domain.Listing;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * 찜 영속 어댑터.
 *
 * <p>도메인 포트 {@link FavoriteRepository}를 MongoDB 컬렉션으로 구현한다. 이 클래스가 맡는 책임은 세 가지다.
 *
 * <p>1. API에서 쓰는 문자열 id와 MongoDB {@link ObjectId} 사이를 변환한다.
 *
 * <p>2. {@code favorites_user_listing} 유니크 인덱스에서 발생하는 중복 저장을 {@code false} 반환으로 바꿔, 서비스가 "이미 찜한
 * 상태"를 정상 응답으로 처리할 수 있게 한다.
 *
 * <p>3. 찜 목록 조회 시 {@code favorites -> listings}를 lookup하고 {@code PUBLISHED} 매물만 남긴다. 따라서 프론트가 받는
 * {@code totalElements}와 {@code content}는 모두 실제 화면에 보여줄 수 있는 매물 기준이다.
 */
@Repository
@RequiredArgsConstructor
public class FavoriteRepositoryImpl implements FavoriteRepository {

  private static final int MAX_PAGE_SIZE = 100;
  private static final String LISTING_ALIAS = "listing";

  private final FavoriteMongoRepository mongoRepository;
  private final MongoTemplate mongoTemplate;

  @Override
  public Optional<Favorite> findByUserIdAndListingId(Long userId, String listingId) {
    if (!ObjectId.isValid(listingId)) {
      return Optional.empty();
    }
    return mongoRepository
        .findByUserIdAndListingId(userId, new ObjectId(listingId))
        .map(FavoriteRepositoryImpl::toDomain);
  }

  @Override
  public boolean saveIfAbsent(Favorite favorite) {
    try {
      mongoRepository.insert(toDocument(favorite));
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  @Override
  public boolean deleteByUserIdAndListingId(Long userId, String listingId) {
    if (!ObjectId.isValid(listingId)) {
      return false;
    }
    Query query =
        new Query(Criteria.where("userId").is(userId).and("listingId").is(new ObjectId(listingId)));
    return mongoTemplate.remove(query, FavoriteDocument.class).getDeletedCount() > 0;
  }

  @Override
  public PageResponse<FavoriteListing> findPublishedByUserIdOrderByFavoritedAtDesc(
      Long userId, int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

    long totalElements = countPublishedFavorites(userId);
    List<FavoriteListing> content = findPublishedFavorites(userId, safePage, safeSize);

    int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
    boolean hasNext = safePage + 1 < totalPages;
    return PageResponse.of(
        content, new PageInfo(safePage, safeSize, totalElements, totalPages, hasNext));
  }

  private long countPublishedFavorites(Long userId) {
    List<Document> pipeline = baseLookupPipeline(userId);
    pipeline.add(new Document("$count", "total"));

    Document result =
        mongoTemplate.getCollection(FavoriteDocument.COLLECTION_NAME).aggregate(pipeline).first();
    if (result == null) {
      return 0;
    }
    Number total = result.get("total", Number.class);
    return total == null ? 0 : total.longValue();
  }

  private List<FavoriteListing> findPublishedFavorites(Long userId, int page, int size) {
    List<Document> pipeline = baseLookupPipeline(userId);
    // favoritedAt이 같은 테스트/동시 요청에서도 응답 순서가 흔들리지 않도록 _id를 보조 정렬한다.
    pipeline.add(new Document("$sort", new Document("favoritedAt", -1).append("_id", -1)));
    pipeline.add(new Document("$skip", (long) page * size));
    pipeline.add(new Document("$limit", size));

    List<FavoriteListing> results = new ArrayList<>();
    for (Document document :
        mongoTemplate.getCollection(FavoriteDocument.COLLECTION_NAME).aggregate(pipeline)) {
      FavoriteDocument favoriteDocument =
          mongoTemplate.getConverter().read(FavoriteDocument.class, document);
      ListingDocument listingDocument =
          mongoTemplate
              .getConverter()
              .read(ListingDocument.class, document.get(LISTING_ALIAS, Document.class));
      results.add(
          new FavoriteListing(
              toDomain(favoriteDocument), ListingMongoMapper.toDomain(listingDocument)));
    }
    return results;
  }

  private static List<Document> baseLookupPipeline(Long userId) {
    // 목록의 count와 content가 같은 기준을 쓰도록 공통 파이프라인을 공유한다.
    return new ArrayList<>(
        List.of(
            new Document("$match", new Document("userId", userId)),
            new Document(
                "$lookup",
                new Document("from", ListingDocument.COLLECTION_NAME)
                    .append("localField", "listingId")
                    .append("foreignField", "_id")
                    .append("as", LISTING_ALIAS)),
            new Document("$unwind", "$" + LISTING_ALIAS),
            new Document(
                "$match",
                new Document(LISTING_ALIAS + ".status", Listing.ListingStatus.PUBLISHED.name()))));
  }

  private static Favorite toDomain(FavoriteDocument document) {
    return Favorite.builder()
        .id(document.getId().toHexString())
        .userId(document.getUserId())
        .listingId(document.getListingId().toHexString())
        .favoritedAt(document.getFavoritedAt())
        .build();
  }

  private static FavoriteDocument toDocument(Favorite favorite) {
    return FavoriteDocument.builder()
        .id(favorite.getId() == null ? null : new ObjectId(favorite.getId()))
        .userId(favorite.getUserId())
        .listingId(new ObjectId(favorite.getListingId()))
        .favoritedAt(favorite.getFavoritedAt())
        .build();
  }
}
