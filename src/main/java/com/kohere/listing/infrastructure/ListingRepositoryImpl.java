package com.kohere.listing.infrastructure;

import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** MongoDB 저장 모델을 도메인 모델로 변환하는 매물 영속 어댑터다. */
@Repository
@RequiredArgsConstructor
public class ListingRepositoryImpl implements ListingRepository {

  private static final int MAX_PAGE_SIZE = 100;

  private final ListingMongoRepository mongoRepository;
  private final MongoTemplate mongoTemplate;

  /** 올바른 ObjectId일 때만 MongoDB에서 매물을 찾아 도메인 모델로 변환한다. */
  @Override
  public Optional<Listing> findById(String listingId) {
    if (!ObjectId.isValid(listingId)) {
      return Optional.empty();
    }
    return mongoRepository.findById(new ObjectId(listingId)).map(ListingMongoMapper::toDomain);
  }

  /** 목록 기본 조회: 공개 매물 중 활성 방 상품이 하나 이상 있는 문서만 조회한다. */
  @Override
  public PageResponse<Listing> findPublished(int page, int size) {
    Criteria criteria =
        Criteria.where("status")
            .is(Listing.ListingStatus.PUBLISHED.name())
            .and("roomOffers")
            .elemMatch(Criteria.where("status").is(Listing.RoomOfferStatus.ACTIVE.name()));
    return findPage(criteria, page, size, defaultSort());
  }

  /** 진단 조건을 지역·학교·예산·방 태그 조건으로 조합해 추천 매물을 조회한다. */
  @Override
  public PageResponse<Listing> recommend(
      String region,
      int monthlyBudgetMax,
      Set<ConditionTag> conditions,
      String university,
      String district,
      int page,
      int size,
      String sort) {
    List<Criteria> rootCriteria = new ArrayList<>();
    rootCriteria.add(Criteria.where("status").is(Listing.ListingStatus.PUBLISHED.name()));
    if (region != null && !region.isBlank()) {
      rootCriteria.add(Criteria.where("address.city").is(region));
    }
    if (university != null && !university.isBlank()) {
      rootCriteria.add(Criteria.where("nearbyUniversityCodes").is(university));
    }
    if (district != null && !district.isBlank()) {
      rootCriteria.add(Criteria.where("address.district").is(district));
    }

    Criteria roomOfferCriteria = Criteria.where("status").is(Listing.RoomOfferStatus.ACTIVE.name());
    if (monthlyBudgetMax > 0) {
      roomOfferCriteria = roomOfferCriteria.and("pricing.monthlyRent").lte(monthlyBudgetMax);
    }
    if (conditions != null && !conditions.isEmpty()) {
      roomOfferCriteria =
          roomOfferCriteria.and("filterTags").all(conditions.stream().map(Enum::name).toList());
      if (conditions.contains(ConditionTag.IMMEDIATE_MOVE_IN)) {
        roomOfferCriteria = roomOfferCriteria.and("inventory.availableCount").gt(0);
      }
    }
    rootCriteria.add(Criteria.where("roomOffers").elemMatch(roomOfferCriteria));

    Criteria criteria = new Criteria().andOperator(rootCriteria.toArray(Criteria[]::new));
    return findPage(criteria, page, size, sortBy(sort));
  }

  /** 도메인 모델을 Mongo Document로 변환해 저장한 뒤 다시 도메인 모델로 반환한다. */
  @Override
  public Listing save(Listing listing) {
    ListingValidator.validateForSave(listing);
    ListingDocument saved = mongoRepository.save(ListingMongoMapper.toDocument(listing));
    return ListingMongoMapper.toDomain(saved);
  }

  /** count와 실제 조회를 함께 수행해 공통 PageResponse 형태로 묶는다. */
  private PageResponse<Listing> findPage(Criteria criteria, int page, int size, Sort sort) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

    Query countQuery = new Query(criteria);
    long totalElements = mongoTemplate.count(countQuery, ListingDocument.class);

    Query query = new Query(criteria).with(PageRequest.of(safePage, safeSize, sort));
    List<Listing> content =
        mongoTemplate.find(query, ListingDocument.class).stream()
            .map(ListingMongoMapper::toDomain)
            .toList();

    int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
    boolean hasNext = safePage + 1 < totalPages;
    return PageResponse.of(
        content, new PageInfo(safePage, safeSize, totalElements, totalPages, hasNext));
  }

  /** 요청 정렬값을 Mongo Sort로 변환한다. 현재는 가격 오름차순과 기본 추천순을 지원한다. */
  private static Sort sortBy(String sort) {
    if (sort == null || sort.isBlank()) {
      return defaultSort();
    }
    String normalized = sort.trim().toLowerCase();
    if (normalized.startsWith("price") || normalized.equals("price_asc")) {
      return Sort.by(Sort.Direction.ASC, "roomOffers.pricing.monthlyRent");
    }
    return defaultSort();
  }

  /** 기본 목록/추천 정렬: 찜 수와 최근 수정일을 우선한다. */
  private static Sort defaultSort() {
    return Sort.by(Sort.Direction.DESC, "favoriteCount")
        .and(Sort.by(Sort.Direction.DESC, "updatedAt"));
  }
}
