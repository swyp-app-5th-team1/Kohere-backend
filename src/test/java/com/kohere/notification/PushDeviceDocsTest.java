package com.kohere.notification;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ApiDocsErrors.errorSnippet;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.PushDeviceDocsFields.DELETE_400;
import static com.kohere.docs.PushDeviceDocsFields.DELETE_401;
import static com.kohere.docs.PushDeviceDocsFields.DELETE_403;
import static com.kohere.docs.PushDeviceDocsFields.DELETE_DESCRIPTION;
import static com.kohere.docs.PushDeviceDocsFields.DELETE_SUMMARY;
import static com.kohere.docs.PushDeviceDocsFields.REGISTER_400;
import static com.kohere.docs.PushDeviceDocsFields.REGISTER_401;
import static com.kohere.docs.PushDeviceDocsFields.REGISTER_403;
import static com.kohere.docs.PushDeviceDocsFields.REGISTER_DESCRIPTION;
import static com.kohere.docs.PushDeviceDocsFields.REGISTER_SUMMARY;
import static com.kohere.docs.PushDeviceDocsFields.installationPathParameters;
import static com.kohere.docs.PushDeviceDocsFields.registerRequestFields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.common.security.JwtTokenService;
import com.kohere.docs.ApiDocsTags;
import com.kohere.notification.domain.PushDevice;
import com.kohere.notification.domain.PushDeviceRepository;
import com.kohere.notification.domain.PushPlatform;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** FCM 기기 등록·삭제 API의 Swagger 스니펫과 실제 MySQL 저장 흐름을 함께 검증한다. */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class PushDeviceDocsTest {

  private static final long USER_ID = 7L;
  private static final String PATH = "/api/v1/users/me/push-devices/{installationId}";

  @Autowired private WebApplicationContext context;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private PushDeviceRepository pushDeviceRepository;

  private MockMvc mockMvc;

  /** 실제 보안 필터와 REST Docs를 적용한 MockMvc를 준비한다. */
  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
  }

  /** 등록 요청이 204를 반환하고 installation과 로그인 사용자를 실제 DB에 연결하는지 문서화·검증한다. */
  @Test
  void registerPushDevice() throws Exception {
    UUID installationId = UUID.fromString("e7714046-1634-4dc1-a97e-8c1f91a72483");

    mockMvc
        .perform(
            put(PATH, installationId)
                .header(HttpHeaders.AUTHORIZATION, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fcmToken\":\"docs-current-token\",\"platform\":\"IOS\"}"))
        .andExpect(status().isNoContent())
        .andDo(
            document(
                "push-device-register",
                resourceDetails()
                    .tag(ApiDocsTags.USERS)
                    .summary(REGISTER_SUMMARY)
                    .description(REGISTER_DESCRIPTION),
                pathParameters(installationPathParameters()),
                requestFields(registerRequestFields())));

    PushDevice saved =
        pushDeviceRepository.findByInstallationIdForUpdate(installationId).orElseThrow();
    assertThat(saved.getUserId()).isEqualTo(USER_ID);
    assertThat(saved.getFcmToken()).isEqualTo("docs-current-token");
    assertThat(saved.getPlatform()).isEqualTo(PushPlatform.IOS);
  }

  /** 기존 installation이 다른 행에 남아 있던 같은 토큰을 가져올 때 오래된 행을 제거하고 중복 없이 갱신하는지 검증한다. */
  @Test
  void registerMovesTokenFromStaleInstallation() throws Exception {
    UUID currentInstallationId = UUID.fromString("95165248-37c5-4409-8ff7-f053058434a8");
    UUID staleInstallationId = UUID.fromString("1f3ae319-06c1-4d3c-899d-099d8d2a4041");
    pushDeviceRepository.save(
        PushDevice.register(
            USER_ID,
            currentInstallationId,
            "old-current-token",
            PushPlatform.IOS,
            Instant.parse("2020-08-29T06:30:00Z")));
    pushDeviceRepository.save(
        PushDevice.register(
            99L,
            staleInstallationId,
            "token-to-move",
            PushPlatform.IOS,
            Instant.parse("2020-08-29T06:31:00Z")));

    mockMvc
        .perform(
            put(PATH, currentInstallationId)
                .header(HttpHeaders.AUTHORIZATION, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fcmToken\":\"token-to-move\",\"platform\":\"IOS\"}"))
        .andExpect(status().isNoContent());

    PushDevice current =
        pushDeviceRepository.findByInstallationIdForUpdate(currentInstallationId).orElseThrow();
    assertThat(current.getUserId()).isEqualTo(USER_ID);
    assertThat(current.getFcmToken()).isEqualTo("token-to-move");
    assertThat(pushDeviceRepository.findByInstallationIdForUpdate(staleInstallationId)).isEmpty();
  }

  /** 삭제 요청이 204를 반환하고 현재 사용자의 installation을 실제 DB에서 제거하는지 문서화·검증한다. */
  @Test
  void deletePushDevice() throws Exception {
    UUID installationId = UUID.fromString("ea678733-ed14-4137-a19d-94967407ea91");
    pushDeviceRepository.save(
        PushDevice.register(
            USER_ID,
            installationId,
            "docs-delete-token",
            PushPlatform.IOS,
            Instant.parse("2020-08-29T06:30:00Z")));

    mockMvc
        .perform(delete(PATH, installationId).header(HttpHeaders.AUTHORIZATION, token()))
        .andExpect(status().isNoContent())
        .andDo(
            document(
                "push-device-delete",
                resourceDetails()
                    .tag(ApiDocsTags.USERS)
                    .summary(DELETE_SUMMARY)
                    .description(DELETE_DESCRIPTION),
                pathParameters(installationPathParameters())));

    assertThat(pushDeviceRepository.findByInstallationIdForUpdate(installationId)).isEmpty();
  }

  /** 등록 API의 입력 검증, 비인증과 온보딩 권한 오류를 같은 오퍼레이션의 Swagger 응답으로 문서화한다. */
  @Test
  void registerErrorSnippets() throws Exception {
    UUID installationId = UUID.fromString("76dbd1fb-1a86-443c-95ce-467fdcd180e2");

    mockMvc
        .perform(
            put(PATH, installationId)
                .header(HttpHeaders.AUTHORIZATION, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fcmToken\":\"\",\"platform\":\"IOS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        .andDo(
            errorSnippet(
                "push-device-register-invalid-input",
                ApiDocsTags.USERS,
                REGISTER_SUMMARY,
                REGISTER_DESCRIPTION,
                installationPathParameters(),
                REGISTER_400));

    mockMvc
        .perform(
            put(PATH, "not-a-uuid")
                .header(HttpHeaders.AUTHORIZATION, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fcmToken\":\"valid-token\",\"platform\":\"IOS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
        .andDo(
            errorSnippet(
                "push-device-register-malformed-installation-id",
                ApiDocsTags.USERS,
                REGISTER_SUMMARY,
                REGISTER_DESCRIPTION,
                installationPathParameters(),
                REGISTER_400));

    mockMvc
        .perform(
            put(PATH, installationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fcmToken\":\"valid-token\",\"platform\":\"IOS\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andDo(
            errorSnippet(
                "push-device-register-unauthenticated",
                ApiDocsTags.USERS,
                REGISTER_SUMMARY,
                REGISTER_DESCRIPTION,
                installationPathParameters(),
                REGISTER_401));

    mockMvc
        .perform(
            put(PATH, installationId)
                .header(HttpHeaders.AUTHORIZATION, onboardingToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fcmToken\":\"valid-token\",\"platform\":\"IOS\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"))
        .andDo(
            errorSnippet(
                "push-device-register-onboarding-required",
                ApiDocsTags.USERS,
                REGISTER_SUMMARY,
                REGISTER_DESCRIPTION,
                installationPathParameters(),
                REGISTER_403));
  }

  /** 삭제 API의 잘못된 UUID, 비인증과 온보딩 권한 오류를 Swagger 응답으로 문서화한다. */
  @Test
  void deleteErrorSnippets() throws Exception {
    UUID installationId = UUID.fromString("0262cd66-6e96-43f8-a47d-46c65c630c1e");

    mockMvc
        .perform(delete(PATH, "not-a-uuid").header(HttpHeaders.AUTHORIZATION, token()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
        .andDo(
            errorSnippet(
                "push-device-delete-malformed-installation-id",
                ApiDocsTags.USERS,
                DELETE_SUMMARY,
                DELETE_DESCRIPTION,
                installationPathParameters(),
                DELETE_400));

    mockMvc
        .perform(delete(PATH, installationId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andDo(
            errorSnippet(
                "push-device-delete-unauthenticated",
                ApiDocsTags.USERS,
                DELETE_SUMMARY,
                DELETE_DESCRIPTION,
                installationPathParameters(),
                DELETE_401));

    mockMvc
        .perform(delete(PATH, installationId).header(HttpHeaders.AUTHORIZATION, onboardingToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"))
        .andDo(
            errorSnippet(
                "push-device-delete-onboarding-required",
                ApiDocsTags.USERS,
                DELETE_SUMMARY,
                DELETE_DESCRIPTION,
                installationPathParameters(),
                DELETE_403));
  }

  /** 문서 테스트 사용자의 정식 access 토큰을 Bearer 헤더 값으로 만든다. */
  private String token() {
    return bearer(jwtTokenService.issueAccessToken(USER_ID));
  }

  /** 문서 테스트 사용자의 온보딩 임시 토큰을 Bearer 헤더 값으로 만든다. */
  private String onboardingToken() {
    return bearer(jwtTokenService.issueOnboardingToken(USER_ID));
  }
}
