package com.kohere.listing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kohere.TestcontainersConfiguration;
import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendationCriteria;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.listing.application.ListingService;
import com.kohere.listing.application.dto.FavoriteListingResponse;
import com.kohere.listing.application.dto.FavoriteToggleResponse;
import com.kohere.listing.application.dto.FavoriteToggleResult;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Favorite;
import com.kohere.listing.domain.FavoriteRepository;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingMapSearchResult;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingSearchCondition;
import com.kohere.listing.domain.ListingSort;
import com.kohere.listing.domain.ListingType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** listings 컬렉션의 저장·인덱스·추천 조회가 실제 MongoDB에서 동작하는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.mongo.indexes-enabled=true")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class ListingMongoIntegrationTest {

  private static final String LISTING_ID = "6858e2000000000000000002";
  private static final String ROOM_OFFER_ID = "6858e2000000000000000102";

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired private ListingRepository listingRepository;
  @Autowired private FavoriteRepository favoriteRepository;
  @Autowired private ListingService listingService;
  @Autowired private ListingRecommendationService listingRecommendationService;
  @Autowired private MongoTemplate mongoTemplate;

  /** 각 테스트가 독립적으로 실행되도록 listing 관련 컬렉션을 비운다. */
  @BeforeEach
  void cleanListingCollections() {
    mongoTemplate.getCollection(ListingDocument.COLLECTION_NAME).deleteMany(new Document());
    mongoTemplate.getCollection(FavoriteDocument.COLLECTION_NAME).deleteMany(new Document());
  }

  /** 도메인 매물을 저장한 뒤 중첩 roomOffers와 GeoJSON 좌표가 보존되는지 확인한다. */
  @Test
  void save_중첩된_방상품과_GeoJson을_보존한다() {
    Listing saved = listingRepository.save(sampleListing());

    Listing found = listingRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getId()).isEqualTo(LISTING_ID);
    assertThat(found.getLocation().longitude()).isEqualTo(126.951422);
    assertThat(found.getLocation().latitude()).isEqualTo(37.459471);
    assertThat(found.getRoomOffers()).hasSize(1);
    assertThat(found.getRoomOffers().getFirst().pricing().monthlyRent()).isEqualTo(300000);
    assertThat(found.getRoomOffers().getFirst().inventory().availableCount()).isEqualTo(1);
  }

  /** 저장 경로는 MongoDB validator에 도달하기 전에 도메인 필수 구조를 검증한다. */
  @Test
  void save_도메인검증으로_방상품없는_매물을_거부한다() {
    Listing invalid = sampleListingBuilder().roomOffers(List.of()).build();

    assertThatThrownBy(() -> listingRepository.save(invalid))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("roomOffers");
  }

  /** 매물·방상품 id가 없으면 저장 어댑터가 Mongo ObjectId를 발급한다. */
  @Test
  void save_아이디가_없으면_ObjectId를_발급한다() {
    Listing listing =
        sampleListingBuilder().id(null).roomOffers(List.of(sampleRoomOffer(null))).build();

    Listing saved = listingRepository.save(listing);

    assertThat(saved.getId()).hasSize(24);
    assertThat(saved.getRoomOffers().getFirst().roomOfferId()).hasSize(24);
  }

  /** 앱 시작 시 지도·필터 조회용 MongoDB 인덱스가 생성되는지 확인한다. */
  @Test
  void initialize_지도와_필터_인덱스를_모두_생성한다() {
    Set<String> indexNames =
        mongoTemplate.indexOps(ListingDocument.class).getIndexInfo().stream()
            .map(index -> index.getName())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(indexNames)
        .contains(
            "listings_location_2dsphere",
            "listings_status_type_rent",
            "listings_landlord_status_updated",
            "listings_status_room_filter_tags",
            "listings_status_room_available_count",
            "listings_status_arc_required");

    Set<String> favoriteIndexNames =
        mongoTemplate.indexOps(FavoriteDocument.class).getIndexInfo().stream()
            .map(index -> index.getName())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(favoriteIndexNames).contains("favorites_user_listing", "favorites_user_favoritedAt");
  }

  /** seed 적재가 고정 ObjectId 저장 방식이라 재실행해도 같은 매물이 중복되지 않는지 확인한다. */
  @Test
  void seed_두번_실행해도_같은_매물이_중복되지_않는다() {
    ListingSeedRunner seedRunner = new ListingSeedRunner(listingRepository);

    seedRunner.run(null);
    seedRunner.run(null);

    assertThat(
            mongoTemplate
                .getCollection(ListingDocument.COLLECTION_NAME)
                .countDocuments(new Document("title", "고시원001")))
        .isEqualTo(1);
  }

  /** 가격과 태그 조건이 같은 roomOffer 안에서 함께 만족될 때만 매칭되는지 확인한다. */
  @Test
  void query_같은_방상품의_가격과_태그를_elemMatch로_조회한다() {
    new ListingSeedRunner(listingRepository).run(null);
    Document roomOfferCondition =
        new Document("status", "ACTIVE")
            .append("pricing.monthlyRent", new Document("$lte", 500000))
            .append(
                "filterTags",
                new Document("$all", List.of("FEMALE_ONLY", "RESIDENT_REGISTRATION")));
    Document query =
        new Document("status", "PUBLISHED")
            .append("roomOffers", new Document("$elemMatch", roomOfferCondition));

    assertThat(mongoTemplate.getCollection(ListingDocument.COLLECTION_NAME).countDocuments(query))
        .isEqualTo(1);
  }

  /** 2dsphere 인덱스로 기준 좌표 반경 내 매물을 찾을 수 있는지 확인한다. */
  @Test
  void query_2dsphere로_반경내_매물을_조회한다() {
    new ListingSeedRunner(listingRepository).run(null);
    Document geometry =
        new Document("type", "Point").append("coordinates", List.of(126.951422, 37.459471));
    Document near = new Document("$geometry", geometry).append("$maxDistance", 1000);
    Document query = new Document("location", new Document("$near", near));

    Document found =
        mongoTemplate.getCollection(ListingDocument.COLLECTION_NAME).find(query).first();
    assertThat(found).isNotNull();
    assertThat(found.getString("title")).isEqualTo("고시원001");
  }

  /** 목록 검색은 지도 범위와 필터를 모두 만족하는 공개 매물만 반환한다. */
  @Test
  void search_지도범위와_필터로_매물을_조회한다() {
    new ListingSeedRunner(listingRepository).run(null);

    PageResponse<Listing> result =
        listingRepository.search(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                500000,
                null,
                500000,
                Set.of(ListingType.GOSIWON),
                Set.of(ConditionTag.FEMALE_ONLY),
                false,
                true,
                ListingSort.PRICE_ASC,
                null,
                null,
                0,
                20));

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().getId()).isEqualTo(ListingSeedFixtures.GOSIWON_001_ID);
  }

  /** 지도 마커 조회는 같은 필터를 적용하되 페이지가 아니라 마커 후보와 전체 개수를 반환한다. */
  @Test
  void searchForMap_지도범위와_필터에_맞는_마커후보를_조회한다() {
    new ListingSeedRunner(listingRepository).run(null);

    ListingMapSearchResult result =
        listingRepository.searchForMap(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                500000,
                null,
                500000,
                Set.of(ListingType.GOSIWON),
                Set.of(ConditionTag.FEMALE_ONLY),
                false,
                true,
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20),
            500);

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.listings()).hasSize(1);
    assertThat(result.listings().getFirst().getId()).isEqualTo(ListingSeedFixtures.GOSIWON_001_ID);
  }

  /** 찜 등록·중복 등록·해제가 멱등하게 동작하고 favoriteCount가 한 번씩만 증감하는지 확인한다. */
  @Test
  void favorite_등록과_해제를_멱등하게_처리한다() {
    listingRepository.save(sampleListing());

    FavoriteToggleResult created = listingService.addFavorite(1L, LISTING_ID);
    FavoriteToggleResult duplicated = listingService.addFavorite(1L, LISTING_ID);
    FavoriteToggleResponse removed = listingService.removeFavorite(1L, LISTING_ID);
    FavoriteToggleResponse removedAgain = listingService.removeFavorite(1L, LISTING_ID);

    assertThat(created.created()).isTrue();
    assertThat(created.response()).isEqualTo(new FavoriteToggleResponse(true, 1));
    assertThat(duplicated.created()).isFalse();
    assertThat(duplicated.response()).isEqualTo(new FavoriteToggleResponse(true, 1));
    assertThat(removed).isEqualTo(new FavoriteToggleResponse(false, 0));
    assertThat(removedAgain).isEqualTo(new FavoriteToggleResponse(false, 0));
    assertThat(listingRepository.findById(LISTING_ID).orElseThrow().getFavoriteCount()).isZero();
  }

  /** 찜하지 않은 매물 해제는 성공으로 응답하되 favoriteCount를 감소시키지 않는다. */
  @Test
  void favorite_찜하지_않은_매물_해제는_count를_변경하지_않는다() {
    listingRepository.save(sampleListingBuilder().favoriteCount(3).build());

    FavoriteToggleResponse response = listingService.removeFavorite(1L, LISTING_ID);

    assertThat(response).isEqualTo(new FavoriteToggleResponse(false, 3));
    assertThat(listingRepository.findById(LISTING_ID).orElseThrow().getFavoriteCount())
        .isEqualTo(3);
  }

  /** 존재하지 않거나 ObjectId 형식이 아닌 매물은 찜 등록/해제 모두 LISTING_NOT_FOUND로 거부한다. */
  @Test
  void favorite_없는_매물이나_잘못된_id는_404로_취급한다() {
    listingRepository.save(sampleListing());

    assertThatThrownBy(() -> listingService.addFavorite(1L, "not-object-id"))
        .isInstanceOf(ListingNotFoundException.class);
    assertThatThrownBy(() -> listingService.addFavorite(1L, "6858e20000000000000000ff"))
        .isInstanceOf(ListingNotFoundException.class);
    assertThatThrownBy(() -> listingService.removeFavorite(1L, "6858e20000000000000000ff"))
        .isInstanceOf(ListingNotFoundException.class);
  }

  /** 찜 목록은 공개 매물만 최근 찜한 순으로 반환하고 비공개 상태 매물은 응답에서 제외한다. */
  @Test
  void favorite_목록은_공개매물만_최근순으로_반환한다() {
    String newerListingId = "6858e2000000000000000003";
    String pausedListingId = "6858e2000000000000000004";
    listingRepository.save(sampleListing());
    listingRepository.save(sampleListingBuilder().id(newerListingId).title("두 번째 고시원").build());
    listingRepository.save(
        sampleListingBuilder()
            .id(pausedListingId)
            .title("노출 중지 고시원")
            .status(Listing.ListingStatus.PAUSED)
            .build());
    favoriteRepository.saveIfAbsent(favorite(1L, LISTING_ID, "2026-06-24T01:00:00Z"));
    favoriteRepository.saveIfAbsent(favorite(1L, newerListingId, "2026-06-24T03:00:00Z"));
    favoriteRepository.saveIfAbsent(favorite(1L, pausedListingId, "2026-06-24T04:00:00Z"));

    PageResponse<FavoriteListingResponse> result = listingService.getMyFavorites(1L, 0, 20);

    assertThat(result.page().totalElements()).isEqualTo(2);
    assertThat(result.content())
        .extracting(FavoriteListingResponse::listingId)
        .containsExactly(newerListingId, LISTING_ID);
    assertThat(result.content()).allMatch(FavoriteListingResponse::favorited);
    assertThatThrownBy(() -> listingService.addFavorite(1L, pausedListingId))
        .isInstanceOf(ListingNotFoundException.class);
  }

  /** 찜 목록은 사용자별로 분리되고 page/size에 맞게 잘린다. */
  @Test
  void favorite_목록은_사용자별로_분리하고_페이지네이션한다() {
    String secondListingId = "6858e2000000000000000005";
    String thirdListingId = "6858e2000000000000000006";
    listingRepository.save(sampleListing());
    listingRepository.save(sampleListingBuilder().id(secondListingId).title("두 번째 고시원").build());
    listingRepository.save(sampleListingBuilder().id(thirdListingId).title("세 번째 고시원").build());
    favoriteRepository.saveIfAbsent(favorite(1L, LISTING_ID, "2026-06-24T01:00:00Z"));
    favoriteRepository.saveIfAbsent(favorite(1L, secondListingId, "2026-06-24T02:00:00Z"));
    favoriteRepository.saveIfAbsent(favorite(1L, thirdListingId, "2026-06-24T03:00:00Z"));
    favoriteRepository.saveIfAbsent(favorite(2L, LISTING_ID, "2026-06-24T04:00:00Z"));

    PageResponse<FavoriteListingResponse> firstPage = listingService.getMyFavorites(1L, 0, 2);
    PageResponse<FavoriteListingResponse> secondPage = listingService.getMyFavorites(1L, 1, 2);
    PageResponse<FavoriteListingResponse> otherUser = listingService.getMyFavorites(2L, 0, 20);

    assertThat(firstPage.page().totalElements()).isEqualTo(3);
    assertThat(firstPage.page().totalPages()).isEqualTo(2);
    assertThat(firstPage.page().hasNext()).isTrue();
    assertThat(firstPage.content())
        .extracting(FavoriteListingResponse::listingId)
        .containsExactly(thirdListingId, secondListingId);
    assertThat(secondPage.page().hasNext()).isFalse();
    assertThat(secondPage.content())
        .extracting(FavoriteListingResponse::listingId)
        .containsExactly(LISTING_ID);
    assertThat(otherUser.page().totalElements()).isEqualTo(1);
    assertThat(otherUser.content())
        .extracting(FavoriteListingResponse::listingId)
        .containsExactly(LISTING_ID);
  }

  /** 찜한 매물이 없으면 빈 content와 0개 page 메타를 정상 응답으로 반환한다. */
  @Test
  void favorite_목록이_비어도_정상_페이지를_반환한다() {
    PageResponse<FavoriteListingResponse> result = listingService.getMyFavorites(1L, 0, 20);

    assertThat(result.content()).isEmpty();
    assertThat(result.page().number()).isZero();
    assertThat(result.page().size()).isEqualTo(20);
    assertThat(result.page().totalElements()).isZero();
    assertThat(result.page().totalPages()).isZero();
    assertThat(result.page().hasNext()).isFalse();
  }

  /** 찜 목록 page/size는 다른 목록 API와 같은 범위를 강제한다. */
  @Test
  void favorite_목록_페이지_파라미터를_검증한다() {
    assertThatThrownBy(() -> listingService.getMyFavorites(1L, -1, 20))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("page");
    assertThatThrownBy(() -> listingService.getMyFavorites(1L, 0, 0))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("size");
    assertThatThrownBy(() -> listingService.getMyFavorites(1L, 0, 101))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("size");
  }

  /** 조건을 모두 만족하는 같은 방 상품이 없으면 목록 검색 결과는 비어 있어야 한다. */
  @Test
  void search_조건을_모두_만족하지_않으면_빈_목록을_반환한다() {
    new ListingSeedRunner(listingRepository).run(null);

    PageResponse<Listing> result =
        listingRepository.search(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                200000,
                null,
                null,
                Set.of(ListingType.GOSIWON),
                Set.of(ConditionTag.FEMALE_ONLY),
                false,
                null,
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20));

    assertThat(result.content()).isEmpty();
  }

  /** diagnosis 추천 조건이 listing 추천 서비스와 Mongo 조회로 연결되는지 확인한다. */
  @Test
  void recommendByCriteria_진단조건으로_매물요약을_반환한다() {
    new ListingSeedRunner(listingRepository).run(null);

    PageResponse<RecommendedListingView> result =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                500000,
                Set.of("FEMALE_ONLY", "RESIDENT_REGISTRATION"),
                "SNU",
                null,
                0,
                20,
                "recommended,desc"));

    assertThat(result.content()).hasSize(1);
    RecommendedListingView listing = result.content().getFirst();
    assertThat(listing.listingId()).isEqualTo(ListingSeedFixtures.GOSIWON_001_ID);
    assertThat(listing.monthlyRent()).isEqualTo(300000);
    assertThat(listing.lat()).isEqualTo(37.459471);
    assertThat(listing.lng()).isEqualTo(126.951422);
    assertThat(listing.conditions()).contains("FEMALE_ONLY", "RESIDENT_REGISTRATION");
  }

  /** 즉시입주 조건은 active 방 상품의 availableCount가 있을 때만 매칭되는지 확인한다. */
  @Test
  void recommendByCriteria_즉시입주조건은_availableCount가_있어야_매칭된다() {
    new ListingSeedRunner(listingRepository).run(null);

    PageResponse<RecommendedListingView> result =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL", 500000, Set.of("IMMEDIATE_MOVE_IN"), "SNU", null, 0, 20, null));

    assertThat(result.content()).isEmpty();
  }

  /** 저장·조회 테스트에서 사용할 대표 매물 도메인 객체를 만든다. */
  private static Listing sampleListing() {
    return sampleListingBuilder().build();
  }

  /** 저장·조회 테스트에서 사용할 찜 도메인 객체를 만든다. */
  private static Favorite favorite(Long userId, String listingId, String favoritedAt) {
    return Favorite.builder()
        .userId(userId)
        .listingId(listingId)
        .favoritedAt(Instant.parse(favoritedAt))
        .build();
  }

  /** 저장·조회 테스트에서 일부 필드만 바꿔 쓸 대표 매물 빌더를 만든다. */
  private static Listing.ListingBuilder sampleListingBuilder() {
    return Listing.builder()
        .id(LISTING_ID)
        .schemaVersion(1)
        .landlordId(1L)
        .title("테스트 고시원")
        .type(ListingType.GOSIWON)
        .status(Listing.ListingStatus.PUBLISHED)
        .location(new Listing.GeoPoint(126.951422, 37.459471))
        .address(new Listing.Address("SEOUL", "GWANAK_GU", "서울특별시 관악구 테스트로 1", null))
        .nearestTransit(new Listing.NearestTransit(Listing.TransitType.SUBWAY, "서울대입구역", 5))
        .nearbyPlacesDescription("편의점")
        .nearbyUniversityCodes(Set.of("SNU"))
        .building(
            new Listing.Building(
                Listing.BuildingType.VILLA, 1, 2, 4, true, true, Listing.HeatingSystem.CENTRAL))
        .propertyPolicies(new Listing.PropertyPolicies(false, true, true, true, false))
        .facilities(
            new Listing.Facilities(
                Set.of("COIN_LAUNDRY"),
                Set.of("WIFI"),
                Set.of("CCTV"),
                List.of(new Listing.CommonSpace(Listing.CommonSpaceType.SHARED_TOILET, 2)),
                Set.of("BEDDING")))
        .roomOffers(List.of(sampleRoomOffer(ROOM_OFFER_ID)))
        .featureSummary(Set.of(ConditionTag.FEMALE_ONLY))
        .descriptions(new Listing.Descriptions("테스트 설명", "Test description"))
        .extraNotes("테스트")
        .imageUrls(List.of())
        .favoriteCount(0)
        .createdAt(Instant.parse("2026-06-24T00:00:00Z"))
        .updatedAt(Instant.parse("2026-06-24T00:00:00Z"));
  }

  /** 저장·조회 테스트에서 사용할 대표 방 상품을 만든다. */
  private static Listing.RoomOffer sampleRoomOffer(String roomOfferId) {
    return new Listing.RoomOffer(
        roomOfferId,
        "스탠다드 1인실",
        Listing.RoomOfferStatus.ACTIVE,
        Listing.RentalType.MONTHLY_RENT,
        new Listing.Pricing(300000, 300000, 0, Listing.Currency.KRW),
        new Listing.Contract(
            2,
            6,
            new Listing.RefundPolicy(
                Listing.RefundPolicyCode.FULL_REFUND_BEFORE_7_DAYS, "입주 7일 전 취소 시 전액 환불")),
        new Listing.Inventory(10, 1, null),
        Listing.GenderPolicy.FEMALE_ONLY,
        Set.of(Listing.RoomFeature.SINGLE_ROOM),
        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.RESIDENT_REGISTRATION),
        List.of());
  }
}
