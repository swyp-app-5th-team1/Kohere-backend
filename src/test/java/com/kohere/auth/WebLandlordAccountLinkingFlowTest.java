package com.kohere.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.TestcontainersConfiguration;
import com.kohere.auth.domain.AppleAuthClient;
import com.kohere.auth.domain.OidcTokenVerifier;
import com.kohere.auth.domain.OidcUser;
import com.kohere.auth.domain.VerificationEmailSender;
import com.kohere.auth.domain.VerificationSmsSender;
import com.mongodb.client.model.InsertManyOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 임대인 웹·앱 <b>양방향 계정 연동</b> 종단 통합 테스트(#229 — US-1-11 · US-1-15). 이슈가 존재하는 이유 그 자체를 검증한다: 한 사람이 앱과 웹
 * 어느 쪽으로 먼저 들어와도 <b>같은 {@code users.id}로 수렴</b>해야 하고, 그래야 웹에서 등록한 매물의 예약이 앱에서 그대로 보인다.
 *
 * <p><b>연동은 방향에 따라 판정 시점과 동작이 다르다</b>(스펙 §개요 웹 임대인 트랙).
 *
 * <ul>
 *   <li><b>앱 먼저</b> → 웹 가입 제출(§1-3)에서 <b>연결</b>: 번호로 찾은 기존 계정에 {@code local_accounts} 행만 붙이고 {@code
 *       users}는 한 행도 늘리지 않는다({@code linked=true}).
 *   <li><b>웹 먼저</b> → 앱 임대인 온보딩 제출(§5-2)에서 <b>병합</b>: 소셜 로그인 시점엔 서버가 번호를 몰라 이미 임시 {@code users} 행이
 *       만들어진 뒤이므로, {@code social_accounts}를 웹 계정으로 옮기고 임시 행을 하드 삭제한다.
 * </ul>
 *
 * <p><b>소유권 사슬을 실제로 태워 확인한다</b> — 매물 등록의 {@code landlordId}는 토큰의 {@code userId}이고 예약의 {@code
 * landlordId}는 그 값의 비정규화 복사본이라, id가 갈리면 "앱에서 내 매물의 예약이 안 보인다"가 된다. 그래서 두 방향 모두 <b>웹 세션으로 매물을
 * 등록</b>하고 그 매물의 {@code landlordId}가 앱 소셜 로그인이 귀결되는 id와 같은지를 단정하며, 앱 먼저 시나리오는 <b>세입자 예약까지 실제로
 * 만들어</b> 앱 임대인 목록 조회에 그 예약이 나오는 것까지 확인한다.
 *
 * <p>가짜로 대체하는 것은 통제 불가능한 외부 연동뿐이다 — 소셜 OIDC 검증({@code idToken == subject})·Apple 인가코드 교환·SMS
 * 발송(인증번호 캡처)이며, Security·JPA(MySQL)·Redis·MongoDB(매물)·JWT·도메인 이벤트는 실제로 구동한다(test-strategy
 * §3-4·§4). 매물 등록이 코드 카탈로그(번역 사전)와 대학 좌표 원장을 읽으므로 Mongock이 꺼진 테스트에서는 정본 fixture를 직접 심는다.
 *
 * <p>단일 엔드포인트의 계약·에러 스니펫은 {@code AuthOnboardingDocsTest}·{@code LandlordOnboardingDocsTest}·{@code
 * WebAuthDocsTest}가 담당한다. 이 클래스는 <b>여러 모듈에 걸친 시나리오</b>만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class WebLandlordAccountLinkingFlowTest {

  private static final String LISTINGS_COLLECTION = "listings";
  private static final String LISTING_CATALOG_COLLECTION = "listingCatalog";
  private static final String UNIVERSITIES_COLLECTION = "universities";

  private static final String CATALOG_RESOURCE = "fixtures/listing-catalog-v4.json";
  private static final String UNIVERSITIES_RESOURCE = "fixtures/universities.json";

  /** 웹 가입 폼의 비밀번호(정책: 영문+숫자+ASCII 특수문자, 8~20자). */
  private static final String PASSWORD = "Kohere1!";

  private static final String BIRTH_DATE = "1988-05-20";

  /**
   * 시나리오마다 번호를 다르게 쓴다 — {@code users.phone_number}에 UNIQUE(V23)가 걸려 있어 한 컨텍스트·한 DB에서 같은 번호로 두 개의
   * 임대인 계정을 확정할 수 없다. 뒤 4자리는 시나리오 식별용이다.
   */
  private static final String APP_FIRST_PHONE = "01011110001";

  private static final String WEB_FIRST_PHONE = "01011110002";
  private static final String TWICE_MERGED_PHONE = "01011110003";

  @TestConfiguration
  static class FakeOidcConfig {
    @Bean
    @Primary
    OidcTokenVerifier fakeOidcTokenVerifier() {
      return (provider, idToken) -> new OidcUser(provider, idToken, idToken + "@example.com");
    }
  }

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private VerificationSmsSender smsSender; // 실제 SMS 발송 대체(인증번호 캡처)
  @MockitoBean private VerificationEmailSender emailSender; // 가입용 이메일 인증번호 캡처(#285)
  private final Map<String, String> sentEmailCodes = new ConcurrentHashMap<>();
  @MockitoBean private AppleAuthClient appleAuthClient; // 실제 Apple HTTP 대체

  private final ObjectMapper objectMapper = new ObjectMapper();

  /** 발송된 인증번호를 연락처별로 기록한다. 앱 트랙·가입 트랙이 같은 SMS 포트를 쓰므로 한 맵으로 충분하다. */
  private final Map<String, String> sentCodes = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    doAnswer(
            inv -> {
              sentCodes.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(smsSender)
        .send(any(), any());
    // 가입용 이메일 인증번호도 캡처한다 — 가입 제출이 연락처·이메일 두 마커를 모두 요구한다(#285).
    doAnswer(
            inv -> {
              sentEmailCodes.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(emailSender)
        .send(any(), any());

    mongoTemplate.getCollection(LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(LISTING_CATALOG_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(UNIVERSITIES_COLLECTION).deleteMany(new Document());
    // Mongock이 꺼진 테스트에는 시드를 심을 주체가 없다. 카탈로그가 없으면 등록이
    // LISTING_UNKNOWN_CATALOG_CODE로 거절되고, 대학 원장이 없으면 인근 대학이 빈 배열로 저장된다.
    seed(LISTING_CATALOG_COLLECTION, CATALOG_RESOURCE);
    seed(UNIVERSITIES_COLLECTION, UNIVERSITIES_RESOURCE);
  }

  /**
   * <b>앱 먼저 → 웹 가입에서 연결</b>(US-1-11). 앱에서 임대인 온보딩까지 마친 사람이 같은 번호로 웹에 가입하면 계정이 새로 생기지 않고 자격증명만
   * 붙는다({@code linked=true}). 그 뒤 <b>웹 세션으로 등록한 매물</b>에 세입자가 예약을 넣으면 <b>앱 소셜 로그인이 귀결되는 그 계정</b>의 받은
   * 신청 목록에 나온다 — 이 사슬이 곧 연동의 존재 이유다.
   *
   * <p>단정의 핵심은 셋이다. ① 응답 {@code linked=true} ② {@code users} 행 수가 <b>가입 전후로 같다</b>(연동은 계정 생성이 아니다)
   * ③ 웹 세션의 {@code /users/me}가 앱 계정 id를 그대로 돌려준다. ②를 행 수로 세는 것은 "새 계정이 만들어지지 않았다"를 응답이 아니라 <b>DB
   * 상태</b>로 확인하기 위해서다 — {@code linked} 플래그만 보면 플래그와 실제 쓰기가 갈리는 회귀를 놓친다.
   */
  @Test
  @DisplayName("앱 먼저 온보딩한 임대인이 같은 번호로 웹 가입하면 계정을 새로 만들지 않고 연동되고, 웹에서 등록한 매물의 예약이 앱에서 보인다")
  void 앱먼저_웹가입은_계정을늘리지않고연동되며_웹매물예약이앱에서보인다() throws Exception {
    // 1) 앱 — 소셜 로그인 → 약관 → 연락처 인증 → 임대인 온보딩(ACTIVE·LANDLORD)
    String appOnboardingToken = read(googleLogin("app-first-google"), "data", "accessToken");
    agreeTerms(appOnboardingToken);
    verifyAppPhone(appOnboardingToken, APP_FIRST_PHONE);
    String onboardingBody = landlordOnboarding(appOnboardingToken, APP_FIRST_PHONE);
    // 앱이 먼저인 이 시나리오에는 아직 웹 계정이 없다 — 병합 분기를 타지 않았다는 것을 여기서 못박아 두어야
    // 뒤의 웹 가입 linked=true가 "원래부터 true였던 값"이 아님이 성립한다.
    assertThat(readBoolean(onboardingBody, "data", "linked")).isFalse();
    long appUserId = readLong(onboardingBody, "data", "user", "id");

    // 2) 웹 — 가입용(번호 키) SMS 인증. 앱 트랙의 userId 키 챌린지와 별개 경로다(§1-1·§1-2).
    verifySignupPhone(APP_FIRST_PHONE);
    verifySignupEmail("app-first@work.com");

    // 3) 웹 회원가입 — 같은 번호라 연동된다. users 행이 늘지 않는 것이 "연결"의 정의다.
    long usersBefore = countUsers();
    String signupBody =
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signupJson("김임대", APP_FIRST_PHONE, "app-first@work.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.linked").value(true))
            .andExpect(jsonPath("$.data.onboardingRequired").value(false))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            // 표시 규칙 — 응답의 email은 폼 값이 아니라 users의 값(소셜 진본)이다. 폼 이메일은
            // local_accounts.email에만 남는다(스펙 §개요 표시 규칙 · D7).
            .andExpect(jsonPath("$.data.email").value("app-first-google@example.com"))
            // 이름도 같은 규칙이다. 폼은 "김임대"를 보냈고 users에는 소셜 표기가 들어 있다 —
            // 연동이 users를 한 칼럼도 건드리지 않는다는 것(ADR-0047 §6)을 끝까지 볼 수 있는 곳은
            // linked=true 경로뿐이라, 이 단언이 없으면 폼 값으로 덮어써도 전부 초록으로 통과한다.
            .andExpect(jsonPath("$.data.name").value("Kim Imdae"))
            // refresh는 본문에 없고 쿠키로만 내려간다(ADR-0048).
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(countUsers()).isEqualTo(usersBefore);

    String webAccessToken = read(signupBody, "data", "accessToken");
    assertThat(userIdOf(webAccessToken)).isEqualTo(appUserId);

    // 4) 웹 세션으로 매물 등록 — landlordId는 요청 본문이 아니라 토큰에서 나온다. 저장된 값이 앱 계정 id여야 한다.
    String registerBody = registerListing(webAccessToken, appUserId);
    String listingId = read(registerBody, "data", "listingId");
    String roomOfferId = read(registerBody, "data", "roomOffers", "0", "roomOfferId");
    assertThat(landlordIdOf(listingId)).isEqualTo(appUserId);

    // 5) 예약은 공개(PUBLISHED) 매물의 활성 방 상품에만 걸린다. 등록 직후 상태는 PENDING이고 승인은 관리자
    //    화면의 일이라 API가 없으므로, 저장소에서 상태만 공개로 바꿔 예약 경로를 연다(그 외 문서는 등록이 만든 그대로다).
    publishListing(listingId);

    // 6) 세입자가 그 매물에 예약을 신청한다.
    String tenantAccessToken = onboardTenantCompletely("app-first-tenant");
    String bookingBody =
        mockMvc
            .perform(
                post("/api/v1/listings/{listingId}/bookings", listingId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tenantAccessToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bookingJson(roomOfferId)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long bookingId = readLong(bookingBody, "data", "bookingId");

    // 7) 앱으로 다시 소셜 로그인한다(기존 ACTIVE → 정식 토큰). 이 토큰으로 보는 받은 신청 목록에
    //    웹에서 등록한 매물의 예약이 그대로 나와야 한다 — 연동이 성립했다는 최종 증거다.
    String appAccessToken = read(googleLogin("app-first-google"), "data", "accessToken");
    mockMvc
        .perform(get("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, bearer(appAccessToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].bookingId").value(bookingId))
        .andExpect(jsonPath("$.data.content[0].listingId").value(listingId))
        .andExpect(jsonPath("$.data.content[0].roomOfferId").value(roomOfferId));
  }

  /**
   * <b>웹 먼저 → 앱 온보딩에서 병합</b>(US-1-15). 웹에서 가입해 매물까지 등록한 임대인이 나중에 앱에 소셜 로그인하면 임시 {@code users} 행이 먼저
   * 만들어진다 — 소셜은 이름·이메일만 주고 번호를 주지 않아 로그인 시점엔 판정할 수 없기 때문이다. 그래서 번호를 처음 알게 되는 <b>임대인 온보딩 제출</b>에서
   * 병합한다.
   *
   * <p>응답은 {@code linked=true}로 병합을 <b>선언</b>하지만 그 플래그만 믿지 않는다 — 플래그와 실제 쓰기가 갈리는 회귀는 응답을 아무리 읽어도
   * 보이지 않기 때문이다(웹 가입 쪽에서 {@code users} 행 수를 세는 것과 같은 이유). 그래서 <b>네 곳을 더</b> 본다. ① {@code
   * social_accounts.user_id}가 웹 계정으로 옮겨졌는가 ② 임시 {@code users} 행이 하드 삭제됐는가(D13) ③ 발급된 토큰이 <b>임시 계정이
   * 아니라 웹 계정</b>의 것인가(방금 지운 행을 가리키는 토큰을 내주는 실수가 조용히 성립할 수 있는 자리다) ④ 병합 전에 등록한 매물의 {@code
   * landlordId}가 그대로인가(살아남는 쪽이 원래 소유자라 옮길 데이터가 없다는 전제 그 자체).
   */
  @Test
  @DisplayName("웹 먼저 가입한 임대인이 앱 온보딩을 마치면 소셜 매핑이 옮겨지고 임시 계정이 삭제되며 매물 소유권은 그대로다")
  void 웹먼저_앱온보딩이_임시계정을병합하고_매물소유권을유지한다() throws Exception {
    // 1) 웹 — 가입용 SMS 인증 → 회원가입(신규 생성이므로 linked=false)
    verifySignupPhone(WEB_FIRST_PHONE);
    verifySignupEmail("web-first@work.com");
    String signupBody =
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signupJson("박임대", WEB_FIRST_PHONE, "web-first@work.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.linked").value(false))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            // 신규 가입일 때만 폼 이메일이 users.email에도 기록된다(D7).
            .andExpect(jsonPath("$.data.email").value("web-first@work.com"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String webAccessToken = read(signupBody, "data", "accessToken");
    long webUserId = userIdOf(webAccessToken);

    // 2) 웹 세션으로 매물 등록 — 병합보다 먼저다. 병합이 소유권을 흔들지 않는다는 것을 뒤에서 확인한다.
    String listingId = read(registerListing(webAccessToken, webUserId), "data", "listingId");
    assertThat(landlordIdOf(listingId)).isEqualTo(webUserId);

    // 3) 앱 — 소셜 로그인. 이 시점에 임시 users 행이 만들어진다(PENDING). 응답에 id가 없으므로 매핑에서 읽는다.
    String appOnboardingToken = read(googleLogin("web-first-google"), "data", "accessToken");
    long temporaryUserId = socialAccountUserId("GOOGLE", "web-first-google");
    assertThat(temporaryUserId).isNotEqualTo(webUserId);

    // 4) 약관 → 같은 번호로 연락처 인증. 병합은 반드시 이 게이트 뒤다 — 앞에 두면 번호만 아는 사람이
    //    남의 웹 계정을 흡수할 수 있다(ADR-0047 §3).
    agreeTerms(appOnboardingToken);
    verifyAppPhone(appOnboardingToken, WEB_FIRST_PHONE);

    // 5) 임대인 온보딩 제출 → 병합. 응답의 user는 임시 계정이 아니라 웹 계정 기준이다(스펙 §5-2).
    String mergedBody = landlordOnboarding(appOnboardingToken, WEB_FIRST_PHONE);
    // 병합을 서버가 명시해서 알린다(US-1-15). 이 단정이 이 시나리오의 요점이다 — 아래 id 비교는 테스트가
    // 두 값을 다 알고 있어서 가능한 것이고, 실제 앱은 자기가 보낸 토큰을 파싱해야 같은 판정을 할 수 있다.
    assertThat(readBoolean(mergedBody, "data", "linked")).isTrue();
    assertThat(readLong(mergedBody, "data", "user", "id")).isEqualTo(webUserId);
    assertThat(read(mergedBody, "data", "user", "email")).isEqualTo("web-first@work.com");

    // ① 앱 로그인의 열쇠가 웹 계정으로 옮겨졌다.
    assertThat(socialAccountUserId("GOOGLE", "web-first-google")).isEqualTo(webUserId);
    // ② 임시 users 행은 하드 삭제됐다(익명화가 아니라 삭제 — D13).
    assertThat(countUsersById(temporaryUserId)).isZero();
    // ③ 발급된 토큰도 웹 계정 것이다. 임시 계정으로 발급했다면 방금 지운 행을 가리켜 조회가 깨진다.
    String mergedAccessToken = read(mergedBody, "data", "accessToken");
    assertThat(userIdOf(mergedAccessToken)).isEqualTo(webUserId);
    // ④ 병합 전에 등록한 매물의 소유자는 그대로다.
    assertThat(landlordIdOf(listingId)).isEqualTo(webUserId);

    // 6) 이후 앱 소셜 로그인은 항상 웹 계정으로 귀결된다 — 온보딩이 아니라 곧바로 정식 토큰이다.
    String reloginBody = googleLogin("web-first-google");
    assertThat(read(reloginBody, "data", "status")).isEqualTo("ACTIVE");
    assertThat(userIdOf(read(reloginBody, "data", "accessToken"))).isEqualTo(webUserId);
  }

  /**
   * <b>한 계정에 두 번 병합한 뒤 탈퇴</b>(US-1-15 경계 AC — 리뷰에서 깨졌던 회귀). 웹에서 가입한 임대인이 Google로 앱 로그인해 병합되고 나중에
   * Apple로도 로그인해 다시 병합하면, 살아남은 계정에 {@code social_accounts}가 <b>2행</b> 붙는다({@code user_id}에는 UNIQUE가
   * 없고 인덱스만 있다). 그 상태에서 탈퇴가 성공해야 한다.
   *
   * <p><b>왜 이 케이스가 따로 필요한가</b> — 탈퇴는 Apple 연동을 폐기하려고 그 회원의 소셜 매핑을 읽는데, 이것을 <b>단건 조회</b>로 하면 두 가지가
   * 동시에 깨진다: ① 순서에 따라 Apple 폐기가 조용히 누락되고 ② Spring Data의 단건 조회가 2행에서 예외를 던져 <b>탈퇴 트랜잭션 전체가
   * 롤백</b>된다(전역 핸들러의 마지막 그물까지 흘러 500이다). 즉 <b>병합을 마친 임대인이 영원히 탈퇴하지 못한다</b>. 병합이 두 번 일어나야만 드러나는 상태라
   * 단건 흐름 테스트로는 잡히지 않는다.
   */
  @Test
  @DisplayName("한 웹 계정에 Google·Apple을 차례로 병합해 소셜 매핑이 2행이어도 탈퇴가 성공한다")
  void 두번병합해_소셜매핑이2행이어도_탈퇴가성공한다() throws Exception {
    // 1) 웹 가입 — 병합이 수렴할 대상 계정
    verifySignupPhone(TWICE_MERGED_PHONE);
    verifySignupEmail("twice@work.com");
    String signupBody =
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signupJson("최임대", TWICE_MERGED_PHONE, "twice@work.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.linked").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long webUserId = userIdOf(read(signupBody, "data", "accessToken"));

    // 2) Google로 앱 로그인 → 병합(1회차)
    mergeIntoWebAccountWithGoogle("twice-google", TWICE_MERGED_PHONE, webUserId);

    // 3) Apple로도 앱 로그인 → 병합(2회차). (provider, providerUserId) UNIQUE는 값이 달라 위반되지 않는다.
    when(appleAuthClient.exchangeAuthorizationCode("twice-apple-code"))
        .thenReturn(new AppleAuthClient.AppleTokens("twice-apple", "twice-apple-rt"));
    String appleOnboardingToken = read(appleLogin("twice-apple-code"), "data", "accessToken");
    agreeTerms(appleOnboardingToken);
    verifyAppPhone(appleOnboardingToken, TWICE_MERGED_PHONE);
    String mergedBody = landlordOnboarding(appleOnboardingToken, TWICE_MERGED_PHONE);
    assertThat(readBoolean(mergedBody, "data", "linked")).isTrue();
    assertThat(readLong(mergedBody, "data", "user", "id")).isEqualTo(webUserId);

    // 한 계정에 소셜 매핑 2행 — 병합 이후의 정상 상태다.
    assertThat(countSocialAccountsByUserId(webUserId)).isEqualTo(2);

    // 4) 탈퇴 — 여기가 회귀 지점이다. 2행을 단건으로 읽으면 500으로 막혀 영원히 탈퇴하지 못한다.
    //    함께 내려오는 삭제 쿠키도 여기서 못박는다: 이 계정은 웹 가입으로 만들어져 브라우저에 refresh
    //    쿠키를 실제로 들고 있는 유일한 흐름이라, 잔여 쿠키가 남는지 아닌지가 여기서만 진짜로 검증된다.
    //    쿠키 Path가 /api/v1/auth라 이 요청(/users/me)에는 쿠키가 실리지 않는다 — 그런데도 삭제 쿠키가
    //    나와야 한다(조건부로 바꾸면 판정이 항상 거짓이라 조용히 사라진다. ADR-0048 §3).
    String accessToken = read(mergedBody, "data", "accessToken");
    mockMvc
        .perform(delete("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isNoContent())
        .andExpect(header().string(HttpHeaders.SET_COOKIE, startsWith("refreshToken=;")))
        .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
        .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")));

    // Apple 폐기는 매핑 삭제 전에, 저장된 토큰으로 호출된다(ADR-0031 #5). 2행을 순회했다는 증거이기도 하다.
    verify(appleAuthClient).revokeRefreshToken("twice-apple-rt");
    assertThat(countSocialAccountsByUserId(webUserId)).isZero();

    // 탈퇴는 로그인 수단을 하나도 남기지 않는다 — 웹 자격증명도 함께 지워진다.
    // 행 삭제를 직접 확인한다: 아래 401만으로는 부족하다. local_accounts가 남아 있어도 상태 게이트
    // (ACTIVE만 통과)가 똑같은 401 AUTH_INVALID_CREDENTIALS를 내므로, 삭제를 지워도 이 테스트는
    // 초록으로 통과해 버린다 — 두 원인을 구분하는 단언이 있어야 회귀가 잡힌다.
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM local_accounts WHERE email = ?",
                Long.class,
                "twice@work.com"))
        .isZero();

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"twice@work.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
  }

  // ---- 시나리오 조각 ----

  /** Google 소셜 로그인 → 약관 → 연락처 인증 → 임대인 온보딩까지 태워 대상 웹 계정으로 병합시킨다. */
  private void mergeIntoWebAccountWithGoogle(String subject, String phone, long targetUserId)
      throws Exception {
    String token = read(googleLogin(subject), "data", "accessToken");
    agreeTerms(token);
    verifyAppPhone(token, phone);
    String body = landlordOnboarding(token, phone);
    assertThat(readBoolean(body, "data", "linked")).isTrue();
    assertThat(readLong(body, "data", "user", "id")).isEqualTo(targetUserId);
  }

  /** 신규 Google 로그인 → 약관 → <b>세입자</b> 온보딩까지 마치고 정식 access 토큰을 돌려준다(예약을 넣을 상대 역). */
  private String onboardTenantCompletely(String subject) throws Exception {
    String token = read(googleLogin(subject), "data", "accessToken");
    agreeTerms(token);
    String body =
        mockMvc
            .perform(
                post("/api/v1/auth/onboarding")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "gender": "FEMALE",
                          "birthDate": "1995-03-11",
                          "country": "VN",
                          "visaType": "STUDENTS_TRAINEES"
                        }
                        """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return read(body, "data", "accessToken");
  }

  private String googleLogin(String subject) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/social-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"provider\":\"GOOGLE\",\"idToken\":\""
                        + subject
                        + "\",\"name\":\"Kim Imdae\"}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String appleLogin(String authorizationCode) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/social-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"provider\":\"APPLE\",\"authorizationCode\":\""
                        + authorizationCode
                        + "\",\"name\":\"Kim Imdae\"}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void agreeTerms(String onboardingToken) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/terms")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"termsOfServiceAgreed\":true,\"privacyPolicyAgreed\":true,\"marketingAgreed\":false}"))
        .andExpect(status().isOk());
  }

  /** 앱 트랙(userId 키) 연락처 인증 — 발송·확인(§4-1·§4-2). */
  private void verifyAppPhone(String onboardingToken, String phone) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/phone/verification-code")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phone + "\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/phone/verify")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"phoneNumber\":\""
                        + phone
                        + "\",\"code\":\""
                        + sentCodes.get(phone)
                        + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.verified").value(true));
  }

  /** 가입 트랙(이메일 키·비로그인) 이메일 인증 — 발송·확인(§1-11·§1-12). 가입 제출의 두 번째 게이트다(#285). */
  private void verifySignupEmail(String email) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/email/signup/verification-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/email/signup/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"" + email + "\",\"code\":\"" + sentEmailCodes.get(email) + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.verified").value(true));
  }

  /** 가입 트랙(번호 키·비로그인) 연락처 인증 — 발송·확인(§1-1·§1-2). 인증 헤더가 없는 것이 앱 트랙과의 차이다. */
  private void verifySignupPhone(String phone) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verification-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phone + "\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"phoneNumber\":\""
                        + phone
                        + "\",\"code\":\""
                        + sentCodes.get(phone)
                        + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.verified").value(true));
  }

  private String landlordOnboarding(String onboardingToken, String phone) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/landlord/onboarding")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"phoneNumber\":\"" + phone + "\",\"birthDate\":\"" + BIRTH_DATE + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.user.userType").value("LANDLORD"))
        .andExpect(jsonPath("$.data.user.status").value("ACTIVE"))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** 매물을 등록하고 응답 본문을 돌려준다. 사진 키는 소유권이 문자열로 박혀 있어 요청자 id로 만들어야 한다. */
  private String registerListing(String accessToken, long landlordId) throws Exception {
    return mockMvc
        .perform(
            post("/api/v2/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(landlordId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  // ---- 저장소 확인·조작 ----

  /** 저장된 매물의 소유자. 등록·상세 응답 어디에도 나오지 않는 값이라 문서에서 직접 읽는다. */
  private long landlordIdOf(String listingId) {
    Document listing =
        mongoTemplate
            .getCollection(LISTINGS_COLLECTION)
            .find(new Document("_id", new ObjectId(listingId)))
            .first();
    assertThat(listing).isNotNull();
    return listing.get("landlordId", Number.class).longValue();
  }

  /** 등록 직후 PENDING 매물을 공개로 바꾼다 — 승인은 관리자 화면의 일이라 API가 없다. */
  private void publishListing(String listingId) {
    mongoTemplate
        .getCollection(LISTINGS_COLLECTION)
        .updateOne(
            new Document("_id", new ObjectId(listingId)),
            new Document("$set", new Document("status", "PUBLISHED")));
  }

  private long countUsers() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
  }

  private long countUsersById(long userId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM users WHERE id = ?", Long.class, userId);
  }

  private long socialAccountUserId(String provider, String providerUserId) {
    return jdbcTemplate.queryForObject(
        "SELECT user_id FROM social_accounts WHERE provider = ? AND provider_user_id = ?",
        Long.class,
        provider,
        providerUserId);
  }

  private long countSocialAccountsByUserId(long userId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM social_accounts WHERE user_id = ?", Long.class, userId);
  }

  /** 토큰이 어느 계정의 것인지는 프로필 조회로 확인한다 — JWT를 직접 파싱하면 필터가 실제로 그렇게 해석하는지는 확인되지 않는다. */
  private long userIdOf(String accessToken) throws Exception {
    String body =
        mockMvc
            .perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return readLong(body, "data", "id");
  }

  // ---- 요청 본문 ----

  private static String signupJson(String name, String phone, String email) {
    return """
        {
          "name": "%s",
          "birthDate": "%s",
          "phoneNumber": "%s",
          "email": "%s",
          "password": "%s",
          "termsOfServiceAgreed": true,
          "privacyPolicyAgreed": true,
          "marketingAgreed": false
        }
        """
        .formatted(name, BIRTH_DATE, phone, email, PASSWORD);
  }

  private static String bookingJson(String roomOfferId) {
    return """
        {
          "roomOfferId": "%s",
          "moveInDate": "%s",
          "contractPeriod": 6
        }
        """
        .formatted(roomOfferId, LocalDate.now().plusMonths(1));
  }

  /** 스펙 예시와 같은 정상 등록 본문. 사진 키의 {@code uploads/{landlordId}/} 접두사가 곧 소유권 표시다(ADR-0041). */
  private static String registerJson(long landlordId) {
    return """
        {
          "title": "신촌 도보 5분 1인실 고시원",
          "type": "GOSHIWON",
          "contact": { "managerName": "김운영", "phone": "+82) 10-1234-5678" },
          "businessRegistrationNumber": "1234567890",
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
          "genderPolicy": "ANY",
          "languagesSupported": ["ENGLISH"],
          "ageRange": "20~35",
          "arcRequired": "NOT_REQUIRED",
          "facilities": {
            "heatingSystem": ["CENTRAL"],
            "kitchen": ["SHARED_REFRIGERATOR"],
            "laundry": ["WASHER"],
            "livingAmenities": ["WIFI"],
            "securityFeatures": ["CCTV"],
            "commonSpaces": ["SHARED_TOILET"],
            "providedSupplies": ["BEDDING"]
          },
          "nearbyFacilities": ["CONVENIENCE_STORE"],
          "nearestTransit": { "type": "SUBWAY", "name": "신촌역", "walkMinutes": 5 },
          "description": "지하철역에서 도보 5분 거리의 관리가 잘 된 고시원입니다.",
          "extraNotes": "객실 내 취사 금지. 오후 11시 이후 정숙.",
          "refundPolicy": "입주 7일 전까지 취소하면 전액 환불합니다.",
          "imageKeys": ["uploads/%1$d/3f9a1c2e-1d2b-4c3a-9f10-2b7c5d8e4a11.jpg"],
          "roomOffers": [
            {
              "name": "스탠다드 1인실",
              "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
              "pricing": { "monthlyRent": 380000, "deposit": 200000, "maintenanceFee": 20000 },
              "filterTags": ["ENGLISH_OK", "ADDRESS_REGISTRATION"],
              "roomImageKeys": [
                "uploads/%1$d/7b2e8841-2a3b-4c5d-8e9f-0a1b2c3d4e55.jpg",
                "uploads/%1$d/c14d05a6-6b7c-4d8e-9f01-2a3b4c5d6e66.jpg"
              ]
            }
          ],
          "preferredNationalities": ["JAPAN"],
          "contractDifficulties": ["LANGUAGE"]
        }
        """
        .formatted(landlordId);
  }

  // ---- 시드·JSON 유틸 ----

  /**
   * 운영이 수동 주입하는 정본 JSON을 드라이버 레벨로 그대로 심는다({@code ListingTestSeeds}와 같은 파일·같은 방식). 그 클래스는 {@code
   * listing} 테스트 패키지의 package-private 타입이라 여기서 호출할 수 없다.
   */
  private void seed(String collection, String resource) {
    List<Document> documents = new ArrayList<>();
    try (var input = new ClassPathResource(resource).getInputStream()) {
      String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      for (BsonValue value : BsonArray.parse(json)) {
        BsonDocument bson = value.asDocument();
        documents.add(Document.parse(bson.toJson()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("테스트 시드 리소스를 읽지 못했다: " + resource, e);
    }
    if (!documents.isEmpty()) {
      mongoTemplate
          .getCollection(collection)
          .insertMany(documents, new InsertManyOptions().ordered(true));
    }
  }

  private String read(String json, String... path) throws Exception {
    return node(json, path).asText();
  }

  private long readLong(String json, String... path) throws Exception {
    return node(json, path).asLong();
  }

  /**
   * boolean 필드를 읽는다. <b>필드가 실제로 boolean인지 먼저 단정하는 것이 핵심</b>이다 — Jackson의 {@code asBoolean()}은 없는
   * 노드({@code MissingNode})에 조용히 {@code false}를 주므로, 그대로 쓰면 응답에서 {@code linked}를 통째로 지워도 {@code
   * isFalse()} 단정이 초록으로 통과한다.
   */
  private boolean readBoolean(String json, String... path) throws Exception {
    JsonNode node = node(json, path);
    assertThat(node.isBoolean()).as("%s는 boolean 필드여야 한다", String.join(".", path)).isTrue();
    return node.booleanValue();
  }

  private JsonNode node(String json, String... path) throws Exception {
    JsonNode node = objectMapper.readTree(json);
    for (String key : path) {
      node =
          key.chars().allMatch(Character::isDigit)
              ? node.path(Integer.parseInt(key))
              : node.path(key);
    }
    return node;
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
