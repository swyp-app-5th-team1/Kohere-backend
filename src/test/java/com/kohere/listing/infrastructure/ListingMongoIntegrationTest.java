package com.kohere.listing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

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
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.application.dto.RecentListingResponse;
import com.kohere.listing.application.dto.RecentListingsResponse;
import com.kohere.listing.domain.ArcRequirement;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.KitchenFacility;
import com.kohere.listing.domain.LaundryFacility;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingInvalidSortParamException;
import com.kohere.listing.domain.ListingMapSearchResult;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingSearchCondition;
import com.kohere.listing.domain.ListingSearchResult;
import com.kohere.listing.domain.ListingSort;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.LivingAmenity;
import com.kohere.listing.domain.LocalizedText;
import com.kohere.listing.domain.Nationality;
import com.kohere.listing.domain.NearbyFacility;
import com.kohere.listing.domain.ProvidedSupply;
import com.kohere.listing.domain.SecurityFeature;
import com.kohere.listing.domain.SupportedLanguage;
import com.kohere.listing.domain.favorite.Favorite;
import com.kohere.listing.domain.favorite.FavoriteRepository;
import com.kohere.listing.domain.nearby.Coordinate;
import com.kohere.listing.domain.recent.RecentListingRepository;
import com.kohere.listing.domain.university.UniversityRepository;
import com.kohere.listing.presentation.dto.ListingSearchRequest;
import com.kohere.user.api.UserAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
  private static final String LISTINGS_COLLECTION = "listings";
  private static final String FAVORITES_COLLECTION = "favorites";
  private static final String RECENT_LISTINGS_COLLECTION = "recentListings";
  private static final String LISTING_CATALOG_COLLECTION = "listingCatalog";
  private static final String UNIVERSITIES_COLLECTION = "universities";

  /** 등록이 인근 대학을 고르는 반경이다({@code ListingRegisterService}와 같은 값). */
  private static final int UNIVERSITY_RADIUS_METERS = 2_000;

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired private ListingRepository listingRepository;
  @Autowired private FavoriteRepository favoriteRepository;
  @Autowired private RecentListingRepository recentListingRepository;
  @Autowired private ListingService listingService;
  @Autowired private ListingRecommendationService listingRecommendationService;
  @Autowired private UniversityRepository universityRepository;
  @Autowired private MongoTemplate mongoTemplate;
  @MockitoBean private UserAccountService userAccountService;

  /** 각 테스트가 독립적으로 실행되도록 listing 관련 컬렉션을 비우고 v4 번역 사전을 다시 심는다. */
  @BeforeEach
  void cleanListingCollections() {
    given(userAccountService.getLanguage(anyLong())).willReturn("en");
    // 찜·최근 본 매물에 사용자용 API 허용 목록 게이트(세입자·임대인만 통과)가 붙었다. 스텁하지 않으면
    // mock 이 null 을 돌려줘 403 이 나고, 정작 검증하려던 저장소 동작에 닿지 못한다.
    given(userAccountService.getUserType(anyLong())).willReturn("TENANT");
    mongoTemplate.getCollection(LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(FAVORITES_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(RECENT_LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(LISTING_CATALOG_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(UNIVERSITIES_COLLECTION).deleteMany(new Document());
    // 응답의 label 자리가 코드값으로 새지 않도록 운영과 같은 정본 카탈로그를 심는다.
    ListingTestSeeds.seedCatalog(mongoTemplate, LISTING_CATALOG_COLLECTION);
  }

  /**
   * 임대인 소유 매물을 상태와 무관하게, 최근 수정순으로 돌려준다(US-3-8).
   *
   * <p>세입자 조회 3종이 {@code PUBLISHED}를 고정하는 것과 달리 이 경로는 {@code landlordId}로 먼저 좁히므로 상태를 열어도 비공개 매물이
   * 새지 않는다. 그 「먼저 좁힌다」가 실제로 성립하는지가 이 테스트의 핵심이다.
   */
  @Test
  void findByLandlord_소유_매물만_상태무관_최근수정순으로_돌려준다() {
    Instant base = Instant.parse("2026-08-01T00:00:00Z");
    listingRepository.save(
        landlordListing(1, 100L, Listing.ListingStatus.PENDING, base.plusSeconds(10)));
    listingRepository.save(
        landlordListing(2, 100L, Listing.ListingStatus.PUBLISHED, base.plusSeconds(30)));
    listingRepository.save(
        landlordListing(3, 100L, Listing.ListingStatus.UPDATE_PENDING, base.plusSeconds(20)));
    // 다른 임대인의 매물 — 소유권 필터가 살아 있으면 절대 섞이지 않는다.
    listingRepository.save(
        landlordListing(4, 200L, Listing.ListingStatus.PUBLISHED, base.plusSeconds(40)));

    PageResponse<Listing> found = listingRepository.findByLandlord(100L, Set.of(), 0, 20);

    assertThat(found.page().totalElements()).isEqualTo(3);
    assertThat(found.content())
        .extracting(Listing::getStatus)
        .containsExactly(
            Listing.ListingStatus.PUBLISHED,
            Listing.ListingStatus.UPDATE_PENDING,
            Listing.ListingStatus.PENDING);
  }

  /** 상태 필터는 여러 값을 받고, 비어 있으면 조건 자체를 걸지 않는다. */
  @Test
  void findByLandlord_상태필터는_여러값을_받는다() {
    Instant base = Instant.parse("2026-08-01T00:00:00Z");
    listingRepository.save(landlordListing(1, 100L, Listing.ListingStatus.PENDING, base));
    listingRepository.save(
        landlordListing(2, 100L, Listing.ListingStatus.REJECTED, base.plusSeconds(10)));
    listingRepository.save(
        landlordListing(3, 100L, Listing.ListingStatus.PUBLISHED, base.plusSeconds(20)));

    PageResponse<Listing> found =
        listingRepository.findByLandlord(
            100L, Set.of(Listing.ListingStatus.PENDING, Listing.ListingStatus.REJECTED), 0, 20);

    assertThat(found.page().totalElements()).isEqualTo(2);
    assertThat(found.content())
        .extracting(Listing::getStatus)
        .containsExactly(Listing.ListingStatus.REJECTED, Listing.ListingStatus.PENDING);
  }

  /**
   * 조건부 교체는 읽은 시점의 상태가 그대로일 때만 쓴다.
   *
   * <p>매물 문서에는 낙관적 락 필드가 없고 저장이 문서 전체 교체라, 조건이 없으면 나중에 쓴 쪽이 앞의 변경을 <b>소리 없이</b> 지운다. 임대인 수정은 읽기와 저장
   * 사이에 사진 확정 복사가 끼어 그 창이 넓다.
   */
  @Test
  void saveIfStatus_기대상태가_같을때만_교체한다() {
    Listing saved = listingRepository.save(sampleListing());

    Optional<Listing> replaced =
        listingRepository.saveIfStatus(
            saved.toBuilder().title(localized("조건이 맞아 교체된다")).build(), saved.getStatus());

    assertThat(replaced).isPresent();
    assertThat(listingRepository.findById(saved.getId()).orElseThrow().getTitle().ko())
        .isEqualTo("조건이 맞아 교체된다");
  }

  /** 그 사이 누가 상태를 바꿨으면 아무것도 쓰지 않고 빈 값을 돌려준다 — 409의 근거다. */
  @Test
  void saveIfStatus_기대상태가_다르면_아무것도_쓰지_않는다() {
    // 임대인이 읽은 시점의 상태는 REJECTED다.
    Listing read =
        listingRepository.save(
            sampleListing().toBuilder().status(Listing.ListingStatus.REJECTED).build());
    // 그 사이 관리자가 심사를 끝냈다.
    listingRepository.save(read.toBuilder().status(Listing.ListingStatus.PUBLISHED).build());

    Optional<Listing> replaced =
        listingRepository.saveIfStatus(
            read.toBuilder().title(localized("덮어써서는 안 된다")).build(), read.getStatus());

    assertThat(replaced).isEmpty();
    Listing untouched = listingRepository.findById(read.getId()).orElseThrow();
    assertThat(untouched.getStatus()).isEqualTo(Listing.ListingStatus.PUBLISHED);
    assertThat(untouched.getTitle().ko()).isNotEqualTo("덮어써서는 안 된다");
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
    assertThat(found.getRoomOffers().getFirst().pricing().deposit()).isEqualTo(300000);
    assertThat(found.getRoomOffers().getFirst().contract().minStayMonths()).isEqualTo(2);
    assertThat(found.getRoomOffers().getFirst().contract().maxStayMonths()).isEqualTo(6);
  }

  /** 저장 경로는 MongoDB validator에 도달하기 전에 도메인 필수 구조를 검증한다. */
  @Test
  void save_도메인검증으로_방상품없는_매물을_거부한다() {
    Listing invalid = sampleListingBuilder().roomOffers(List.of()).build();

    assertThatThrownBy(() -> listingRepository.save(invalid))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("roomOffers");
  }

  /**
   * 좌표 없는 매물은 저장 경로에서 막힌다(ADR-0042 · changeUnit {@code 0116}).
   *
   * <p>배포 환경에서는 MongoDB validator의 {@code required}가 같은 것을 한 겹 더 막지만, 테스트는 Mongock을 끄고 돌아
   * validator가 걸리지 않는다 — 그래서 여기서 확인하는 것은 도메인 검증이다.
   */
  @Test
  void save_도메인검증으로_좌표없는_매물을_거부한다() {
    Listing invalid = sampleListingBuilder().location(null).build();

    assertThatThrownBy(() -> listingRepository.save(invalid))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("location");
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
        mongoTemplate.indexOps(LISTINGS_COLLECTION).getIndexInfo().stream()
            .map(index -> index.getName())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(indexNames)
        .contains(
            "listings_location_2dsphere",
            "listings_status_type_rent",
            "listings_landlord_status_updated",
            "listings_status_room_filter_tags",
            "listings_status_arc_requirement");

    Set<String> favoriteIndexNames =
        mongoTemplate.indexOps(FAVORITES_COLLECTION).getIndexInfo().stream()
            .map(index -> index.getName())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(favoriteIndexNames).contains("favorites_user_listing", "favorites_user_favoritedAt");

    Set<String> recentListingIndexNames =
        mongoTemplate.indexOps(RECENT_LISTINGS_COLLECTION).getIndexInfo().stream()
            .map(index -> index.getName())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(recentListingIndexNames)
        .contains("recentListings_user_listing", "recentListings_user_viewedAt");

    Set<String> universityIndexNames =
        mongoTemplate.indexOps(UNIVERSITIES_COLLECTION).getIndexInfo().stream()
            .map(index -> index.getName())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(universityIndexNames).contains("universities_location_2dsphere");
  }

  /** 가격과 태그 조건이 같은 roomOffer 안에서 함께 만족될 때만 매칭되는지 확인한다. */
  @Test
  void query_같은_방상품의_가격과_태그를_elemMatch로_조회한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);
    Document roomOfferCondition =
        new Document("status", "ACTIVE")
            .append("pricing.monthlyRent", new Document("$lte", 500000))
            .append(
                "filterTags", new Document("$all", List.of("FEMALE_ONLY", "ADDRESS_REGISTRATION")));
    Document query =
        new Document("status", "PUBLISHED")
            .append("roomOffers", new Document("$elemMatch", roomOfferCondition));

    assertThat(mongoTemplate.getCollection(LISTINGS_COLLECTION).countDocuments(query)).isEqualTo(1);
  }

  /** 2dsphere 인덱스로 기준 좌표 반경 내 매물을 찾을 수 있는지 확인한다. */
  @Test
  void query_2dsphere로_반경내_매물을_조회한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);
    Document geometry =
        new Document("type", "Point").append("coordinates", List.of(126.951422, 37.459471));
    Document near = new Document("$geometry", geometry).append("$maxDistance", 1000);
    Document query = new Document("location", new Document("$near", near));

    Document found = mongoTemplate.getCollection(LISTINGS_COLLECTION).find(query).first();
    assertThat(found).isNotNull();
    assertThat(found.get("title", Document.class).getString("ko")).isEqualTo("신림 스테이");
  }

  /**
   * 대학이 밀집한 좌표에서 반경 안의 코드를 모두 찾는지 확인한다(ADR-0045).
   *
   * <p>신촌로 12는 연세·이화·홍익이 모두 2km 안이다 — 진단의 {@code HONGIK_YONSEI_EWHA} 그룹과 그대로 맞물리는 자리라, 최근접 하나만 고르면
   * 매칭이 줄어든다는 결정 근거를 여기서 못 박는다.
   */
  @Test
  void findCodesWithin_반경안의_대학코드를_모두_반환한다() {
    ListingTestSeeds.seedUniversities(mongoTemplate, UNIVERSITIES_COLLECTION);

    Set<String> codes =
        universityRepository.findCodesWithin(
            new Coordinate(37.5559918, 126.9368647), UNIVERSITY_RADIUS_METERS);

    assertThat(codes).containsExactlyInAnyOrder("YONSEI", "EWHA", "HONGIK");
  }

  /** 반경이 경계다 — 서울대 남쪽 약 1.9km는 잡히고 약 2.1km는 빠진다. */
  @Test
  void findCodesWithin_반경_경계를_지킨다() {
    ListingTestSeeds.seedUniversities(mongoTemplate, UNIVERSITIES_COLLECTION);
    double snuLng = 126.952239;
    double snuLat = 37.464007;

    Set<String> inside =
        universityRepository.findCodesWithin(
            new Coordinate(snuLat - 0.01708, snuLng), UNIVERSITY_RADIUS_METERS);
    Set<String> outside =
        universityRepository.findCodesWithin(
            new Coordinate(snuLat - 0.01889, snuLng), UNIVERSITY_RADIUS_METERS);

    assertThat(inside).containsExactly("SNU");
    assertThat(outside).isEmpty();
  }

  /** 원장이 비어 있어도(시드 전 신규 환경) 조회는 빈 집합이다 — 등록이 예외로 멈추지 않는다. */
  @Test
  void findCodesWithin_원장이_비어있으면_빈집합이다() {
    Set<String> codes =
        universityRepository.findCodesWithin(
            new Coordinate(37.5559918, 126.9368647), UNIVERSITY_RADIUS_METERS);

    assertThat(codes).isEmpty();
  }

  /** 목록 검색은 지도 범위와 필터를 모두 만족하는 방 상품을 가진 매물 카드를 반환한다. */
  @Test
  void search_지도범위와_필터로_매물을_조회한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);

    PageResponse<ListingSearchResult> result =
        listingRepository.search(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                null,
                500000,
                null,
                500000,
                Set.of(ListingType.GOSHIWON),
                Set.of(ConditionTag.FEMALE_ONLY),
                ListingSort.PRICE_ASC,
                null,
                null,
                0,
                20));

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().listing().getId())
        .isEqualTo(ListingTestSeeds.LISTING_ID);
    assertThat(result.content().getFirst().roomOffers())
        .extracting(Listing.RoomOffer::roomOfferId)
        .containsExactly(ListingTestSeeds.ROOM_OFFER_ID);
  }

  /** 필터가 없으면 공개 매물 안의 모든 active roomOffer가 매물 카드의 범위 계산 대상이 된다. */
  @Test
  void search_필터가_없으면_active_방상품을_모두_묶어_매물_카드로_반환한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000201",
                        "Green Zone 1",
                        490000,
                        1000000,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000202",
                        "Green Zone 2",
                        550000,
                        1000000,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000203",
                        "Green Zone 3",
                        450000,
                        500000,
                        Set.of(ConditionTag.ENGLISH_OK)),
                    roomOffer(
                        "6858e2000000000000000204",
                        "Inactive Zone",
                        Listing.RoomOfferStatus.INACTIVE,
                        300000,
                        300000,
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
                null,
                Set.of(ListingType.GOSHIWON),
                Set.of(),
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20));

    assertThat(result.page().totalElements()).isEqualTo(1);
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().roomOffers())
        .extracting(roomOffer -> roomOffer.name().en())
        .containsExactly("Green Zone 1", "Green Zone 2", "Green Zone 3");
  }

  /** 필터가 있으면 조건에 맞는 active roomOffer만 매물 카드의 범위 계산 대상에 남긴다. */
  @Test
  void search_필터에_맞는_방상품만_매물_카드에_포함한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000301",
                        "Green Zone 1",
                        490000,
                        1000000,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000302",
                        "Green Zone 2",
                        550000,
                        1000000,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000303",
                        "Green Zone 3",
                        450000,
                        500000,
                        Set.of(ConditionTag.ENGLISH_OK))))
            .build());

    PageResponse<ListingSearchResult> result =
        listingRepository.search(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                480000,
                600000,
                null,
                null,
                Set.of(ListingType.GOSHIWON),
                Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH),
                ListingSort.PRICE_ASC,
                null,
                null,
                0,
                20));

    assertThat(result.page().totalElements()).isEqualTo(1);
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().roomOffers())
        .extracting(roomOffer -> roomOffer.name().en())
        .containsExactly("Green Zone 1", "Green Zone 2");
    assertThat(result.content().getFirst().roomOffers())
        .extracting(roomOffer -> roomOffer.pricing().monthlyRent())
        .containsExactly(490000, 550000);
  }

  /** 목록 페이지 정보는 roomOffer 수가 아니라 최종 매물 카드 수를 기준으로 계산한다. */
  @Test
  void search_페이지네이션은_매물_카드_기준으로_계산한다() {
    String firstListingId = "6858e2000000000000000401";
    String secondListingId = "6858e2000000000000000402";
    String thirdListingId = "6858e2000000000000000403";
    listingRepository.save(
        sampleListingBuilder()
            .id(firstListingId)
            .favoriteCount(3)
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000411",
                        "Green Zone 1",
                        490000,
                        1000000,
                        Set.of(ConditionTag.FEMALE_ONLY))))
            .build());
    listingRepository.save(
        sampleListingBuilder()
            .id(secondListingId)
            .favoriteCount(2)
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000421",
                        "Green Zone 2",
                        550000,
                        1000000,
                        Set.of(ConditionTag.PRIVATE_BATH))))
            .build());
    listingRepository.save(
        sampleListingBuilder()
            .id(thirdListingId)
            .favoriteCount(1)
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000431",
                        "Green Zone 3",
                        600000,
                        1000000,
                        Set.of(ConditionTag.ENGLISH_OK))))
            .build());

    ListingSearchCondition firstPageCondition =
        new ListingSearchCondition(
            new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
            null,
            null,
            null,
            null,
            null,
            Set.of(ListingType.GOSHIWON),
            Set.of(),
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
            null,
            Set.of(ListingType.GOSHIWON),
            Set.of(),
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
        .extracting(searchResult -> searchResult.listing().getId())
        .containsExactly(firstListingId, secondListingId);
    assertThat(secondPage.page().hasNext()).isFalse();
    assertThat(secondPage.content())
        .extracting(searchResult -> searchResult.listing().getId())
        .containsExactly(thirdListingId);
  }

  /** v4에는 재고가 없으므로 즉시입주는 같은 roomOffer가 MOVE_IN_NOW 태그를 가졌는지로만 판정한다. */
  @Test
  void search_즉시입주조건은_MOVE_IN_NOW_태그를_가진_방상품만_반환한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000501",
                        "Waiting List Zone",
                        490000,
                        1000000,
                        Set.of(ConditionTag.FEMALE_ONLY)),
                    roomOffer(
                        "6858e2000000000000000502",
                        "Move In Now Zone",
                        550000,
                        1000000,
                        Set.of(ConditionTag.MOVE_IN_NOW, ConditionTag.FEMALE_ONLY))))
            .build());

    PageResponse<ListingSearchResult> result =
        listingRepository.search(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                null,
                null,
                null,
                null,
                Set.of(ListingType.GOSHIWON),
                Set.of(ConditionTag.MOVE_IN_NOW),
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20));

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().roomOffers())
        .extracting(roomOffer -> roomOffer.name().en())
        .containsExactly("Move In Now Zone");
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
                        Set.of(ConditionTag.ADDRESS_REGISTRATION)),
                    roomOffer(
                        "6858e2000000000000000802",
                        "No Registration Zone",
                        450000,
                        500000,
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
                null,
                Set.of(ListingType.GOSHIWON),
                Set.of(ConditionTag.ADDRESS_REGISTRATION),
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20));

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().roomOffers())
        .extracting(roomOffer -> roomOffer.name().en())
        .containsExactly("Registration Zone");
  }

  /**
   * ARC 필터는 v4에서 목록 조건 태그가 아니라 진단 ⑥ arcStatus로 옮겨졌다. NO_ARC 답이면 ARC 불요 매물만 남기고, 답이 없으면 ARC 조건을 적용하지
   * 않는다.
   */
  @Test
  void recommend_arcStatus가_NO_ARC면_ARC불요_매물만_반환한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);

    PageResponse<RecommendedListingView> withoutArcAnswer = recommendWithArcStatus(null);
    PageResponse<RecommendedListingView> noArcOnly = recommendWithArcStatus("NO_ARC");

    assertThat(withoutArcAnswer.content())
        .extracting(RecommendedListingView::listingId)
        .containsExactlyInAnyOrder(ListingTestSeeds.LISTING_ID, ListingTestSeeds.SECOND_LISTING_ID);
    assertThat(noArcOnly.content())
        .extracting(RecommendedListingView::listingId)
        .containsExactly(ListingTestSeeds.LISTING_ID);
  }

  /**
   * 진단 ③ 지역구의 {@code ETC}는 특정 구가 아니라 명시 5구의 여집합이다.
   *
   * <p>등가 비교로 두면 {@code address.district}에 저장된 어떤 값도 문자열 {@code "ETC"}와 같지 않아 항상 0건이 된다. 이 테스트는 그
   * 회귀를 막는다.
   */
  @Test
  void recommend_지역구가_ETC면_명시된_5구를_제외한_매물을_반환한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);

    PageResponse<RecommendedListingView> etc = recommendWithDistrict("ETC");
    PageResponse<RecommendedListingView> gwanakGu = recommendWithDistrict("GWANAK_GU");

    // 시드는 GWANAK_GU(명시 5구)와 MAPO_GU(그 외) 각각 1건이다.
    assertThat(etc.content())
        .extracting(RecommendedListingView::listingId)
        .containsExactly(ListingTestSeeds.SECOND_LISTING_ID);
    assertThat(gwanakGu.content())
        .extracting(RecommendedListingView::listingId)
        .containsExactly(ListingTestSeeds.LISTING_ID);
  }

  /** 서비스 응답은 조건에 맞는 방 상품들을 매물 단위로 집계해 가격·계약기간을 내려준다. */
  @Test
  void getListings_매물_카드_응답에_가격과_계약기간_범위를_포함한다() {
    listingRepository.save(
        sampleListingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000601",
                        "Green Zone 1",
                        490000,
                        1000000,
                        20000,
                        1,
                        6,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000602",
                        "Green Zone 2",
                        550000,
                        1500000,
                        30000,
                        3,
                        12,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.ENGLISH_OK)),
                    roomOffer(
                        "6858e2000000000000000603",
                        "Filtered Out Zone",
                        450000,
                        500000,
                        10000,
                        2,
                        6,
                        Set.of(ConditionTag.ADDRESS_REGISTRATION))))
            .build());
    ListingSearchRequest request = new ListingSearchRequest();
    request.setSwLat(37.45);
    request.setSwLng(126.90);
    request.setNeLat(37.50);
    request.setNeLng(127.00);
    request.setConditions(Set.of(ConditionTag.FEMALE_ONLY));
    request.setType(Set.of(ListingType.GOSHIWON));

    PageResponse<ListingSummaryResponse> result = listingService.getListings(request);

    assertThat(result.content()).hasSize(1);
    ListingSummaryResponse card = result.content().getFirst();
    assertThat(card.listingId()).isEqualTo(LISTING_ID);
    assertThat(card.status()).isEqualTo(Listing.ListingStatus.PUBLISHED);
    assertThat(card.rentalType().code()).isEqualTo("MONTHLY_RENT");
    assertThat(card.refundPolicy()).isEqualTo("입주 7일 전 취소 시 전액 환불");
    assertThat(card.location())
        .isEqualTo(new ListingDetailResponse.GeoPoint(37.459471, 126.951422));
    assertThat(card.address().city().code()).isEqualTo("SEOUL");
    assertThat(card.address().district().code()).isEqualTo("GWANAK_GU");
    assertThat(card.address().fullAddress()).isEqualTo("서울특별시 관악구 테스트로 1");
    assertThat(card.nearestTransit().type().code()).isEqualTo("SUBWAY");
    assertThat(card.nearestTransit().name()).isEqualTo("서울대입구역");
    assertThat(card.nearestTransit().walkMinutes()).isEqualTo(5);
    assertThat(card.building().type().code()).isEqualTo("VILLA");
    assertThat(card.facilities().heatingSystem())
        .extracting(heating -> heating.code())
        .containsExactly("CENTRAL");
    assertThat(card.facilities().commonSpaces())
        .extracting(commonSpace -> commonSpace.code())
        .containsExactly("SHARED_TOILET");
    assertThat(card.roomOffers())
        .extracting(ListingDetailResponse.RoomOfferResponse::name)
        .containsExactly("Green Zone 1", "Green Zone 2");
    assertThat(card.roomOffers())
        .extracting(roomOffer -> roomOffer.pricing().monthlyRent())
        .containsExactly(490000, 550000);
    assertThat(card.roomOffers())
        .extracting(roomOffer -> roomOffer.pricing().deposit())
        .containsExactly(1000000, 1500000);
    assertThat(card.roomOffers())
        .extracting(roomOffer -> roomOffer.pricing().maintenanceFee())
        .containsExactly(20000, 30000);
    assertThat(card.roomOffers())
        .extracting(roomOffer -> roomOffer.contract().minStayMonths())
        .containsExactly(1, 3);
    assertThat(card.roomOffers())
        .extracting(roomOffer -> roomOffer.contract().maxStayMonths())
        .containsExactly(6, 12);
    assertThat(card.roomOffers().getFirst().filterTags())
        .extracting(tag -> tag.code())
        .containsExactlyInAnyOrder("FEMALE_ONLY", "PRIVATE_BATH");
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
                List.of(roomOffer("6858e2000000000000000a01", "Far Zone", 490000, 0, Set.of())))
            .build());
    listingRepository.save(
        sampleListingBuilder()
            .id(nearListingId)
            .location(new Listing.GeoPoint(127.101, 37.101))
            .roomOffers(
                List.of(roomOffer("6858e2000000000000000a02", "Near Zone", 490000, 0, Set.of())))
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
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);

    ListingMapSearchResult result =
        listingRepository.searchForMap(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                null,
                500000,
                null,
                500000,
                Set.of(ListingType.GOSHIWON),
                Set.of(ConditionTag.FEMALE_ONLY),
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20),
            500);

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.listings()).hasSize(1);
    assertThat(result.listings().getFirst().getId()).isEqualTo(ListingTestSeeds.LISTING_ID);
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
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH)),
                    roomOffer(
                        "6858e2000000000000000702",
                        "Green Zone 2",
                        550000,
                        1000000,
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
                null,
                Set.of(ListingType.GOSHIWON),
                Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.PRIVATE_BATH),
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
    String rejectedListingId = "6858e2000000000000000004";
    listingRepository.save(sampleListing());
    listingRepository.save(
        sampleListingBuilder().id(newerListingId).title(localized("두 번째 고시원")).build());
    listingRepository.save(
        sampleListingBuilder()
            .id(rejectedListingId)
            .title(localized("반려된 고시원"))
            .status(Listing.ListingStatus.REJECTED)
            .build());
    favoriteRepository.saveIfAbsent(favorite(1L, LISTING_ID, "2026-06-24T01:00:00Z"));
    favoriteRepository.saveIfAbsent(favorite(1L, newerListingId, "2026-06-24T03:00:00Z"));
    favoriteRepository.saveIfAbsent(favorite(1L, rejectedListingId, "2026-06-24T04:00:00Z"));

    PageResponse<FavoriteListingResponse> result = listingService.getMyFavorites(1L, 0, 20);

    assertThat(result.page().totalElements()).isEqualTo(2);
    assertThat(result.content())
        .extracting(FavoriteListingResponse::listingId)
        .containsExactly(newerListingId, LISTING_ID);
    assertThat(result.content()).allMatch(FavoriteListingResponse::favorited);
    assertThatThrownBy(() -> listingService.addFavorite(1L, rejectedListingId))
        .isInstanceOf(ListingNotFoundException.class);
  }

  /** 찜 목록은 사용자별로 분리되고 page/size에 맞게 잘린다. */
  @Test
  void favorite_목록은_사용자별로_분리하고_페이지네이션한다() {
    String secondListingId = "6858e2000000000000000005";
    String thirdListingId = "6858e2000000000000000006";
    listingRepository.save(sampleListing());
    listingRepository.save(
        sampleListingBuilder().id(secondListingId).title(localized("두 번째 고시원")).build());
    listingRepository.save(
        sampleListingBuilder().id(thirdListingId).title(localized("세 번째 고시원")).build());
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

  /** 상세 조회가 성공하면 최근 본 매물 기록을 남기고, 상세 응답에는 현재 사용자의 실제 찜 여부를 반영한다. */
  @Test
  void recent_상세조회는_최근본을_저장하고_상세_찜여부를_반영한다() {
    listingRepository.save(sampleListingBuilder().favoriteCount(1).build());
    favoriteRepository.saveIfAbsent(favorite(1L, LISTING_ID, "2026-06-24T01:00:00Z"));

    ListingDetailResponse response = listingService.getListing(1L, LISTING_ID);

    assertThat(response.listingId()).isEqualTo(LISTING_ID);
    assertThat(response.favorited()).isTrue();
    assertThat(response.favoriteCount()).isEqualTo(1);
    assertThat(
            mongoTemplate
                .getCollection(RECENT_LISTINGS_COLLECTION)
                .countDocuments(new Document("userId", 1L)))
        .isEqualTo(1);
  }

  /** 상세 응답은 MongoDB v4 구조에 맞게 루트 공통 필드를 내려주고, 방 상품은 ACTIVE 항목만 내려준다. */
  @Test
  void detail_루트필드와_방목록은_v4_구조를_따르고_ACTIVE_roomOffer만_반환한다() {
    listingRepository.save(
        sampleListingBuilder()
            .imageUrls(
                List.of(
                    "https://cdn.kohere.app/listings/detail/1.jpg",
                    "https://cdn.kohere.app/listings/detail/2.jpg",
                    "https://cdn.kohere.app/listings/detail/3.jpg"))
            .roomOffers(
                List.of(
                    roomOffer(
                        "6858e2000000000000000901",
                        "Green Zone 1",
                        380000,
                        500000,
                        0,
                        1,
                        6,
                        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.ADDRESS_REGISTRATION)),
                    roomOffer(
                        "6858e2000000000000000902",
                        "Green Zone 2",
                        400000,
                        700000,
                        20000,
                        2,
                        12,
                        Set.of(ConditionTag.PRIVATE_BATH, ConditionTag.NO_MAINT_FEE)),
                    roomOffer(
                        "6858e2000000000000000903",
                        "Inactive Zone",
                        Listing.RoomOfferStatus.INACTIVE,
                        100000,
                        100000,
                        Set.of(ConditionTag.MOVE_IN_NOW))))
            .build());

    ListingDetailResponse response = listingService.getListing(1L, LISTING_ID);

    assertThat(response.rentalType().code()).isEqualTo("MONTHLY_RENT");
    assertThat(response.refundPolicy()).isEqualTo("입주 7일 전 취소 시 전액 환불");
    assertThat(response.genderPolicy().code()).isEqualTo("FEMALE_ONLY");
    assertThat(response.imageUrls()).hasSize(3);
    assertThat(response.roomOffers())
        .extracting(ListingDetailResponse.RoomOfferResponse::name)
        .containsExactly("Green Zone 1", "Green Zone 2");
    assertThat(response.roomOffers())
        .extracting(roomOffer -> roomOffer.contract().minStayMonths())
        .containsExactly(1, 2);
    assertThat(response.roomOffers())
        .allSatisfy(
            roomOffer -> {
              assertThat(roomOffer.status()).isEqualTo(Listing.RoomOfferStatus.ACTIVE);
              assertThat(roomOffer.pricing().monthlyRent()).isBetween(380000, 400000);
            });
  }

  /** 같은 매물을 다시 보면 최근 본 문서를 중복 생성하지 않고 viewedAt만 최신값으로 갱신한다. */
  @Test
  void recent_같은_매물_재조회는_viewedAt만_갱신한다() {
    listingRepository.save(sampleListing());
    Instant firstViewedAt = Instant.parse("2026-06-24T01:00:00Z");
    Instant secondViewedAt = Instant.parse("2026-06-24T03:00:00Z");

    recentListingRepository.upsertViewedAt(1L, LISTING_ID, firstViewedAt);
    recentListingRepository.upsertViewedAt(1L, LISTING_ID, secondViewedAt);

    RecentListingsResponse result = listingService.getRecentListings(1L);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().listingId()).isEqualTo(LISTING_ID);
    assertThat(result.content().getFirst().viewedAt()).isEqualTo(secondViewedAt);
    assertThat(
            mongoTemplate
                .getCollection(RECENT_LISTINGS_COLLECTION)
                .countDocuments(new Document("userId", 1L)))
        .isEqualTo(1);
  }

  /** 최근 본 목록은 공개 매물만 최신순 최대 10개 반환하고, 현재 사용자의 실제 찜 여부를 함께 내려준다. */
  @Test
  void recent_목록은_공개매물만_최신순_최대10개와_실제찜여부를_반환한다() {
    Instant baseViewedAt = Instant.parse("2026-06-24T00:00:00Z");
    for (int index = 1; index <= 13; index++) {
      Listing.ListingStatus status =
          index >= 12 ? Listing.ListingStatus.REJECTED : Listing.ListingStatus.PUBLISHED;
      saveListingWithRecentView(1L, index, status, baseViewedAt.plusSeconds(index));
    }
    recentListingRepository.upsertViewedAt(2L, listingId(11), baseViewedAt.plusSeconds(999));
    favoriteRepository.saveIfAbsent(favorite(1L, listingId(10), "2026-06-24T05:00:00Z"));
    favoriteRepository.saveIfAbsent(favorite(2L, listingId(11), "2026-06-24T06:00:00Z"));

    RecentListingsResponse result = listingService.getRecentListings(1L);

    assertThat(result.content()).hasSize(10);
    assertThat(result.content())
        .extracting(RecentListingResponse::listingId)
        .containsExactly(
            listingId(11),
            listingId(10),
            listingId(9),
            listingId(8),
            listingId(7),
            listingId(6),
            listingId(5),
            listingId(4),
            listingId(3),
            listingId(2));
    assertThat(result.content())
        .filteredOn(response -> response.listingId().equals(listingId(10)))
        .singleElement()
        .satisfies(
            response -> {
              assertThat(response.favorited()).isTrue();
              assertThat(response.nearestTransit())
                  .isEqualTo(
                      new ListingDetailResponse.NearestTransitResponse(
                          response.nearestTransit().type(), "서울대입구역", 5));
              assertThat(response.roomOffers().getFirst().pricing().monthlyRent())
                  .isEqualTo(310000);
            });
    assertThat(result.content())
        .filteredOn(response -> response.listingId().equals(listingId(11)))
        .singleElement()
        .satisfies(response -> assertThat(response.favorited()).isFalse());
  }

  /** 상세 조회로 최근 본 기록을 남긴 뒤 사용자별 30개를 넘으면 오래된 기록부터 삭제한다. */
  @Test
  void recent_상세조회후_사용자별_최대30개만_보관한다() {
    for (int index = 1; index <= 31; index++) {
      listingRepository.save(listingWithIndex(index));
      listingService.getListing(1L, listingId(index));
    }
    recentListingRepository.upsertViewedAt(2L, listingId(1), Instant.parse("2026-06-24T10:00:00Z"));

    assertThat(
            mongoTemplate
                .getCollection(RECENT_LISTINGS_COLLECTION)
                .countDocuments(new Document("userId", 1L)))
        .isEqualTo(30);
    assertThat(
            mongoTemplate
                .getCollection(RECENT_LISTINGS_COLLECTION)
                .countDocuments(new Document("userId", 2L)))
        .isEqualTo(1);
    assertThat(listingService.getRecentListings(1L).content()).hasSize(10);
  }

  /** 없거나 공개 상태가 아닌 매물 상세 조회는 LISTING_NOT_FOUND가 되며 최근 본 기록도 남기지 않는다. */
  @Test
  void recent_상세조회_대상이_없거나_비공개면_기록하지_않는다() {
    String rejectedListingId = listingId(40);
    listingRepository.save(listingWithIndex(40, Listing.ListingStatus.REJECTED));

    assertThatThrownBy(() -> listingService.getListing(1L, rejectedListingId))
        .isInstanceOf(ListingNotFoundException.class);
    assertThatThrownBy(() -> listingService.getListing(1L, listingId(41)))
        .isInstanceOf(ListingNotFoundException.class);
    assertThatThrownBy(() -> listingService.getListing(1L, "not-object-id"))
        .isInstanceOf(ListingNotFoundException.class);
    assertThat(
            mongoTemplate
                .getCollection(RECENT_LISTINGS_COLLECTION)
                .countDocuments(new Document("userId", 1L)))
        .isZero();
  }

  /** 최근 본 기록이 없거나 모두 비공개 매물이면 content 빈 배열을 정상 응답으로 반환한다. */
  @Test
  void recent_목록이_비어도_정상_응답을_반환한다() {
    listingRepository.save(listingWithIndex(50, Listing.ListingStatus.REJECTED));
    recentListingRepository.upsertViewedAt(
        1L, listingId(50), Instant.parse("2026-06-24T01:00:00Z"));

    RecentListingsResponse result = listingService.getRecentListings(1L);

    assertThat(result.content()).isEmpty();
  }

  /** 조건을 모두 만족하는 같은 방 상품이 없으면 목록 검색 결과는 비어 있어야 한다. */
  @Test
  void search_조건을_모두_만족하지_않으면_빈_목록을_반환한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);

    PageResponse<ListingSearchResult> result =
        listingRepository.search(
            new ListingSearchCondition(
                new ListingSearchCondition.BoundingBox(37.45, 126.90, 37.50, 127.00),
                null,
                null,
                200000,
                null,
                null,
                Set.of(ListingType.GOSHIWON),
                Set.of(ConditionTag.FEMALE_ONLY),
                ListingSort.RECOMMENDED,
                null,
                null,
                0,
                20));

    assertThat(result.content()).isEmpty();
  }

  /** diagnosis가 전달한 UI 필터 조건 코드로 매물 요약을 반환한다. */
  @Test
  void recommendByCriteria_조건코드로_매물요약을_반환한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);

    PageResponse<RecommendedListingView> result =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                0,
                500000,
                Set.of("FEMALE_ONLY", "ADDRESS_REGISTRATION"),
                Set.of("SNU"),
                Set.of(),
                null,
                null,
                0,
                20,
                "recommended,desc"));

    assertThat(result.content()).hasSize(1);
    RecommendedListingView listing = result.content().getFirst();
    assertThat(listing.listingId()).isEqualTo(ListingTestSeeds.LISTING_ID);
    assertThat(listing.title()).isEqualTo("Sillim Stay");
    assertThat(listing.type().code()).isEqualTo("GOSHIWON");
    assertThat(listing.type().label()).isEqualTo("Goshiwon");
    assertThat(listing.monthlyRentMin()).isEqualTo(380000);
    assertThat(listing.monthlyRentMax()).isEqualTo(520000);
    assertThat(listing.minDeposit()).isEqualTo(300000);
    assertThat(listing.maxDeposit()).isEqualTo(500000);
    assertThat(listing.lat()).isEqualTo(37.459471);
    assertThat(listing.lng()).isEqualTo(126.951422);
    assertThat(listing.conditions())
        .extracting(condition -> condition.code())
        .contains("FEMALE_ONLY", "ADDRESS_REGISTRATION");
    assertThat(listing.conditions())
        .filteredOn(condition -> condition.code().equals("FEMALE_ONLY"))
        .singleElement()
        .extracting(condition -> condition.label())
        .isEqualTo("Female Only");

    PageResponse<RecommendedListingView> koreanResult =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                0,
                500000,
                Set.of("FEMALE_ONLY", "ADDRESS_REGISTRATION"),
                Set.of("SNU"),
                Set.of(),
                null,
                null,
                0,
                20,
                "recommended,desc"),
            "ko");
    RecommendedListingView koreanListing = koreanResult.content().getFirst();
    assertThat(koreanListing.title()).isEqualTo("신림 스테이");
    assertThat(koreanListing.type().code()).isEqualTo("GOSHIWON");
    assertThat(koreanListing.type().label()).isEqualTo("고시원");
  }

  /** 진단 대학 그룹에서 펼친 개별 대학 코드 집합은 nearbyUniversityCodes와 ANY 매칭되고, 빈 집합은 대학 필터를 생략한다. */
  @Test
  void recommendByCriteria_대학코드집합은_ANY로_매칭하고_빈집합은_대학필터를_생략한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);

    PageResponse<RecommendedListingView> matchedByGroupMember =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                0,
                500000,
                Set.of("FEMALE_ONLY"),
                Set.of("CAU"),
                Set.of(),
                null,
                null,
                0,
                20,
                null));
    PageResponse<RecommendedListingView> notMatchedByDifferentUniversity =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                0,
                500000,
                Set.of("FEMALE_ONLY"),
                Set.of("KOREA"),
                Set.of(),
                null,
                null,
                0,
                20,
                null));
    PageResponse<RecommendedListingView> noUniversityFilter =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                0,
                500000,
                Set.of("FEMALE_ONLY"),
                Set.of(),
                Set.of(),
                null,
                null,
                0,
                20,
                null));

    assertThat(matchedByGroupMember.content())
        .extracting(RecommendedListingView::listingId)
        .containsExactly(ListingTestSeeds.LISTING_ID);
    assertThat(notMatchedByDifferentUniversity.content()).isEmpty();
    assertThat(noUniversityFilter.content())
        .extracting(RecommendedListingView::listingId)
        .containsExactly(ListingTestSeeds.LISTING_ID);
  }

  /** 추천 월세 하한과 상한은 같은 active roomOffer의 pricing.monthlyRent에 함께 적용된다. */
  @Test
  void recommendByCriteria_월세하한과_상한을_모두_적용한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);

    PageResponse<RecommendedListingView> inRange =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                350000,
                400000,
                Set.of("FEMALE_ONLY"),
                Set.of("SNU"),
                Set.of(),
                null,
                null,
                0,
                20,
                null));
    PageResponse<RecommendedListingView> belowMinimum =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                530000,
                600000,
                Set.of("FEMALE_ONLY"),
                Set.of("SNU"),
                Set.of(),
                null,
                null,
                0,
                20,
                null));
    PageResponse<RecommendedListingView> aboveMaximum =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                0,
                379999,
                Set.of("FEMALE_ONLY"),
                Set.of("SNU"),
                Set.of(),
                null,
                null,
                0,
                20,
                null));

    assertThat(inRange.content())
        .extracting(RecommendedListingView::listingId)
        .containsExactly(ListingTestSeeds.LISTING_ID);
    assertThat(belowMinimum.content()).isEmpty();
    assertThat(aboveMaximum.content()).isEmpty();
  }

  /** v4에는 재고가 없으므로 즉시입주 조건은 방 상품의 MOVE_IN_NOW 태그로만 매칭된다. */
  @Test
  void recommendByCriteria_즉시입주조건은_MOVE_IN_NOW_태그로_매칭한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);

    PageResponse<RecommendedListingView> moveInNow =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                0,
                500000,
                Set.of("MOVE_IN_NOW"),
                Set.of("SNU"),
                Set.of(),
                null,
                null,
                0,
                20,
                null));
    PageResponse<RecommendedListingView> notTagged =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                0,
                500000,
                Set.of("MEALS_INCLUDED"),
                Set.of("SNU"),
                Set.of(),
                null,
                null,
                0,
                20,
                null));

    assertThat(moveInNow.content())
        .extracting(RecommendedListingView::listingId)
        .containsExactly(ListingTestSeeds.LISTING_ID);
    assertThat(notTagged.content()).isEmpty();
  }

  /**
   * 진단 ③에서 "그 외 대학"({@code ETC})을 고르면 목록에 든 대학 근처 매물이 빠진다(ADR-0045).
   *
   * <p>{@code ETC}는 "대학 조건 없음"이 아니라 <b>목록 14곳의 여집합</b>이다 — 진단 지역의 {@code ETC}가 명시 5구의 여집합인 것과 같다.
   * 시드 매물 둘은 각각 서울대·홍익대 인근이라 둘 다 빠지고, 대학 코드가 없는 매물만 남는다.
   */
  @Test
  void recommend_ETC는_목록대학_인근매물을_제외한다() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);
    listingRepository.save(
        sampleListing().toBuilder()
            .id(null)
            .status(Listing.ListingStatus.PUBLISHED)
            .nearbyUniversityCodes(Set.of())
            .build());
    Set<String> allUniversityCodes = Set.of("SNU", "CAU", "SOONGSIL", "HONGIK", "YONSEI", "EWHA");

    PageResponse<RecommendedListingView> etc =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL",
                null,
                null,
                Set.of(),
                Set.of(),
                allUniversityCodes,
                null,
                null,
                0,
                20,
                null));
    PageResponse<RecommendedListingView> noUniversityCondition =
        listingRecommendationService.recommendByCriteria(
            new RecommendationCriteria(
                "SEOUL", null, null, Set.of(), Set.of(), Set.of(), null, null, 0, 20, null));

    // 대학 코드를 가진 시드 매물 둘은 빠지고, 빈 배열 매물만 남는다.
    assertThat(etc.content()).hasSize(1);
    assertThat(noUniversityCondition.content()).hasSizeGreaterThan(etc.content().size());
  }

  /** 진단 ⑥ arcStatus만 바꿔 추천 결과를 비교하기 위한 요청 헬퍼다. */
  private PageResponse<RecommendedListingView> recommendWithArcStatus(String arcStatus) {
    return listingRecommendationService.recommendByCriteria(
        new RecommendationCriteria(
            "SEOUL", null, null, Set.of(), Set.of(), Set.of(), null, arcStatus, 0, 20, null));
  }

  /** 진단 ③ 지역구만 바꿔 추천 결과를 비교하기 위한 요청 헬퍼다. */
  private PageResponse<RecommendedListingView> recommendWithDistrict(String district) {
    return listingRecommendationService.recommendByCriteria(
        new RecommendationCriteria(
            "SEOUL", null, null, Set.of(), Set.of(), Set.of(), district, null, 0, 20, null));
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

  /** 최근 본 매물 목록 테스트에 사용할 매물을 저장하고 같은 사용자에게 최근 본 기록을 남긴다. */
  private void saveListingWithRecentView(
      Long userId, int index, Listing.ListingStatus status, Instant viewedAt) {
    listingRepository.save(listingWithIndex(index, status));
    recentListingRepository.upsertViewedAt(userId, listingId(index), viewedAt);
  }

  /** 반복 테스트에서 충돌 없는 고정 listingId를 만든다. */
  /** 임대인 조회 테스트용 매물이다. 소유자·상태·수정 시각만 바꿔 쓴다. */
  private static Listing landlordListing(
      int index, long landlordId, Listing.ListingStatus status, Instant updatedAt) {
    return listingWithIndex(index, status).toBuilder()
        .landlordId(landlordId)
        .updatedAt(updatedAt)
        .build();
  }

  private static String listingId(int index) {
    return String.format("6858e200000000000000%04x", index);
  }

  /** 반복 테스트에서 충돌 없는 고정 roomOfferId를 만든다. */
  private static String roomOfferId(int index) {
    return String.format("6858e200000000000001%04x", index);
  }

  /** 반복 테스트에서 사용할 공개 매물 기본값이다. */
  private static Listing listingWithIndex(int index) {
    return listingWithIndex(index, Listing.ListingStatus.PUBLISHED);
  }

  /** 반복 테스트에서 사용할 매물 기본값이다. */
  private static Listing listingWithIndex(int index, Listing.ListingStatus status) {
    return sampleListingBuilder()
        .id(listingId(index))
        .title(localized("최근 본 테스트 매물 " + index))
        .status(status)
        .roomOffers(
            List.of(
                roomOffer(
                    roomOfferId(index),
                    "최근 본 방 " + index,
                    300000 + index * 1000,
                    300000,
                    10000,
                    1,
                    6,
                    Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.ADDRESS_REGISTRATION))))
        .favoriteCount(index)
        .build();
  }

  /** 저장·조회 테스트에서 일부 필드만 바꿔 쓸 v4 대표 매물 빌더를 만든다. */
  private static Listing.ListingBuilder sampleListingBuilder() {
    return Listing.builder()
        .id(LISTING_ID)
        .schemaVersion(4)
        .landlordId(1L)
        .contact(new Listing.Contact("김담당", "+82) 10-1111-2222"))
        .businessRegistrationNumber("1112233344")
        .blogUrl(null)
        .ageMin(20)
        .ageMax(35)
        .title(localized("테스트 고시원"))
        .type(ListingType.GOSHIWON)
        .rentalType(Listing.RentalType.MONTHLY_RENT)
        .status(Listing.ListingStatus.PUBLISHED)
        .rejectionReason(null)
        .genderPolicy(Listing.GenderPolicy.FEMALE_ONLY)
        .languagesSupported(Set.of(SupportedLanguage.ENGLISH))
        .favoriteCount(0)
        .imageUrls(List.of())
        .nearbyUniversityCodes(Set.of("SNU"))
        .createdAt(Instant.parse("2026-06-24T00:00:00Z"))
        .updatedAt(Instant.parse("2026-06-24T00:00:00Z"))
        .address(new Listing.Address("SEOUL", "GWANAK_GU", localized("서울특별시 관악구 테스트로 1"), null))
        .building(new Listing.Building(Listing.BuildingType.VILLA, 1, 2, 4, true, true))
        .description(localized("테스트 설명"))
        .extraNotes(localized("테스트 주의사항"))
        .facilities(
            new Listing.Facilities(
                Set.of(Listing.HeatingSystem.CENTRAL),
                Set.of(KitchenFacility.SHARED_REFRIGERATOR),
                Set.of(LaundryFacility.WASHER),
                Set.of(LivingAmenity.WIFI),
                Set.of(SecurityFeature.CCTV),
                Set.of(Listing.CommonSpaceType.SHARED_TOILET),
                Set.of(ProvidedSupply.BEDDING)))
        .location(new Listing.GeoPoint(126.951422, 37.459471))
        .nearestTransit(
            new Listing.NearestTransit(Listing.TransitType.SUBWAY, localized("서울대입구역"), 5))
        .nearbyFacilities(Set.of(NearbyFacility.CONVENIENCE_STORE))
        .arcRequired(ArcRequirement.NOT_REQUIRED)
        .refundPolicy(localized("입주 7일 전 취소 시 전액 환불"))
        .roomOffers(List.of(sampleRoomOffer(ROOM_OFFER_ID)))
        .preferredNationalities(Set.of(Nationality.JAPAN))
        .contractDifficulties(Set.of(ContractDifficulty.LANGUAGE))
        .serviceFeedback(null);
  }

  /** 저장·조회 테스트에서 사용할 대표 방 상품을 만든다. */
  private static Listing.RoomOffer sampleRoomOffer(String roomOfferId) {
    return roomOffer(
        roomOfferId,
        "스탠다드 1인실",
        300000,
        300000,
        Set.of(ConditionTag.FEMALE_ONLY, ConditionTag.ADDRESS_REGISTRATION));
  }

  /**
   * 목록 조회 테스트에서 사용할 방 상품 묶음을 만든다.
   *
   * <p>roomOffer는 실제 방 1개가 아니라 같은 가격·조건을 가진 방 묶음이다. 테스트에서는 이름·가격·태그만 바꿔 여러 카드가 어떻게 펼쳐지는지 확인한다.
   */
  private static Listing.RoomOffer roomOffer(
      String roomOfferId, String name, int monthlyRent, int deposit, Set<ConditionTag> filterTags) {
    return roomOffer(roomOfferId, name, monthlyRent, deposit, 0, 2, 6, filterTags);
  }

  /** 관리비와 계약기간까지 지정하는 방 상품 묶음이다. v4에서는 계약기간이 방 상품마다 다를 수 있다. */
  private static Listing.RoomOffer roomOffer(
      String roomOfferId,
      String name,
      int monthlyRent,
      int deposit,
      int maintenanceFee,
      int minStayMonths,
      int maxStayMonths,
      Set<ConditionTag> filterTags) {
    return roomOffer(
        roomOfferId,
        name,
        Listing.RoomOfferStatus.ACTIVE,
        monthlyRent,
        deposit,
        maintenanceFee,
        minStayMonths,
        maxStayMonths,
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
      Set<ConditionTag> filterTags) {
    return roomOffer(roomOfferId, name, status, monthlyRent, deposit, 0, 2, 6, filterTags);
  }

  /** 상태·가격·계약기간까지 모두 지정할 수 있는 가장 상세한 방 상품 테스트 헬퍼다. */
  private static Listing.RoomOffer roomOffer(
      String roomOfferId,
      String name,
      Listing.RoomOfferStatus status,
      int monthlyRent,
      int deposit,
      int maintenanceFee,
      int minStayMonths,
      int maxStayMonths,
      Set<ConditionTag> filterTags) {
    return new Listing.RoomOffer(
        roomOfferId,
        localized(name),
        status,
        new Listing.Contract(minStayMonths, maxStayMonths),
        new Listing.Pricing(monthlyRent, deposit, maintenanceFee, Listing.Currency.KRW),
        filterTags,
        List.of());
  }

  /** 저장소 동작 자체에 집중하는 테스트에서 두 언어에 같은 문자열을 넣는 다국어 값 헬퍼다. */
  private static LocalizedText localized(String value) {
    return LocalizedText.same(value);
  }
}
