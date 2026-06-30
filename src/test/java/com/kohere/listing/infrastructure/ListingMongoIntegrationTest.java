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
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Favorite;
import com.kohere.listing.domain.FavoriteRepository;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingInvalidSortParamException;
import com.kohere.listing.domain.ListingMapSearchResult;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingSearchCondition;
import com.kohere.listing.domain.ListingSearchResult;
import com.kohere.listing.domain.ListingSort;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.presentation.dto.ListingSearchRequest;
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

  /** 목록 검색은 지도 범위와 필터를 모두 만족하는 방 상품 카드만 반환한다. */
  @Test
  void search_지도범위와_필터로_매물을_조회한다() {
    new ListingSeedRunner(listingRepository).run(null);

    PageResponse<ListingSearchResult> result =
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
                ListingSort.PRICE_ASC,
                null,
                null,
                0,
                20));

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().listing().getId())
        .isEqualTo(ListingSeedFixtures.GOSIWON_001_ID);
    assertThat(result.content().getFirst().roomOffer().roomOfferId())
        .isEqualTo(ListingSeedFixtures.GOSIWON_001_ROOM_OFFER_ID);
  }

  /** 필터가 없으면 공개 매물 안의 모든 active roomOffer가 각각 목록 카드가 된다. */
  @Test
  void search_필터가_없으면_active_방상품을_모두_반환한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000201",
                        "Green Zone 1",
                        490000,
                        1000000,
                        3,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000202",
                        "Green Zone 2",
                        550000,
                        1000000,
                        2,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000203",
                        "Green Zone 3",
                        450000,
                        500000,
                        1,
                        Set.of(ConditionTag.PRIVATE_TOILET)),
                    roomOffer(
                        "6858e2000000000000000204",
                        "Inactive Zone",
                        Listing.RoomOfferStatus.INACTIVE,
                        300000,
                        300000,
                        1,
                        Set.of(ConditionTag.FEMALE_ONLY))))
            .build());

    PageResponse<ListingSearchResult> result =
        listingRepository.search(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                null,
                null,
                null,
                Set.of(ListingType.GOSIWON),
                Set.of(),
                null,
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20));

    assertThat(result.page().totalElements()).isEqualTo(3);
    assertThat(result.content())
        .extracting(searchResult -> searchResult.roomOffer().name())
        .containsExactly("Green Zone 1", "Green Zone 2", "Green Zone 3");
  }

  /** 필터가 있으면 조건에 맞는 active roomOffer만 남기고, PRICE_ASC는 그 방 상품들의 월세 기준으로 정렬한다. */
  @Test
  void search_필터에_맞는_방상품을_월세순으로_반환한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000301",
                        "Green Zone 1",
                        490000,
                        1000000,
                        3,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000302",
                        "Green Zone 2",
                        550000,
                        1000000,
                        2,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000303",
                        "Green Zone 3",
                        450000,
                        500000,
                        1,
                        Set.of(ConditionTag.PRIVATE_TOILET))))
            .build());

    PageResponse<ListingSearchResult> result =
        listingRepository.search(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                480000,
                600000,
                null,
                null,
                Set.of(ListingType.GOSIWON),
                Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH),
                null,
                ListingSort.PRICE_ASC,
                null,
                null,
                0,
                20));

    assertThat(result.page().totalElements()).isEqualTo(2);
    assertThat(result.content())
        .extracting(searchResult -> searchResult.roomOffer().name())
        .containsExactly("Green Zone 1", "Green Zone 2");
    assertThat(result.content())
        .extracting(searchResult -> searchResult.roomOffer().pricing().monthlyRent())
        .containsExactly(490000, 550000);
  }

  /** 목록 페이지 정보는 Listing 문서 수가 아니라 펼쳐진 roomOffer 카드 수를 기준으로 계산한다. */
  @Test
  void search_페이지네이션은_방상품_카드_기준으로_계산한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000401",
                        "Green Zone 1",
                        490000,
                        1000000,
                        3,
                        Set.of(ConditionTag.FEMALE_ONLY)),
                    roomOffer(
                        "6858e2000000000000000402",
                        "Green Zone 2",
                        550000,
                        1000000,
                        2,
                        Set.of(ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000403",
                        "Green Zone 3",
                        600000,
                        1000000,
                        1,
                        Set.of(ConditionTag.PRIVATE_TOILET))))
            .build());

    ListingSearchCondition firstPageCondition =
        new ListingSearchCondition(
            new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
            null,
            null,
            null,
            null,
            Set.of(ListingType.GOSIWON),
            Set.of(),
            null,
            ListingSort.RECOMMENDED,
            null,
            null,
            0,
            2);
    ListingSearchCondition secondPageCondition =
        new ListingSearchCondition(
            firstPageCondition.bounds(),
            null,
            null,
            null,
            null,
            Set.of(ListingType.GOSIWON),
            Set.of(),
            null,
            ListingSort.RECOMMENDED,
            null,
            null,
            1,
            2);

    PageResponse<ListingSearchResult> firstPage = listingRepository.search(firstPageCondition);
    PageResponse<ListingSearchResult> secondPage = listingRepository.search(secondPageCondition);

    assertThat(firstPage.page().totalElements()).isEqualTo(3);
    assertThat(firstPage.page().totalPages()).isEqualTo(2);
    assertThat(firstPage.page().hasNext()).isTrue();
    assertThat(firstPage.content())
        .extracting(searchResult -> searchResult.roomOffer().name())
        .containsExactly("Green Zone 1", "Green Zone 2");
    assertThat(secondPage.page().hasNext()).isFalse();
    assertThat(secondPage.content())
        .extracting(searchResult -> searchResult.roomOffer().name())
        .containsExactly("Green Zone 3");
  }

  /** 즉시입주 조건은 태그만으로 충분하지 않고, 같은 roomOffer의 availableCount가 1 이상이어야 한다. */
  @Test
  void search_즉시입주조건은_계약가능수량이_있는_방상품만_반환한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000501",
                        "Sold Out Zone",
                        490000,
                        1000000,
                        0,
                        Set.of(ConditionTag.IMMEDIATE_MOVE_IN, ConditionTag.FEMALE_ONLY)),
                    roomOffer(
                        "6858e2000000000000000502",
                        "Move In Now Zone",
                        550000,
                        1000000,
                        2,
                        Set.of(ConditionTag.IMMEDIATE_MOVE_IN, ConditionTag.FEMALE_ONLY))))
            .build());

    PageResponse<ListingSearchResult> result =
        listingRepository.search(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                null,
                null,
                null,
                Set.of(ListingType.GOSIWON),
                Set.of(ConditionTag.IMMEDIATE_MOVE_IN),
                null,
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20));

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().roomOffer().name()).isEqualTo("Move In Now Zone");
    assertThat(result.content().getFirst().roomOffer().inventory().availableCount()).isEqualTo(2);
  }

  /** 전입신고 가능 여부는 별도 boolean 파라미터가 아니라 conditions 태그로 필터링한다. */
  @Test
  void search_전입신고가능은_conditions_태그로_필터링한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000801",
                        "Registration Zone",
                        490000,
                        1000000,
                        3,
                        Set.of(ConditionTag.RESIDENT_REGISTRATION)),
                    roomOffer(
                        "6858e2000000000000000802",
                        "No Registration Zone",
                        450000,
                        500000,
                        3,
                        Set.of(ConditionTag.FEMALE_ONLY))))
            .build());

    PageResponse<ListingSearchResult> result =
        listingRepository.search(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                null,
                null,
                null,
                Set.of(ListingType.GOSIWON),
                Set.of(ConditionTag.RESIDENT_REGISTRATION),
                null,
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20));

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().roomOffer().name()).isEqualTo("Registration Zone");
  }

  /** ARC 필터는 true일 때만 적용하고, false는 ARC 여부와 상관없이 조회한다. */
  @Test
  void search_arcRequired_false는_ARC_조건을_적용하지_않고_true만_필터링한다() {
    String arcOptionalListingId = "6858e2000000000000000007";
    String arcRequiredListingId = "6858e2000000000000000008";
    listingRepository.save(
        sampleListingBuilder()
            .id(arcOptionalListingId)
            .roomOffers(
                List.of(
                    roomOffer("6858e2000000000000000901", "ARC Optional", 490000, 0, 3, Set.of())))
            .propertyPolicies(new Listing.PropertyPolicies(false, true, true, true, false))
            .build());
    listingRepository.save(
        sampleListingBuilder()
            .id(arcRequiredListingId)
            .roomOffers(
                List.of(
                    roomOffer("6858e2000000000000000902", "ARC Required", 490000, 0, 3, Set.of())))
            .propertyPolicies(new Listing.PropertyPolicies(true, true, true, true, false))
            .build());

    ListingSearchCondition withoutArcFilter =
        new ListingSearchCondition(
            new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
            null,
            null,
            null,
            null,
            Set.of(ListingType.GOSIWON),
            Set.of(),
            false,
            ListingSort.RECOMMENDED,
            null,
            null,
            0,
            20);
    ListingSearchCondition onlyArcRequired =
        new ListingSearchCondition(
            withoutArcFilter.bounds(),
            null,
            null,
            null,
            null,
            Set.of(ListingType.GOSIWON),
            Set.of(),
            true,
            ListingSort.RECOMMENDED,
            null,
            null,
            0,
            20);

    PageResponse<ListingSearchResult> unfiltered = listingRepository.search(withoutArcFilter);
    PageResponse<ListingSearchResult> requiredOnly = listingRepository.search(onlyArcRequired);

    assertThat(unfiltered.content())
        .extracting(searchResult -> searchResult.listing().getId())
        .containsExactlyInAnyOrder(arcOptionalListingId, arcRequiredListingId);
    assertThat(requiredOnly.content())
        .extracting(searchResult -> searchResult.listing().getId())
        .containsExactly(arcRequiredListingId);
  }

  /** 서비스 응답은 목록 카드가 어떤 roomOffer를 의미하는지 프론트가 알 수 있게 식별자와 재고를 함께 내려준다. */
  @Test
  void getListings_방상품_카드_응답에_roomOffer_정보를_포함한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000601",
                        "Green Zone 1",
                        490000,
                        1000000,
                        3,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH))))
            .build());
    ListingSearchRequest request = new ListingSearchRequest();
    request.setSwLat(37.45);
    request.setSwLng(126.90);
    request.setNeLat(37.50);
    request.setNeLng(127.00);
    request.setConditions(Set.of(ConditionTag.FEMALE_ONLY));
    request.setType(Set.of(ListingType.GOSIWON));

    PageResponse<ListingSummaryResponse> result = listingService.getListings(request);

    assertThat(result.content()).hasSize(1);
    ListingSummaryResponse card = result.content().getFirst();
    assertThat(card.listingId()).isEqualTo(LISTING_ID);
    assertThat(card.roomOfferId()).isEqualTo("6858e2000000000000000601");
    assertThat(card.roomOfferName()).isEqualTo("Green Zone 1");
    assertThat(card.monthlyRent()).isEqualTo(490000);
    assertThat(card.availableCount()).isEqualTo(3);
    assertThat(card.conditions()).contains(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH);
  }

  /** DISTANCE 정렬은 프론트가 보낸 중심 좌표가 아니라 bbox의 원본 중심점을 기준으로 계산한다. */
  @Test
  void getListings_DISTANCE는_bbox_중심점_기준으로_정렬하고_거리값을_반환한다() {
    String nearListingId = "6858e2000000000000000009";
    String farListingId = "6858e200000000000000000a";
    listingRepository.save(
        sampleListingBuilder()
            .id(farListingId)
            .location(new Listing.GeoPoint(127.19, 37.19))
            .roomOffers(
                List.of(roomOffer("6858e2000000000000000a01", "Far Zone", 490000, 0, 3, Set.of())))
            .build());
    listingRepository.save(
        sampleListingBuilder()
            .id(nearListingId)
            .location(new Listing.GeoPoint(127.101, 37.101))
            .roomOffers(
                List.of(roomOffer("6858e2000000000000000a02", "Near Zone", 490000, 0, 3, Set.of())))
            .build());
    ListingSearchRequest request = new ListingSearchRequest();
    request.setSwLat(37.0);
    request.setSwLng(127.0);
    request.setNeLat(37.2);
    request.setNeLng(127.2);
    request.setSort(ListingSort.DISTANCE);

    PageResponse<ListingSummaryResponse> result = listingService.getListings(request);

    assertThat(result.content())
        .extracting(ListingSummaryResponse::listingId)
        .containsExactly(nearListingId, farListingId);
    assertThat(result.content()).allSatisfy(card -> assertThat(card.distanceMeters()).isNotNull());
  }

  /** DISTANCE 정렬은 bbox 중심점을 써야 하므로 bbox 없이 요청하면 거부한다. */
  @Test
  void getListings_DISTANCE는_bbox가_없으면_예외를_반환한다() {
    ListingSearchRequest request = new ListingSearchRequest();
    request.setSort(ListingSort.DISTANCE);

    assertThatThrownBy(() -> listingService.getListings(request))
        .isInstanceOf(ListingInvalidSortParamException.class);
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

  /** 지도 마커는 roomOffer 카드 수가 아니라 조건에 맞는 Listing 건물 수 기준으로 1개만 반환한다. */
  @Test
  void searchForMap_여러_방상품이_매칭되어도_마커는_매물당_하나만_반환한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000701",
                        "Green Zone 1",
                        490000,
                        1000000,
                        3,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000702",
                        "Green Zone 2",
                        550000,
                        1000000,
                        2,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH))))
            .build());

    ListingMapSearchResult result =
        listingRepository.searchForMap(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                null,
                null,
                null,
                Set.of(ListingType.GOSIWON),
                Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH),
                null,
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20),
            500);

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.listings()).hasSize(1);
    assertThat(result.listings().getFirst().getId()).isEqualTo(LISTING_ID);
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

    PageResponse<ListingSearchResult> result =
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
    return roomOffer(
        roomOfferId,
        "스탠다드 1인실",
        300000,
        300000,
        1,
        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.RESIDENT_REGISTRATION));
  }

  /**
   * 목록 조회 테스트에서 사용할 방 상품 묶음을 만든다.
   *
   * <p>roomOffer는 실제 방 1개가 아니라 같은 가격·조건을 가진 방 묶음이다. 테스트에서는 이름·가격·재고·태그만 바꿔 여러 카드가 어떻게 펼쳐지는지 확인한다.
   */
  private static Listing.RoomOffer roomOffer(
      String roomOfferId,
      String name,
      int monthlyRent,
      int deposit,
      int availableCount,
      Set<ConditionTag> filterTags) {
    return roomOffer(
        roomOfferId,
        name,
        Listing.RoomOfferStatus.ACTIVE,
        monthlyRent,
        deposit,
        availableCount,
        filterTags);
  }

  /**
   * 목록 조회 테스트에서 상태까지 지정할 수 있는 방 상품 묶음을 만든다.
   *
   * <p>INACTIVE 방 상품이 목록 카드로 펼쳐지지 않는지 검증할 때 사용한다.
   */
  private static Listing.RoomOffer roomOffer(
      String roomOfferId,
      String name,
      Listing.RoomOfferStatus status,
      int monthlyRent,
      int deposit,
      int availableCount,
      Set<ConditionTag> filterTags) {
    return new Listing.RoomOffer(
        roomOfferId,
        name,
        status,
        Listing.RentalType.MONTHLY_RENT,
        new Listing.Pricing(monthlyRent, deposit, 0, Listing.Currency.KRW),
        new Listing.Contract(
            2,
            6,
            new Listing.RefundPolicy(
                Listing.RefundPolicyCode.FULL_REFUND_BEFORE_7_DAYS, "입주 7일 전 취소 시 전액 환불")),
        new Listing.Inventory(10, availableCount, null),
        Listing.GenderPolicy.FEMALE_ONLY,
        Set.of(Listing.RoomFeature.SINGLE_ROOM),
        filterTags,
        List.of());
  }
}
