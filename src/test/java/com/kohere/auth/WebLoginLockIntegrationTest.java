package com.kohere.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.auth.application.AuthProperties;
import com.kohere.auth.domain.LocalAccount;
import com.kohere.auth.domain.LocalAccountRepository;
import com.kohere.auth.domain.VerificationSmsSender;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 임대인 웹 로그인 계정 잠금 종단 통합 테스트(US-1-12) — 상한만큼의 실패를 <b>실제 HTTP 요청</b>으로 쌓고 그 다음이 423인지 본다.
 *
 * <p><b>이 테스트만이 「로그인은 트랜잭션 단위가 아니다」를 지킬 수 있다.</b> 실패 카운터는 요청이 <b>예외로 끝나는 바로 그때</b> 커밋돼야 하는데, {@code
 * WebAuthService#login}에 {@code @Transactional}을 달면 정확히 그 반대가 보장된다 — {@code
 * InvalidCredentialsException}이 나가면서 방금 올린 카운터까지 함께 롤백돼 <b>상한이 영원히 오지 않는다</b>. 잠금 코드는 그대로 있는데 아무도
 * 잠기지 않는, 눈에 띄지 않는 실패다.
 *
 * <p>그리고 그 회귀는 <b>다른 어떤 테스트로도 잡히지 않는다</b>. 저장소를 목으로 바꾼 단위 테스트({@code WebAuthServiceTest})는 롤백이라는 현상
 * 자체가 없어 {@code save}가 불렸다는 것까지만 보고 통과하고, 문서 테스트는 상태 코드만 본다. 그래서 <b>클래스 레벨 {@code @Transactional} 한
 * 줄을 추가하는 지극히 자연스러운 리팩터링</b>이 나머지 테스트를 전부 초록으로 둔 채 계정 잠금을 통째로 무력화할 수 있다 — 이 파일이 그 한 줄을 막는다.
 *
 * <p>같은 이유로 <b>이 테스트에 {@code @Transactional}을 붙이면 안 된다</b>(테스트 트랜잭션이 요청들을 하나로 묶어 같은 착시를 만든다). 롤백으로
 * 정리하지 않으므로 연락처·이메일은 {@code WebLandlordAuthDocsTest}와 겹치지 않는 값을 쓴다 — 컨텍스트 설정이 같아 같은 컨테이너·같은 DB를
 * 공유하고, {@code users.phone_number}(V23)·{@code local_accounts.email}(V22)에 UNIQUE가 걸려 있다.
 *
 * <p>SMS 발송만 모킹해 인증번호를 캡처하고 Security·JPA·Redis·JWT는 실제로 구동한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WebLoginLockIntegrationTest {

  private static final String PHONE = "01055559001";
  private static final String EMAIL = "lock-int@work.example";
  private static final String PASSWORD = "Kohere1!";
  private static final String WRONG_PASSWORD = "Wrong99!";

  /** 로그인 시도 IP 한도를 다른 테스트와 나눠 쓰지 않도록 이 테스트 전용 IP를 쓴다. */
  private static final String CLIENT_IP = "198.51.100.20";

  @Autowired private WebApplicationContext context;
  @Autowired private AuthProperties authProperties;
  @Autowired private LocalAccountRepository localAccountRepository;
  @MockitoBean private VerificationSmsSender smsSender;
  private final Map<String, String> sentCodes = new ConcurrentHashMap<>();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    doAnswer(
            inv -> {
              sentCodes.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(smsSender)
        .send(any(), any());
  }

  @Test
  @DisplayName("상한만큼의 실패 응답이 실제로 커밋돼 그 다음 로그인이 423으로 막힌다")
  void failedLoginsPersistAndLockTheAccount() throws Exception {
    signup();

    int maxFailedAttempts = authProperties.getWeb().getLoginMaxFailedAttempts();
    for (int attempt = 1; attempt <= maxFailedAttempts; attempt++) {
      login(WRONG_PASSWORD)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"))
          // 응답에 실린 카운터는 저장된 값과 같아야 한다 — 상한에 닿은 응답이 곳 잠금 시점이므로
          // 클라이언트가 보는 순서가 어긋나면 안 된다.
          .andExpect(jsonPath("$.error.details.failedAttempts").value(attempt))
          .andExpect(jsonPath("$.error.details.maxFailedAttempts").value(maxFailedAttempts));
      // 매 요청이 예외로 끝나도 카운터가 남아 있어야 한다 — 여기서 0으로 돌아가면 롤백이 일어났다는 뜻이고,
      // 그 상태로는 아래 잠금이 영원히 성립하지 않는다.
      assertThat(loadAccount().getFailedLoginAttempts()).isEqualTo(attempt);
    }

    LocalAccount locked = loadAccount();
    assertThat(locked.getLockedAt()).isNotNull();
    assertThat(locked.isLocked()).isTrue();

    // 잠긴 뒤에는 비밀번호가 맞아도 423이다 — 잠금 판정이 자격증명 대조보다 먼저다.
    login(PASSWORD)
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_LOCKED"))
        // 잠긴 계정에는 details를 실지 않는다 — 카운터가 상한에 고정돼 정보량이 없다.
        .andExpect(jsonPath("$.error.details").doesNotExist());

    // 잠금은 실패 카운터를 더 올리지 않는다 — 올리면 lockedAt이 요청마다 갱신돼 "언제 잠겼는가"가 사라진다.
    LocalAccount afterLocked = loadAccount();
    assertThat(afterLocked.getFailedLoginAttempts()).isEqualTo(maxFailedAttempts);
    assertThat(afterLocked.getLockedAt()).isEqualTo(locked.getLockedAt());
  }

  // ---- helpers ----

  private LocalAccount loadAccount() {
    return localAccountRepository
        .findByEmail(EMAIL)
        .orElseThrow(() -> new AssertionError("웹 자격증명이 없다 — 가입이 커밋되지 않았다"));
  }

  private ResultActions login(String password) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/login")
            .header("X-Forwarded-For", CLIENT_IP)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}"));
  }

  /** 가입용 SMS 인증을 거쳐 웹 계정을 만든다 — 잠금을 볼 수 있는 계정이 있어야 시작할 수 있다. */
  private void signup() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verification-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + PHONE + "\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"phoneNumber\":\""
                        + PHONE
                        + "\",\"code\":\""
                        + sentCodes.get(PHONE)
                        + "\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "김임대",
                      "birthDate": "1990-01-01",
                      "phoneNumber": "%s",
                      "email": "%s",
                      "password": "%s",
                      "termsOfServiceAgreed": true,
                      "privacyPolicyAgreed": true,
                      "marketingAgreed": false
                    }
                    """
                        .formatted(PHONE, EMAIL, PASSWORD)))
        .andExpect(status().isOk());
  }
}
