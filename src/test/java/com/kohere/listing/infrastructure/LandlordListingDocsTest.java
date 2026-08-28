package com.kohere.listing.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ApiDocsErrors.errorSnippet;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static com.kohere.docs.ListingDocsFields.LANDLORD_LISTING_401;
import static com.kohere.docs.ListingDocsFields.LANDLORD_LISTING_403;
import static com.kohere.docs.ListingDocsFields.LANDLORD_LISTING_404;
import static com.kohere.docs.ListingDocsFields.LANDLORD_LISTING_DETAIL_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.LANDLORD_LISTING_DETAIL_SUMMARY;
import static com.kohere.docs.ListingDocsFields.LANDLORD_LISTING_LIST_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.LANDLORD_LISTING_LIST_SUMMARY;
import static com.kohere.docs.ListingDocsFields.landlordListingPageResponseFields;
import static com.kohere.docs.ListingDocsFields.landlordListingResponseFields;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.docs.ApiDocsTags;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.infrastructure.external.s3.ListingImageProperties;
import com.kohere.user.api.UserAccountService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.request.ParameterDescriptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 임대인 「내 매물」 조회 2종({@code GET /api/v2/users/me/listings} · {@code GET
 * /api/v2/users/me/listings/{listingId}})의 REST Docs 스니펫 생성 테스트다(US-3-8).
 *
 * <p>태그는 {@link ApiDocsTags#LISTINGS}를 그대로 쓴다 — 경로가 {@code /users/me}로 시작해도 귀속 기준은 경로가 아니라 소유 모듈이라
 * 매물 그룹이다({@link ApiDocsTags} 클래스 주석).
 *
 * <p>인가가 세 겹이라 에러 스니펫에 비중을 둔다 — 임대인 아님(403)·온보딩 토큰(403)·남의 매물(404)·토큰 부재/위조/만료(401). 특히 <b>남의 매물은
 * 403이 아니라 404</b>다. 있는 매물과 없는 매물을 다른 코드로 알려 주면 그 차이가 곧 존재 여부를 알려 주는 통로가 된다.
 *
 * <p>cross-module 협력({@code user :: api}의 {@code userType}·표시 언어)은 {@link MockitoBean}으로 대체하고
 * access 토큰은 {@link JwtTokenService}로 직접 발급한다(test-strategy §4).
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class LandlordListingDocsTest {

  /** 시드 첫 번째 매물의 소유자다. 문서 예시는 전부 이 임대인 기준이다. */
  private static final long LANDLORD_ID = 11L;

  /** 정식 회원이지만 임대인이 아니라 서비스가 403으로 거르는 계정이다. 보안 매처는 통과한다. */
  private static final long TENANT_ID = 42L;

  private static final String LISTINGS_COLLECTION = "listings";
  private static final String LISTING_CATALOG_COLLECTION = "listingCatalog";

  /** 상세 예시로 쓸 시드 매물이다. 공개 중이며 방 하나를 내려 둔다. */
  private static final String PUBLISHED_LISTING_ID = ListingTestSeeds.LISTING_ID;

  /** 시드 두 번째 매물은 다른 임대인(12) 소유다 — 소유권 게이트의 404 예시로 쓴다. */
  private static final String OTHER_LANDLORD_LISTING_ID = ListingTestSeeds.SECOND_LISTING_ID;

  private static final String PENDING_LISTING_ID = "68e0000000000000000000a2";
  private static final String REJECTED_LISTING_ID = "68e0000000000000000000a3";
  private static final String UPDATE_PENDING_LISTING_ID = "68e0000000000000000000a4";

  private static final String MISSING_LISTING_ID = "68e0000000000000000000ff";

  /** ObjectId 형식이 아닌 값. 저장소가 조회 전에 걸러 없는 매물과 같은 404가 된다. */
  private static final String MALFORMED_LISTING_ID = "not-an-object-id";

  private static final String REJECTION_REASON = "사업자등록번호와 매물 주소가 일치하지 않습니다.";

  // 다른 키로 서명한 위조 access 토큰. 401 예시도 구조상 JWT여야 restdocs-api-spec이 bearerAuth 보안 스킴을
  // 도출해 Swagger 자물쇠가 유지된다(ListingDocsTest와 동일 처리).
  private static final String FORGED_TOKEN =
      Jwts.builder()
          .issuer("kohere")
          .subject("1")
          .claim("onboardingCompleted", true)
          .signWith(
              Keys.hmacShaKeyFor(
                  "forged-doc-only-wrong-secret-please-override-32bytes-min!!"
                      .getBytes(StandardCharsets.UTF_8)))
          .compact();

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired private WebApplicationContext context;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private ListingRepository listingRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private JwtProperties jwtProperties;
  @Autowired private ListingImageProperties imageProperties;

  @MockitoBean private UserAccountService userAccountService;

  private MockMvc mockMvc;

  /** REST Docs용 MockMvc를 만들고, 카탈로그·매물을 시드한 뒤 목록·상세 예시가 설 만큼 문서를 다듬는다. */
  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    mongoTemplate.getCollection(LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(LISTING_CATALOG_COLLECTION).deleteMany(new Document());
    ListingTestSeeds.seedCatalog(mongoTemplate, LISTING_CATALOG_COLLECTION);
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);
    copyOf(PENDING_LISTING_ID, Listing.ListingStatus.PENDING, null, "2026-08-02T00:00:00Z");
    copyOf(
        REJECTED_LISTING_ID,
        Listing.ListingStatus.REJECTED,
        REJECTION_REASON,
        "2026-08-03T00:00:00Z");
    copyOf(
        UPDATE_PENDING_LISTING_ID,
        Listing.ListingStatus.UPDATE_PENDING,
        null,
        "2026-08-04T00:00:00Z");
    prepareDetailListing();

    given(userAccountService.getUserType(LANDLORD_ID)).willReturn("LANDLORD");
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    // 임대인은 한국어로 서비스를 쓰므로 라벨도 한국어로 내려간다(#141). 상태는 관리 상태라 번역 없이 코드 그대로다.
    given(userAccountService.getLanguage(LANDLORD_ID)).willReturn("ko");
  }

  @Test
  void 문서스니펫생성_내매물목록_상태를가리지않는다() throws Exception {
    // status를 아예 보내지 않는 것이 이 API의 기본 호출이라 예시도 그렇게 만든다 — 기술자는 optional이다.
    mockMvc
        .perform(
            get("/api/v2/users/me/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(landlordToken()))
                .queryParam("page", "0")
                .queryParam("size", "20"))
        .andExpect(status().isOk())
        // 다른 임대인(12)의 시드 매물은 빠져 4건이다. 소유권은 상세뿐 아니라 목록에서도 조건이다.
        .andExpect(jsonPath("$.data.page.totalElements").value(4))
        // 최근 수정순 고정 — 방금 고친 매물이 맨 위다. 정렬 파라미터는 없다.
        .andExpect(jsonPath("$.data.content[0].listing.status").value("UPDATE_PENDING"))
        .andExpect(jsonPath("$.data.content[3].listing.status").value("PUBLISHED"))
        // 반려 사유는 반려된 카드에만 붙고, 그 외에는 값이 null이 아니라 키 자체가 빠진다.
        .andExpect(jsonPath("$.data.content[1].rejectionReason").value(REJECTION_REASON))
        .andExpect(jsonPath("$.data.content[0].rejectionReason").doesNotExist())
        .andDo(
            document(
                "landlord-listing-list",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(LANDLORD_LISTING_LIST_SUMMARY)
                    .description(LANDLORD_LISTING_LIST_DESCRIPTION),
                queryParameters(listQueryParameters()),
                responseFields(landlordListingPageResponseFields())));
  }

  @Test
  void 문서스니펫생성_내매물목록_상태필터() throws Exception {
    // 위 예시가 보여주지 못하는 것이 하나 있다 — 콤마 계약을 쓰는 요청 자체다. 응답 모양은 같으므로
    // 오퍼레이션이 늘지 않고 같은 200에 예시 하나가 더 붙는다(BookingDocsTest의 세입자/임대인 분기와 같은 모양).
    mockMvc
        .perform(
            get("/api/v2/users/me/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(landlordToken()))
                .queryParam("status", "REJECTED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].listing.listingId").value(REJECTED_LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].listing.status").value("REJECTED"))
        .andExpect(jsonPath("$.data.content[0].rejectionReason").value(REJECTION_REASON))
        .andDo(
            document(
                "landlord-listing-list-rejected",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(LANDLORD_LISTING_LIST_SUMMARY)
                    .description(LANDLORD_LISTING_LIST_DESCRIPTION),
                queryParameters(listQueryParameters()),
                responseFields(landlordListingPageResponseFields())));
  }

  @Test
  void 문서스니펫생성_내매물상세_비활성방까지준다() throws Exception {
    mockMvc
        .perform(
            get("/api/v2/users/me/listings/{listingId}", PUBLISHED_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(landlordToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.data.rejectionReason").doesNotExist())
        // 세입자에게 감추는 값이 수정 폼 프리필로는 그대로 실린다.
        .andExpect(jsonPath("$.data.businessRegistrationNumber").isString())
        // 중첩된 세입자 상세는 활성 방만, 바깥 rooms[]는 내린 방까지 준다 — 되살리려면 응답에 보여야 한다.
        .andExpect(jsonPath("$.data.listing.roomOffers.length()").value(1))
        .andExpect(jsonPath("$.data.rooms.length()").value(2))
        .andExpect(jsonPath("$.data.rooms[1].status").value("INACTIVE"))
        // 사진은 URL만이 아니라 수정 요청에 되돌려 보낼 키로도 내려간다.
        .andExpect(
            jsonPath("$.data.imageKeys[0]")
                .value("listings/%s/cover/1.jpg".formatted(PUBLISHED_LISTING_ID)))
        .andExpect(jsonPath("$.data.rooms[1].roomImageKeys.length()").value(2))
        .andDo(
            document(
                "landlord-listing-detail",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(LANDLORD_LISTING_DETAIL_SUMMARY)
                    .description(LANDLORD_LISTING_DETAIL_DESCRIPTION),
                pathParameters(detailPathParameters()),
                responseFields(landlordListingResponseFields())));
  }

  @Test
  void 문서스니펫생성_임대인아님_403() throws Exception {
    mockMvc
        .perform(
            get("/api/v2/users/me/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(tenantToken())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
        .andDo(
            errorSnippet(
                "landlord-listing-list-forbidden",
                ApiDocsTags.LISTINGS,
                LANDLORD_LISTING_LIST_SUMMARY,
                LANDLORD_LISTING_LIST_DESCRIPTION,
                LANDLORD_LISTING_403));

    // 상세도 같은 게이트를 먼저 지난다. 소유권보다 앞이라 남의 매물이어도 404가 아니라 403이다.
    mockMvc
        .perform(
            get("/api/v2/users/me/listings/{listingId}", OTHER_LANDLORD_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(tenantToken())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }

  @Test
  void 온보딩토큰_403() throws Exception {
    // 매처(hasRole("USER"))가 컨트롤러 앞에서 끊는다. 매처가 없으면 여기까지 도달한다.
    mockMvc
        .perform(
            get("/api/v2/users/me/listings")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(jwtTokenService.issueOnboardingToken(LANDLORD_ID))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 문서스니펫생성_남의매물_404() throws Exception {
    // 403이 아니다. 남의 매물에만 403을 주면 그 코드 차이가 곧 "그 id의 매물은 있다"는 답이 된다.
    mockMvc
        .perform(
            get("/api/v2/users/me/listings/{listingId}", OTHER_LANDLORD_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(landlordToken())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("LISTING_NOT_FOUND"))
        .andDo(
            errorSnippet(
                "landlord-listing-detail-not-found",
                ApiDocsTags.LISTINGS,
                LANDLORD_LISTING_DETAIL_SUMMARY,
                LANDLORD_LISTING_DETAIL_DESCRIPTION,
                detailPathParameters(),
                LANDLORD_LISTING_404));
  }

  @Test
  void 없는매물과_형식오류_404() throws Exception {
    // 위 스니펫과 본문이 한 글자도 다르지 않아 스니펫을 따로 뜨지 않는다. 계약만 붙잡아 둔다.
    mockMvc
        .perform(
            get("/api/v2/users/me/listings/{listingId}", MISSING_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(landlordToken())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("LISTING_NOT_FOUND"));

    // ObjectId 형식이 아니면 저장소가 조회조차 하지 않는다. 400이 아니라 없는 매물과 같은 404다.
    mockMvc
        .perform(
            get("/api/v2/users/me/listings/{listingId}", MALFORMED_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(landlordToken())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("LISTING_NOT_FOUND"));
  }

  @Test
  void 문서스니펫생성_인증실패_401() throws Exception {
    mockMvc
        .perform(
            get("/api/v2/users/me/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andDo(
            errorSnippet(
                "landlord-listing-list-unauthenticated",
                ApiDocsTags.LISTINGS,
                LANDLORD_LISTING_LIST_SUMMARY,
                LANDLORD_LISTING_LIST_DESCRIPTION,
                LANDLORD_LISTING_401));

    // 만료는 위조와 달리 게스트로 강등되지 않고 재발급을 유도하는 코드로 갈린다.
    mockMvc
        .perform(
            get("/api/v2/users/me/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(expiredAccessToken(jwtProperties))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));

    // 헤더가 아예 없어도 위조와 같은 401이다. 로그인 화면으로 보낼 신호가 하나로 모인다.
    mockMvc
        .perform(get("/api/v2/users/me/listings"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  /**
   * 목록 쿼리 기술자다. 셋 다 optional이라 <b>보내지 않은 파라미터가 있어도</b> 스니펫이 만들어진다.
   *
   * <p>두 200 예시가 같은 배열을 나눠 쓴다 — 생성기가 query 파라미터를 {@code (path, method)} 모델 전체에서 모아 이름으로 중복을 제거하므로,
   * 예시마다 문구를 따로 적으면 어느 쪽이 살아남을지가 파일 순회 순서에 좌우된다({@link com.kohere.docs.ApiDocsErrors} 클래스 주석).
   */
  private static ParameterDescriptor[] listQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("status").description("상태 필터. 콤마로 여러 개. 생략하면 전체").optional(),
      parameterWithName("page").description("0부터 시작하는 페이지 번호(기본 0)").optional(),
      parameterWithName("size").description("페이지 크기(기본 20, 최대 100)").optional()
    };
  }

  /**
   * 상세의 path 기술자다. 성공 스니펫과 404 스니펫이 <b>같은 배열</b>을 쓴다.
   *
   * <p>생성기는 path 파라미터만 그룹의 첫 모델 하나에서 가져가므로, 에러 쪽에 선언을 빼면 어느 스니펫이 먼저 읽히느냐에 따라 파라미터 설명이 통째로
   * 사라진다({@link com.kohere.docs.ApiDocsErrors#errorSnippet}).
   */
  private static ParameterDescriptor[] detailPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("listingId").description("조회할 내 매물 ID. 남의 매물이면 404다")
    };
  }

  /**
   * 시드 매물을 복사해 다른 상태의 내 매물을 한 건 더 만든다.
   *
   * <p>정본 시드는 임대인마다 매물이 하나뿐이라 그대로는 "상태를 가리지 않는다"도 "최근 수정순"도 예시로 보일 수 없다.
   *
   * @param updatedAt 정렬 기준. 상태마다 다른 값을 줘 목록 순서가 흔들리지 않게 한다
   */
  private void copyOf(
      String listingId, Listing.ListingStatus status, String rejectionReason, String updatedAt) {
    Listing source = listingRepository.findById(PUBLISHED_LISTING_ID).orElseThrow();
    listingRepository.save(
        source.toBuilder()
            .id(listingId)
            .status(status)
            .rejectionReason(rejectionReason)
            .updatedAt(Instant.parse(updatedAt))
            .build());
  }

  /**
   * 상세 예시가 될 매물을 다듬는다 — 사진 주소를 이 환경 저장소의 주소로 맞추고 방 하나를 내린다.
   *
   * <p>사진 키는 저장된 URL에서 역산하므로, 저장소 주소가 다른 시드 URL은 키를 만들지 못하고 조용히 빠진다. 그대로 두면 {@code
   * imageKeys}·{@code rooms[].roomImageKeys}가 빈 배열로 문서에 실려, 그대로 둘 사진을 되돌려 보낸다는 계약이 예시에서 사라진다.
   *
   * <p>마지막 방만 내리는 이유는 둘이다 — 활성 방이 하나도 없는 매물은 저장이 막히고, 상세가 비활성 방까지 준다는 계약은 내려간 방이 하나 있어야 보인다.
   */
  private void prepareDetailListing() {
    Listing seeded = listingRepository.findById(PUBLISHED_LISTING_ID).orElseThrow();
    List<Listing.RoomOffer> offers = seeded.getRoomOffers();
    List<Listing.RoomOffer> prepared = new ArrayList<>();
    for (int index = 0; index < offers.size(); index++) {
      Listing.RoomOffer offer = offers.get(index);
      boolean last = index == offers.size() - 1;
      prepared.add(
          new Listing.RoomOffer(
              offer.roomOfferId(),
              offer.name(),
              last ? Listing.RoomOfferStatus.INACTIVE : offer.status(),
              offer.contract(),
              offer.pricing(),
              offer.filterTags(),
              storedUrls(
                  "listings/%s/rooms/%s".formatted(PUBLISHED_LISTING_ID, offer.roomOfferId()),
                  offer.roomImageUrls().size())));
    }
    listingRepository.save(
        seeded.toBuilder()
            .imageUrls(
                storedUrls(
                    "listings/%s/cover".formatted(PUBLISHED_LISTING_ID),
                    seeded.getImageUrls().size()))
            .roomOffers(prepared)
            .build());
  }

  /** 저장소가 확정 키에 대해 만들어 주는 읽기 주소다. 키를 되돌릴 수 있는 유일한 형식이다(ADR-0041 §2). */
  private List<String> storedUrls(String keyPrefix, int count) {
    String baseUrl = imageProperties.publicBaseUrl();
    return IntStream.rangeClosed(1, count)
        .mapToObj(index -> "%s/%s/%d.jpg".formatted(baseUrl, keyPrefix, index))
        .toList();
  }

  private String landlordToken() {
    return jwtTokenService.issueAccessToken(LANDLORD_ID);
  }

  private String tenantToken() {
    return jwtTokenService.issueAccessToken(TENANT_ID);
  }
}
