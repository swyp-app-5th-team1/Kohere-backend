package com.kohere.listing.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ApiDocsErrors.errorSnippet;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static com.kohere.docs.ListingDocsFields.LISTING_UPDATE_400;
import static com.kohere.docs.ListingDocsFields.LISTING_UPDATE_401;
import static com.kohere.docs.ListingDocsFields.LISTING_UPDATE_403;
import static com.kohere.docs.ListingDocsFields.LISTING_UPDATE_404;
import static com.kohere.docs.ListingDocsFields.LISTING_UPDATE_409;
import static com.kohere.docs.ListingDocsFields.LISTING_UPDATE_422;
import static com.kohere.docs.ListingDocsFields.LISTING_UPDATE_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.LISTING_UPDATE_SUMMARY;
import static com.kohere.docs.ListingDocsFields.landlordListingResponseFields;
import static com.kohere.docs.ListingDocsFields.updateRequestFields;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
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
import java.util.List;
import java.util.Optional;
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
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.request.ParameterDescriptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 매물 수정({@code PUT /api/v2/listings/{listingId}})의 REST Docs 스니펫 생성 테스트다(US-3-9).
 *
 * <p>등록({@link ListingRegisterDocsTest})과 파일을 나눈 이유는 계약이 갈리기 때문이다 — 요청 본문은 거의 같지만 성공 응답이 세입자 상세가
 * 아니라 <b>임대인 상세</b>이고, 상태 전이·사진 자리 검사·조건부 저장에서만 나오는 실패 코드가 넷(422 둘 · 409 · 400 하나) 더 있다.
 *
 * <p>문구·필드 기술자는 {@code ListingDocsFields}에 한 벌만 두고 여기서는 흐름만 만든다 — 성공·에러 스니펫이 같은 오퍼레이션으로 병합되므로
 * summary·description은 상수 하나를, 같은 status의 에러 스니펫은 같은 코드 배열을 써야 한다(ADR-0017).
 *
 * <p><b>사진 키가 이 테스트의 준비 절반이다.</b> 수정 요청은 그대로 둘 사진을 <b>확정 키</b>로 가리키는데, 그 키는 저장된 URL을 {@code
 * ListingImageStorage#keyOf}가 되돌린 값이라 문서의 URL이 {@code app.images}의 읽기 주소 형식과 맞아야 한다. 정본 시드는 운영 CDN
 * 주소로 적혀 있어 테스트 프로파일에서는 키가 되돌아오지 않으므로, 시드 매물 하나를 이 계정 소유로 바꾸면서 사진 URL도 함께 다시 심는다.
 *
 * <p>cross-module 협력({@code user :: api}의 {@code userType}·표시 언어)은 {@link MockitoBean}으로 대체하고
 * access 토큰은 {@link JwtTokenService}로 직접 발급한다(test-strategy §4).
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class ListingUpdateDocsTest {

  /** 시드 매물을 넘겨받는 임대인 계정이다. 표시 언어가 한국어라 응답 label도 한국어로 내려간다. */
  private static final long LANDLORD_ID = 42L;

  /** 정식 회원이지만 임대인이 아니라 서비스가 403으로 거르는 계정이다. */
  private static final long TENANT_ID = 1L;

  private static final String LISTINGS_COLLECTION = "listings";
  private static final String LISTING_CATALOG_COLLECTION = "listingCatalog";
  private static final String UNIVERSITIES_COLLECTION = "universities";

  /** 이 계정 소유로 바꿔 쓰는 시드 매물이다. */
  private static final String LISTING_ID = ListingTestSeeds.LISTING_ID;

  /** 계속 파는 방이다. 수정 요청이 {@code ACTIVE}로 그대로 다시 보낸다. */
  private static final String ROOM_OFFER_ID = ListingTestSeeds.ROOM_OFFER_ID;

  /** 이번 수정으로 내리는 방이다. 요청에서 빼는 것이 아니라 {@code INACTIVE}로 보낸다. */
  private static final String CLOSED_ROOM_OFFER_ID = "68e0000000000000000001a2";

  /** 두 번째 시드 매물은 다른 임대인(12) 소유 그대로 둔다 — 「남의 매물」 404의 예시다. */
  private static final String OTHER_LANDLORD_LISTING_ID = ListingTestSeeds.SECOND_LISTING_ID;

  private static final String MISSING_LISTING_ID = "68e0000000000000000000ff";

  /** 관리자가 남긴 반려 사유다. 수정에 성공하면 서버가 지운다. */
  private static final String REJECTION_REASON = "대표사진이 매물 외관을 확인할 수 없는 각도입니다.";

  /** 그대로 두는 대표사진의 확정 키다. */
  private static final String KEPT_COVER_KEY = "listings/" + LISTING_ID + "/cover/cover-1.jpg";

  /** 계속 파는 방에서 그대로 두는 사진의 확정 키다. */
  private static final String KEPT_ROOM_KEY =
      "listings/" + LISTING_ID + "/rooms/" + ROOM_OFFER_ID + "/room-1.jpg";

  /** 같은 방에서 새 사진으로 밀려나는 확정 키다. 본문이 가리키지 않아 저장 뒤 참조를 잃는다. */
  private static final String REPLACED_ROOM_KEY =
      "listings/" + LISTING_ID + "/rooms/" + ROOM_OFFER_ID + "/room-2.jpg";

  /** 내리는 방의 사진이다. {@code INACTIVE}로 보내도 문서에 그대로 남는다. */
  private static final String CLOSED_ROOM_KEY_A =
      "listings/" + LISTING_ID + "/rooms/" + CLOSED_ROOM_OFFER_ID + "/room-1.jpg";

  private static final String CLOSED_ROOM_KEY_B =
      "listings/" + LISTING_ID + "/rooms/" + CLOSED_ROOM_OFFER_ID + "/room-2.jpg";

  /** 새로 올린 사진의 파일명이다. 확정 위치로 옮겨도 파일명은 그대로다. */
  private static final String NEW_COVER_FILE = "3f9a1c2e-1d2b-4c3a-9f10-2b7c5d8e4a11.jpg";

  private static final String NEW_ROOM_FILE = "7b2e8841-2a3b-4c5d-8e9f-0a1b2c3d4e55.jpg";

  private static final String NEW_COVER_KEY = "uploads/" + LANDLORD_ID + "/" + NEW_COVER_FILE;

  private static final String NEW_ROOM_KEY = "uploads/" + LANDLORD_ID + "/" + NEW_ROOM_FILE;

  /** 임시 키가 확정 위치로 승격된 결과다. 응답 {@code imageKeys}·{@code rooms[].roomImageKeys}가 이 값을 돌려준다. */
  private static final String PROMOTED_COVER_KEY =
      "listings/" + LISTING_ID + "/cover/" + NEW_COVER_FILE;

  private static final String PROMOTED_ROOM_KEY =
      "listings/" + LISTING_ID + "/rooms/" + ROOM_OFFER_ID + "/" + NEW_ROOM_FILE;

  /** 수정 본문이 반드시 가리켜야 하는 키다. 하나라도 어긋나면 400이 나 원인이 흐려지므로 요청 전에 대조한다. */
  private static final List<String> REFERENCED_KEYS =
      List.of(
          KEPT_COVER_KEY,
          NEW_COVER_KEY,
          KEPT_ROOM_KEY,
          NEW_ROOM_KEY,
          CLOSED_ROOM_KEY_A,
          CLOSED_ROOM_KEY_B);

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

  /**
   * 스펙 예시와 같은 정상 수정 본문이다. 에러 스니펫은 {@link #bodyReplacing(String, String)}으로 여기서 한 곳만 바꿔 쓴다.
   *
   * <p>등록 본문과 다른 곳은 둘뿐이다 — 방마다 {@code roomOfferId}·{@code status}가 있고, 사진 키에 새로 올린 임시 키({@code
   * uploads/…})와 그대로 둘 확정 키({@code listings/…})가 <b>섞여</b> 있다.
   */
  private static final String UPDATE_BODY =
      """
      {
        "title": "신촌 도보 5분 1인실 고시원",
        "type": "GOSHIWON",
        "contact": {
          "managerName": "김운영",
          "phone": "+82) 10-1234-5678"
        },
        "businessRegistrationNumber": "1234567890",
        "blogUrl": "https://blog.naver.com/kohere-goshiwon",
        "address": {
          "fullAddress": "서울특별시 서대문구 신촌로 12",
          "detail": "3층 305호",
          "lat": 37.5559918,
          "lng": 126.9368647
        },
        "building": {
          "type": "VILLA",
          "totalFloors": 4,
          "usedFloorRange": "1~2",
          "parkingAvailable": true,
          "elevatorAvailable": true
        },
        "genderPolicy": "FEMALE_ONLY",
        "languagesSupported": ["ENGLISH", "CHINESE"],
        "ageRange": "20~35",
        "arcRequired": "NOT_REQUIRED",
        "facilities": {
          "heatingSystem": ["CENTRAL"],
          "kitchen": ["SHARED_REFRIGERATOR", "MICROWAVE"],
          "laundry": ["WASHER", "DRYING_RACK"],
          "livingAmenities": ["WIFI", "TV"],
          "securityFeatures": ["CCTV", "ENTRANCE_DOOR_LOCK"],
          "commonSpaces": ["SHARED_KITCHEN", "SHARED_TOILET"],
          "providedSupplies": ["BEDDING", "TISSUE"]
        },
        "nearbyFacilities": ["CONVENIENCE_STORE", "HOSPITAL_PHARMACY"],
        "nearestTransit": {
          "type": "SUBWAY",
          "name": "신촌역",
          "walkMinutes": 5
        },
        "description": "지하철역에서 도보 5분 거리의 관리가 잘 된 고시원입니다.",
        "extraNotes": "객실 내 취사 금지. 오후 11시 이후 정숙.",
        "refundPolicy": "입주 7일 전까지 취소하면 전액 환불합니다.",
        "imageKeys": [
          "listings/68e0000000000000000000a1/cover/cover-1.jpg",
          "uploads/42/3f9a1c2e-1d2b-4c3a-9f10-2b7c5d8e4a11.jpg"
        ],
        "roomOffers": [
          {
            "roomOfferId": "68e0000000000000000001a1",
            "status": "ACTIVE",
            "name": "스탠다드 1인실",
            "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
            "pricing": {
              "monthlyRent": 380000,
              "deposit": 200000,
              "maintenanceFee": 20000
            },
            "filterTags": ["ENGLISH_OK", "ADDRESS_REGISTRATION"],
            "roomImageKeys": [
              "listings/68e0000000000000000000a1/rooms/68e0000000000000000001a1/room-1.jpg",
              "uploads/42/7b2e8841-2a3b-4c5d-8e9f-0a1b2c3d4e55.jpg"
            ]
          },
          {
            "roomOfferId": "68e0000000000000000001a2",
            "status": "INACTIVE",
            "name": "프리미엄 1인실",
            "contract": { "minStayMonths": 3, "maxStayMonths": 24 },
            "pricing": {
              "monthlyRent": 520000,
              "deposit": 500000,
              "maintenanceFee": 20000
            },
            "filterTags": ["PRIVATE_BATH", "ADDRESS_REGISTRATION"],
            "roomImageKeys": [
              "listings/68e0000000000000000000a1/rooms/68e0000000000000000001a2/room-1.jpg",
              "listings/68e0000000000000000000a1/rooms/68e0000000000000000001a2/room-2.jpg"
            ]
          }
        ],
        "preferredNationalities": ["JAPAN", "CHINA"],
        "contractDifficulties": ["LANGUAGE", "PAYMENT"],
        "serviceFeedback": "외국인 세입자용 계약서 번역 템플릿이 있으면 좋겠습니다.",
        "consents": {
          "privacyPolicyAgreed": true,
          "listingExposureAgreed": true
        }
      }
      """;

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired private WebApplicationContext context;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private JwtProperties jwtProperties;
  @Autowired private ListingImageProperties listingImageProperties;

  /**
   * 조회와 저장 사이의 상태 변경(409)은 실제 동시성 없이는 재현되지 않는다. 조건부 교체만 빈 값으로 바꾸고 나머지 조회·저장은 실제 어댑터를 그대로 태운다 — 전체를
   * 목으로 바꾸면 이 테스트가 문서화하려는 흐름 자체가 사라진다.
   */
  @MockitoSpyBean private ListingRepository listingRepository;

  @MockitoBean private UserAccountService userAccountService;

  private MockMvc mockMvc;

  /** REST Docs용 MockMvc를 만들고 카탈로그·대학 원장·매물을 시드한 뒤 시드 매물 하나를 이 임대인의 반려 매물로 바꾼다. */
  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    mongoTemplate.getCollection(LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(LISTING_CATALOG_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(UNIVERSITIES_COLLECTION).deleteMany(new Document());
    ListingTestSeeds.seedCatalog(mongoTemplate, LISTING_CATALOG_COLLECTION);
    // 수정도 등록과 같은 조립기를 타 좌표로 인근 대학을 다시 파생한다(ADR-0045).
    ListingTestSeeds.seedUniversities(mongoTemplate, UNIVERSITIES_COLLECTION);
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);
    requireBodyReferencesSeededKeys();
    seedOwnRejectedListing();

    given(userAccountService.getUserType(LANDLORD_ID)).willReturn("LANDLORD");
    given(userAccountService.getLanguage(LANDLORD_ID)).willReturn("ko");
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
  }

  /** 반려 매물 수정 성공 — 상태가 심사 대기로 돌아가고 반려 사유가 사라지며, 사진 키가 자리별로 다시 정리된다. */
  @Test
  void 문서스니펫생성_반려매물수정_PENDING으로되돌아간다() throws Exception {
    mockMvc
        .perform(update(landlordToken(), LISTING_ID, UPDATE_BODY))
        .andExpect(status().isOk())
        // REJECTED → PENDING. 최초 등록과 같은 줄에 선다.
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.listing.status").value("PENDING"))
        // 반려 사유는 수정으로 지워지지 않는다 — 심사를 기다리는 동안 무엇을 고치라고 했는지
        // 임대인이 다시 볼 수 있어야 하고, 재심사하는 관리자도 이전 반려 맥락을 본다.
        // 지우는 것은 승인 시점뿐이다.
        .andExpect(jsonPath("$.data.rejectionReason").value(REJECTION_REASON))
        // 세입자에게 감추는 값이 임대인 응답에는 그대로 실린다. 전부 수정 요청 필드다.
        .andExpect(jsonPath("$.data.businessRegistrationNumber").value("1234567890"))
        .andExpect(jsonPath("$.data.serviceFeedback").isString())
        // 최초 동의 이력은 이번 요청으로 덮지 않는다.
        .andExpect(jsonPath("$.data.consents.version").value("v1.0"))
        // 그대로 둔 확정 키는 그 자리에 남고, 새로 올린 임시 키는 파일명을 유지한 채 확정 위치로 승격된다.
        .andExpect(jsonPath("$.data.imageKeys[0]").value(KEPT_COVER_KEY))
        .andExpect(jsonPath("$.data.imageKeys[1]").value(PROMOTED_COVER_KEY))
        .andExpect(jsonPath("$.data.rooms[0].roomImageKeys[0]").value(KEPT_ROOM_KEY))
        .andExpect(jsonPath("$.data.rooms[0].roomImageKeys[1]").value(PROMOTED_ROOM_KEY))
        // rooms[]는 내린 방까지 담고, 중첩된 세입자 상세는 활성 방만 담는다.
        .andExpect(jsonPath("$.data.rooms.length()").value(2))
        .andExpect(jsonPath("$.data.rooms[1].roomOfferId").value(CLOSED_ROOM_OFFER_ID))
        .andExpect(jsonPath("$.data.rooms[1].status").value("INACTIVE"))
        .andExpect(jsonPath("$.data.listing.roomOffers.length()").value(1))
        .andExpect(jsonPath("$.data.listing.roomOffers[0].roomOfferId").value(ROOM_OFFER_ID))
        // 전체 교체라 주소에서 파생되는 값도 새 주소 기준으로 다시 만들어진다.
        .andExpect(jsonPath("$.data.listing.address.district.code").value("SEODAEMUN_GU"))
        .andExpect(jsonPath("$.data.listing.location.lat").value(37.5559918))
        .andDo(
            document(
                "listing-update",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(LISTING_UPDATE_SUMMARY)
                    .description(LISTING_UPDATE_DESCRIPTION),
                pathParameters(listingIdPathParameters()),
                requestFields(updateRequestFields()),
                responseFields(landlordListingResponseFields())));
  }

  /**
   * 공개 중인 매물을 고치면 재심사 대기로 넘어간다.
   *
   * <p>스니펫은 만들지 않는다 — 같은 오퍼레이션의 성공 예시는 한 벌이면 되고, 두 벌을 뜨면 어느 쪽이 Swagger에 남을지가 파일 순회 순서에 좌우된다.
   */
  @Test
  void 수정_공개매물은_UPDATE_PENDING으로_넘어간다() throws Exception {
    changeStatus(Listing.ListingStatus.PUBLISHED);

    mockMvc
        .perform(update(landlordToken(), LISTING_ID, UPDATE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("UPDATE_PENDING"))
        // 심사가 끝날 때까지 세입자 조회에서 빠지지만 찜 수는 줄지 않는다 — 승인되면 그대로 돌아온다.
        .andExpect(jsonPath("$.data.listing.favoriteCount").value(0));
  }

  /** 이미 심사 대기열에 있는 매물은 고칠 수 없다. 수정 신청 취소도 없어 관리자가 심사를 마칠 때까지 기다린다. */
  @Test
  void 문서스니펫생성_심사중매물수정_422() throws Exception {
    changeStatus(Listing.ListingStatus.PENDING);
    performError(
        update(landlordToken(), LISTING_ID, UPDATE_BODY),
        status().isUnprocessableEntity(),
        "LISTING_NOT_EDITABLE",
        "listing-update-not-editable",
        LISTING_UPDATE_422);

    // 수정 심사 대기도 같은 이유로 막힌다. 스니펫은 위 한 벌로 충분하다.
    changeStatus(Listing.ListingStatus.UPDATE_PENDING);
    mockMvc
        .perform(update(landlordToken(), LISTING_ID, UPDATE_BODY))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("LISTING_NOT_EDITABLE"));
  }

  /**
   * 동의 2종은 수정할 때도 다시 받고 다시 확인한다.
   *
   * <p>{@code false}로 보내야 이 코드가 나온다 — 키를 빼면 {@code @NotNull}에 먼저 걸려 {@code 400 INVALID_INPUT}이다. 그
   * 구분을 위해 요청 DTO가 원시 {@code boolean}이 아니라 {@code Boolean}으로 받는다.
   */
  @Test
  void 문서스니펫생성_동의누락_422() throws Exception {
    performError(
        update(
            landlordToken(),
            LISTING_ID,
            bodyReplacing("\"privacyPolicyAgreed\": true", "\"privacyPolicyAgreed\": false")),
        status().isUnprocessableEntity(),
        "LISTING_REQUIRED_AGREEMENT_MISSING",
        "listing-update-agreement-missing",
        LISTING_UPDATE_422);
  }

  /**
   * 읽은 뒤 저장하기 전에 관리자가 심사를 끝낸 경우다.
   *
   * <p>저장은 읽은 시점의 상태를 조건으로 건 교체라 조건이 어긋나면 아무것도 쓰지 않고 빈 값이 돌아온다. 그 사이에 사진 확정 복사(네트워크)가 끼어 창이 수백 ms에
   * 이르므로 실제로 닿는 경로이며, 재조회 후 다시 보내면 성공할 수 있어 422가 아니라 409다.
   */
  @Test
  void 문서스니펫생성_조회와저장사이_상태변경_409() throws Exception {
    willReturn(Optional.empty())
        .given(listingRepository)
        .saveIfStatus(any(Listing.class), any(Listing.ListingStatus.class));

    performError(
        update(landlordToken(), LISTING_ID, UPDATE_BODY),
        status().isConflict(),
        "LISTING_STATE_CHANGED",
        "listing-update-state-changed",
        LISTING_UPDATE_409);
  }

  /** 설문 2종을 생략한 수정은 성공하고 저장된 값이 빈 배열로 교체된다 — 전체 교체라 생략은 유지가 아니라 삭제다(#270). */
  @Test
  void 설문생략_수정성공_빈배열로교체() throws Exception {
    String body =
        UPDATE_BODY
            .replaceAll("(?m)^ *\"preferredNationalities\".*\\R", "")
            .replaceAll("(?m)^ *\"contractDifficulties\".*\\R", "");
    if (body.contains("preferredNationalities") || body.contains("contractDifficulties")) {
      throw new IllegalStateException("수정 본문에서 설문 두 필드를 빼지 못했다");
    }

    mockMvc
        .perform(update(landlordToken(), LISTING_ID, body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.preferredNationalities").isArray())
        .andExpect(jsonPath("$.data.preferredNationalities").isEmpty())
        .andExpect(jsonPath("$.data.contractDifficulties").isEmpty());
  }

  /**
   * 시설의 {@code NONE}은 단독으로만 보낼 수 있다(#270). 카탈로그에 실재하는 정상 코드라 코드 대조로는 걸러지지 않고, 등록·수정이 조립을 공유하므로 이
   * 규칙도 두 경로에 똑같이 걸린다.
   *
   * <p>문서 스니펫은 등록 쪽에 한 벌 있으므로 여기서는 만들지 않는다 — 계약 회귀만 지킨다.
   */
  @Test
  void NONE을_다른_코드와_함께_보내면_400() throws Exception {
    mockMvc
        .perform(
            update(
                landlordToken(),
                LISTING_ID,
                bodyReplacing("[\"WIFI\", \"TV\"]", "[\"NONE\", \"WIFI\"]")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.error.errors[0].field").value("facilities.livingAmenities"));
  }

  /** 시설이 하나도 없는 상태로도 수정된다 — 저장·응답 모두 {@code NONE} 하나다(#270). */
  @Test
  void 시설없음_NONE_단독_수정() throws Exception {
    mockMvc
        .perform(
            update(
                landlordToken(),
                LISTING_ID,
                bodyReplacing("[\"WASHER\", \"DRYING_RACK\"]", "[\"NONE\"]")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listing.facilities.laundry.length()").value(1))
        .andExpect(jsonPath("$.data.listing.facilities.laundry[0].code").value("NONE"));
  }

  /**
   * 그 매물의 것이 아닌 방 식별자는 400이다. 남의 매물 방을 끌어오거나 오타로 새 방이 생기는 것을 막는다.
   *
   * <p>어느 칸이 틀렸는지는 {@code errors[]}로만 알 수 있다 — {@code error.message}는 코드별 일반 문구로 덮인다.
   */
  @Test
  void 문서스니펫생성_문서에없는방식별자_400() throws Exception {
    mockMvc
        .perform(
            update(
                landlordToken(),
                LISTING_ID,
                bodyReplacing(
                    "\"roomOfferId\": \"" + CLOSED_ROOM_OFFER_ID + "\"",
                    "\"roomOfferId\": \"68e00000000000000000ffff\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.error.errors[0].field").value("roomOffers.roomOfferId"))
        .andDo(
            errorSnippet(
                "listing-update-invalid-room-offer-id",
                ApiDocsTags.LISTINGS,
                LISTING_UPDATE_SUMMARY,
                LISTING_UPDATE_DESCRIPTION,
                listingIdPathParameters(),
                LISTING_UPDATE_400));
  }

  /** 남의 매물과 없는 매물을 구분하지 않는다 — 코드를 나누면 그 차이가 곧 존재 여부를 알려주는 통로가 된다. */
  @Test
  void 문서스니펫생성_남의매물_404() throws Exception {
    performError(
        update(landlordToken(), OTHER_LANDLORD_LISTING_ID, UPDATE_BODY),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        "listing-update-not-found",
        LISTING_UPDATE_404);

    mockMvc
        .perform(update(landlordToken(), MISSING_LISTING_ID, UPDATE_BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("LISTING_NOT_FOUND"));
  }

  /** 임대인 여부는 보안 매처가 아니라 서비스가 본다 — 매처는 정식 회원인지까지만 본다. */
  @Test
  void 문서스니펫생성_임대인아님_403() throws Exception {
    // 유효한 본문이 있어야 컨트롤러에 도달하므로 본문을 그대로 싣는다.
    performError(
        update(tenantToken(), LISTING_ID, UPDATE_BODY),
        status().isForbidden(),
        "FORBIDDEN",
        "listing-update-forbidden",
        LISTING_UPDATE_403);

    // 온보딩 토큰은 매처가 컨트롤러 앞에서 끊는다 — 본문 없이도 도달한다(415를 피해 contentType만 남긴다).
    performError(
        emptyUpdate()
            .header(
                HttpHeaders.AUTHORIZATION, bearer(jwtTokenService.issueOnboardingToken(TENANT_ID))),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "listing-update-onboarding-required",
        LISTING_UPDATE_403);
  }

  /** 보안 필터가 내는 401이라 본문 없이 도달한다. 위조는 게스트로 강등되지 않고 만료는 재발급을 유도한다. */
  @Test
  void 문서스니펫생성_인증실패_401() throws Exception {
    performError(
        emptyUpdate().header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "listing-update-unauthenticated",
        LISTING_UPDATE_401);

    performError(
        emptyUpdate().header(HttpHeaders.AUTHORIZATION, bearer(expiredAccessToken(jwtProperties))),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "listing-update-token-expired",
        LISTING_UPDATE_401);
  }

  private void performError(
      MockHttpServletRequestBuilder request,
      ResultMatcher expectedStatus,
      String expectedCode,
      String identifier,
      String... errorCodes)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(expectedStatus)
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value(expectedCode))
        .andDo(
            errorSnippet(
                identifier,
                ApiDocsTags.LISTINGS,
                LISTING_UPDATE_SUMMARY,
                LISTING_UPDATE_DESCRIPTION,
                listingIdPathParameters(),
                errorCodes));
  }

  private MockHttpServletRequestBuilder update(String bearerToken, String listingId, String body) {
    return put("/api/v2/listings/{listingId}", listingId)
        .header(HttpHeaders.AUTHORIZATION, bearerToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  /** 본문 없이 보내는 요청. 토큰 자체가 거절되는 401·403에는 본문이 필요 없다. */
  private MockHttpServletRequestBuilder emptyUpdate() {
    return put("/api/v2/listings/{listingId}", LISTING_ID).contentType(MediaType.APPLICATION_JSON);
  }

  /**
   * 에러 스니펫에도 path 파라미터를 선언한다.
   *
   * <p>생성기는 path 파라미터를 그룹의 <b>첫</b> ResourceModel 하나에서만 가져오는데 그 순서가 파일 순회에 좌우된다 — 성공 스니펫이 첫 모델이 아닐
   * 수 있으므로 모든 모델이 같은 선언을 갖게 한다({@code ApiDocsErrors}).
   */
  private static ParameterDescriptor[] listingIdPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("listingId").description("수정할 매물 ID. 내 매물 목록·상세 응답의 listingId를 그대로 넣는다")
    };
  }

  /** 시드 매물 하나를 이 임대인의 반려 매물로 바꾸고 사진 URL도 테스트 저장소 주소 형식으로 다시 심는다. */
  private void seedOwnRejectedListing() {
    Listing seeded = listingRepository.findById(LISTING_ID).orElseThrow();
    List<Listing.RoomOffer> rooms =
        seeded.getRoomOffers().stream()
            .map(
                offer ->
                    new Listing.RoomOffer(
                        offer.roomOfferId(),
                        offer.name(),
                        offer.status(),
                        offer.contract(),
                        offer.pricing(),
                        offer.filterTags(),
                        seededRoomImageUrls(offer.roomOfferId())))
            .toList();
    listingRepository.save(
        seeded.toBuilder()
            .landlordId(LANDLORD_ID)
            .status(Listing.ListingStatus.REJECTED)
            .rejectionReason(REJECTION_REASON)
            .imageUrls(List.of(urlOf(KEPT_COVER_KEY)))
            .roomOffers(rooms)
            .build());
  }

  /** 시드 매물의 상태만 바꾼다. 반려 사유는 반려 상태에서만 남긴다. */
  private void changeStatus(Listing.ListingStatus status) {
    Listing current = listingRepository.findById(LISTING_ID).orElseThrow();
    listingRepository.save(
        current.toBuilder()
            .status(status)
            .rejectionReason(status == Listing.ListingStatus.REJECTED ? REJECTION_REASON : null)
            .build());
  }

  private List<String> seededRoomImageUrls(String roomOfferId) {
    List<String> keys =
        ROOM_OFFER_ID.equals(roomOfferId)
            ? List.of(KEPT_ROOM_KEY, REPLACED_ROOM_KEY)
            : List.of(CLOSED_ROOM_KEY_A, CLOSED_ROOM_KEY_B);
    return keys.stream().map(this::urlOf).toList();
  }

  /**
   * 저장 키를 문서에 실리는 읽기 URL로 만든다.
   *
   * <p>주소를 손으로 적지 않고 설정에서 가져오는 이유는 {@code keyOf}가 이 형식의 역함수이기 때문이다 — 형식이 어긋나면 확정 키가 되돌아오지 않아 수정 요청이
   * 통째로 {@code 400 LISTING_IMAGE_KEY_NOT_FOUND}가 된다.
   */
  private String urlOf(String key) {
    return listingImageProperties.publicBaseUrl() + "/" + key;
  }

  /** 정상 본문에서 한 값만 바꾼 변형이다. 본문에서 유일하게 지목할 수 있는 값만 바꾼다. */
  private static String bodyReplacing(String original, String replacement) {
    int first = UPDATE_BODY.indexOf(original);
    if (first < 0 || first != UPDATE_BODY.lastIndexOf(original)) {
      throw new IllegalArgumentException("수정 본문에서 유일하게 지목할 수 없는 값이다: " + original);
    }
    return UPDATE_BODY.replace(original, replacement);
  }

  /**
   * 본문의 사진 키와 시드가 심는 키가 어긋나지 않았는지 먼저 본다.
   *
   * <p>어긋나면 준비가 잘못된 것인데 응답은 사진 키가 틀렸을 때와 똑같은 {@code 400}이라, 여기서 잡지 않으면 원인을 찾는 데만 한참 걸린다.
   */
  private static void requireBodyReferencesSeededKeys() {
    for (String key : REFERENCED_KEYS) {
      if (!UPDATE_BODY.contains("\"" + key + "\"")) {
        throw new IllegalStateException("수정 본문이 가리키지 않는 사진 키다: " + key);
      }
    }
    if (UPDATE_BODY.contains(REPLACED_ROOM_KEY)) {
      throw new IllegalStateException("교체될 사진의 키가 본문에 남아 있다: " + REPLACED_ROOM_KEY);
    }
  }

  private String landlordToken() {
    return bearer(jwtTokenService.issueAccessToken(LANDLORD_ID));
  }

  private String tenantToken() {
    return bearer(jwtTokenService.issueAccessToken(TENANT_ID));
  }
}
