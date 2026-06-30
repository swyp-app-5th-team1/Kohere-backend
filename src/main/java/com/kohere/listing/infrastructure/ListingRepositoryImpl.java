package com.kohere.listing.infrastructure;

import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingMapSearchResult;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingSearchCondition;
import com.kohere.listing.domain.ListingSearchResult;
import com.kohere.listing.domain.ListingSort;
import com.kohere.listing.domain.ListingValidator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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

  /**
   * 지도 범위·가격·보증금·매물종류·옵션 조건을 적용해 목록 카드 후보를 조회한다.
   *
   * <p>MongoDB 쿼리는 먼저 조건에 맞는 방 상품을 하나 이상 가진 Listing 문서만 좁혀 온다. 그 다음 Java 코드에서 해당 Listing의 {@code
   * roomOffers}를 다시 순회하면서 실제로 조건에 맞는 방 상품을 모두 {@link ListingSearchResult}로 펼친다. 이렇게 해야 같은 고시원 안에
   * 조건을 만족하는 Green Zone이 여러 개 있을 때 목록 카드도 여러 개 만들 수 있다.
   */
  @Override
  public PageResponse<ListingSearchResult> search(ListingSearchCondition condition) {
    Criteria criteria = searchCriteria(condition);
    List<Listing> listings = findSearchCandidateListings(criteria, condition);
    List<ListingSearchResult> roomOfferResults = matchingRoomOfferResults(listings, condition);

    if (condition.sort() == ListingSort.PRICE_ASC) {
      roomOfferResults =
          roomOfferResults.stream()
              .sorted(
                  Comparator.<ListingSearchResult>comparingInt(
                          result -> result.roomOffer().pricing().monthlyRent())
                      .thenComparingInt(result -> result.roomOffer().pricing().deposit())
                      .thenComparing(result -> result.listing().getId())
                      .thenComparing(result -> result.roomOffer().roomOfferId()))
              .toList();
    }
    return pageFrom(roomOfferResults, condition.page(), condition.size());
  }

  /** 지도 SDK가 클러스터링할 수 있도록 필터된 개별 매물 좌표 후보를 상한까지만 조회한다. */
  @Override
  public ListingMapSearchResult searchForMap(ListingSearchCondition condition, int limit) {
    int safeLimit = Math.max(1, limit);
    Criteria criteria = searchCriteria(condition);

    long totalElements = mongoTemplate.count(new Query(criteria), ListingDocument.class);
    if (totalElements > safeLimit) {
      return new ListingMapSearchResult(List.of(), totalElements);
    }

    Query query = new Query(criteria).with(defaultSort()).limit(safeLimit);
    List<Listing> content =
        mongoTemplate.find(query, ListingDocument.class).stream()
            .map(ListingMongoMapper::toDomain)
            .toList();

    return new ListingMapSearchResult(content, totalElements);
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

  /**
   * 매물 찜 수를 원자적으로 1 증가시키고 변경 후 값을 반환한다.
   *
   * <p>찜 수는 목록/상세 화면에서 자주 읽는 값이라 {@code favorites}를 매번 집계하지 않고 {@code listings.favoriteCount} 캐시로
   * 들고 있다. 여러 사용자가 동시에 찜해도 값이 덮어써지지 않도록 MongoDB {@code $inc}로 숫자만 갱신한다.
   */
  @Override
  public int increaseFavoriteCount(String listingId) {
    ListingDocument updated = updateFavoriteCount(listingId, 1, false);
    if (updated == null) {
      throw new ListingNotFoundException();
    }
    return updated.getFavoriteCount();
  }

  /**
   * 매물 찜 수를 원자적으로 1 감소시키되 0 미만으로 내리지 않고 변경 후 값을 반환한다.
   *
   * <p>감소 조건에 {@code favoriteCount > 0}을 포함해 데이터가 이미 0인 상태에서는 음수가 되지 않게 막는다. 보통은 {@code favorites}
   * 문서가 실제로 삭제된 뒤에만 호출되므로 1 감소가 일어나지만, 방어적으로 현재 값을 다시 읽어 반환한다.
   */
  @Override
  public int decreaseFavoriteCount(String listingId) {
    ListingDocument updated = updateFavoriteCount(listingId, -1, true);
    if (updated != null) {
      return updated.getFavoriteCount();
    }
    return findById(listingId).map(Listing::getFavoriteCount).orElse(0);
  }

  private ListingDocument updateFavoriteCount(
      String listingId, int delta, boolean requirePositiveCount) {
    if (!ObjectId.isValid(listingId)) {
      return null;
    }
    Criteria criteria = Criteria.where("_id").is(new ObjectId(listingId));
    if (requirePositiveCount) {
      criteria = criteria.and("favoriteCount").gt(0);
    }
    return mongoTemplate.findAndModify(
        new Query(criteria),
        new Update().inc("favoriteCount", delta),
        FindAndModifyOptions.options().returnNew(true),
        ListingDocument.class);
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

  /** 검색 조건 객체를 MongoDB Criteria로 변환한다. */
  private static Criteria searchCriteria(ListingSearchCondition condition) {
    List<Criteria> rootCriteria = new ArrayList<>();
    rootCriteria.add(Criteria.where("status").is(Listing.ListingStatus.PUBLISHED.name()));

    if (condition.bounds() != null) {
      rootCriteria.add(Criteria.where("location").intersects(toPolygon(condition.bounds())));
    }
    if (!condition.types().isEmpty()) {
      rootCriteria.add(
          Criteria.where("type").in(condition.types().stream().map(Enum::name).toList()));
    }
    if (Boolean.TRUE.equals(condition.arcRequired())) {
      rootCriteria.add(Criteria.where("propertyPolicies.arcRequired").is(true));
    }

    rootCriteria.add(Criteria.where("roomOffers").elemMatch(roomOfferCriteria(condition)));
    return new Criteria().andOperator(rootCriteria.toArray(Criteria[]::new));
  }

  /**
   * 방 상품 배열 안에서 같은 방 상품이 가격·보증금·옵션을 모두 만족하게 만드는 MongoDB 조건이다.
   *
   * <p>이 조건은 Listing 문서를 빠르게 좁히기 위한 1차 필터다. 목록 응답은 이후 {@link #matchesRoomOffer}로 다시 한 번 같은 기준을 적용해서
   * 조건에 맞는 방 상품만 카드로 펼친다.
   */
  private static Criteria roomOfferCriteria(ListingSearchCondition condition) {
    List<Criteria> criteria = new ArrayList<>();
    criteria.add(Criteria.where("status").is(Listing.RoomOfferStatus.ACTIVE.name()));
    if (condition.minBudget() != null) {
      criteria.add(Criteria.where("pricing.monthlyRent").gte(condition.minBudget()));
    }
    if (condition.maxBudget() != null) {
      criteria.add(Criteria.where("pricing.monthlyRent").lte(condition.maxBudget()));
    }
    if (condition.minDeposit() != null) {
      criteria.add(Criteria.where("pricing.deposit").gte(condition.minDeposit()));
    }
    if (condition.maxDeposit() != null) {
      criteria.add(Criteria.where("pricing.deposit").lte(condition.maxDeposit()));
    }

    Set<ConditionTag> effectiveConditions = condition.effectiveConditions();
    if (!effectiveConditions.isEmpty()) {
      criteria.add(
          Criteria.where("filterTags").all(effectiveConditions.stream().map(Enum::name).toList()));
      if (effectiveConditions.contains(ConditionTag.IMMEDIATE_MOVE_IN)) {
        criteria.add(Criteria.where("inventory.availableCount").gt(0));
      }
    }
    return new Criteria().andOperator(criteria.toArray(Criteria[]::new));
  }

  /** 남서·북동 좌표로 만든 네모를 MongoDB GeoJSON Polygon으로 바꾼다. */
  private static GeoJsonPolygon toPolygon(ListingSearchCondition.BoundingBox bounds) {
    return new GeoJsonPolygon(
        List.of(
            new Point(bounds.swLng(), bounds.swLat()),
            new Point(bounds.swLng(), bounds.neLat()),
            new Point(bounds.neLng(), bounds.neLat()),
            new Point(bounds.neLng(), bounds.swLat()),
            new Point(bounds.swLng(), bounds.swLat())));
  }

  /**
   * 목록 조회의 1차 후보 Listing을 가져온다.
   *
   * <p>거리순은 Listing 위치 기준이므로 Listing을 먼저 거리순으로 정렬한다. 가격순은 방 상품 기준으로 다시 정렬해야 정확하므로 여기서는 기본 추천순으로 후보를
   * 가져오고, {@link #search(ListingSearchCondition)}에서 펼친 방 상품 결과를 월세 기준으로 다시 정렬한다.
   */
  private List<Listing> findSearchCandidateListings(
      Criteria criteria, ListingSearchCondition condition) {
    Query query = new Query(criteria);
    if (condition.sort() == ListingSort.DISTANCE) {
      return mongoTemplate.find(query, ListingDocument.class).stream()
          .map(ListingMongoMapper::toDomain)
          .sorted(Comparator.comparingDouble(listing -> distanceSquared(listing, condition)))
          .toList();
    }
    return mongoTemplate.find(query.with(defaultSort()), ListingDocument.class).stream()
        .map(ListingMongoMapper::toDomain)
        .toList();
  }

  /**
   * Listing 문서 안의 roomOffers를 목록 카드 단위 결과로 펼친다.
   *
   * <p>필터가 없으면 활성 방 상품이 모두 카드가 되고, 필터가 있으면 가격·보증금·옵션·재고 조건을 모두 만족하는 활성 방 상품만 카드가 된다.
   */
  private static List<ListingSearchResult> matchingRoomOfferResults(
      List<Listing> listings, ListingSearchCondition condition) {
    return listings.stream()
        .flatMap(
            listing ->
                listing.getRoomOffers().stream()
                    .filter(roomOffer -> matchesRoomOffer(roomOffer, condition))
                    .map(roomOffer -> new ListingSearchResult(listing, roomOffer)))
        .toList();
  }

  /**
   * 방 상품 하나가 목록 필터를 실제로 만족하는지 확인한다.
   *
   * <p>MongoDB의 {@code elemMatch}와 같은 기준을 Java에서도 적용한다. Listing 문서가 조건을 통과했더라도, 그 문서 안에는 조건을 만족하지
   * 않는 다른 방 상품도 함께 들어 있으므로 목록 카드로 펼치기 전에 이 검사가 반드시 필요하다.
   */
  private static boolean matchesRoomOffer(
      Listing.RoomOffer roomOffer, ListingSearchCondition condition) {
    if (roomOffer.status() != Listing.RoomOfferStatus.ACTIVE) {
      return false;
    }
    if (condition.minBudget() != null
        && roomOffer.pricing().monthlyRent() < condition.minBudget()) {
      return false;
    }
    if (condition.maxBudget() != null
        && roomOffer.pricing().monthlyRent() > condition.maxBudget()) {
      return false;
    }
    if (condition.minDeposit() != null && roomOffer.pricing().deposit() < condition.minDeposit()) {
      return false;
    }
    if (condition.maxDeposit() != null && roomOffer.pricing().deposit() > condition.maxDeposit()) {
      return false;
    }

    Set<ConditionTag> effectiveConditions = condition.effectiveConditions();
    if (!roomOffer.filterTags().containsAll(effectiveConditions)) {
      return false;
    }
    return !effectiveConditions.contains(ConditionTag.IMMEDIATE_MOVE_IN)
        || roomOffer.inventory().availableCount() > 0;
  }

  /** 가까운 순서 비교에만 쓰는 간단한 거리값이다. 실제 표시 거리는 application 계층에서 미터로 계산한다. */
  private static double distanceSquared(Listing listing, ListingSearchCondition condition) {
    double lat = listing.getLocation().latitude() - condition.centerLat();
    double lng = listing.getLocation().longitude() - condition.centerLng();
    return lat * lat + lng * lng;
  }

  /**
   * 이미 메모리에 있는 결과를 공통 PageResponse 형태로 자른다.
   *
   * <p>목록 조회에서는 Listing 문서 개수가 아니라 펼쳐진 방 상품 카드 개수가 {@code totalElements}가 되어야 한다. 그래서 MongoDB count
   * 결과를 그대로 쓰지 않고, Java에서 펼친 결과 목록을 기준으로 페이지 메타를 만든다.
   */
  private static <T> PageResponse<T> pageFrom(List<T> content, int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    int from = Math.min(safePage * safeSize, content.size());
    int to = Math.min(from + safeSize, content.size());
    long totalElements = content.size();
    int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
    boolean hasNext = safePage + 1 < totalPages;
    return PageResponse.of(
        content.subList(from, to),
        new PageInfo(safePage, safeSize, totalElements, totalPages, hasNext));
  }

  /** 문자열 정렬값을 받는 진단 추천 조회와 호환되도록 남겨 둔 정렬 변환이다. */
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
