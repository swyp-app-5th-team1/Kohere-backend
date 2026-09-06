package com.kohere.notification;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ApiDocsErrors.errorSnippet;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static com.kohere.docs.NotificationPreferenceDocsFields.GET_401;
import static com.kohere.docs.NotificationPreferenceDocsFields.GET_403;
import static com.kohere.docs.NotificationPreferenceDocsFields.GET_DESCRIPTION;
import static com.kohere.docs.NotificationPreferenceDocsFields.GET_SUMMARY;
import static com.kohere.docs.NotificationPreferenceDocsFields.PATCH_400;
import static com.kohere.docs.NotificationPreferenceDocsFields.PATCH_401;
import static com.kohere.docs.NotificationPreferenceDocsFields.PATCH_403;
import static com.kohere.docs.NotificationPreferenceDocsFields.PATCH_DESCRIPTION;
import static com.kohere.docs.NotificationPreferenceDocsFields.PATCH_SUMMARY;
import static com.kohere.docs.NotificationPreferenceDocsFields.preferenceResponseFields;
import static com.kohere.docs.NotificationPreferenceDocsFields.updateRequestFields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.docs.ApiDocsTags;
import com.kohere.notification.application.NotificationPreferenceService;
import com.kohere.notification.domain.NotificationPreferenceRepository;
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

/** 사용자별 채팅 푸시 설정 API의 Swagger 계약과 실제 MySQL 저장 흐름을 함께 검증한다. */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class NotificationPreferenceDocsTest {

  private static final long USER_ID = 7L;
  private static final String PATH = "/api/v1/users/me/notification-preferences";

  @Autowired private WebApplicationContext context;
  @Autowired private JwtProperties jwtProperties;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private NotificationPreferenceService preferenceService;
  @Autowired private NotificationPreferenceRepository preferenceRepository;

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

  /** 저장된 행이 없는 사용자에게 true를 반환하면서 조회만으로 행을 만들지 않는지 문서화·검증한다. */
  @Test
  void getDefaultsToEnabledWithoutInsert() throws Exception {
    mockMvc
        .perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.chatPushEnabled").value(true))
        .andDo(
            document(
                "notification-preference-get",
                resourceDetails()
                    .tag(ApiDocsTags.USERS)
                    .summary(GET_SUMMARY)
                    .description(GET_DESCRIPTION),
                responseFields(preferenceResponseFields())));

    assertThat(preferenceRepository.findByUserId(USER_ID)).isEmpty();
  }

  /** 명시적으로 수신을 거부한 사용자의 false 응답을 Swagger Examples에서 확인할 수 있게 문서화한다. */
  @Test
  void getReturnsStoredDisabledValue() throws Exception {
    preferenceService.updateChatPushEnabled(USER_ID, false);

    mockMvc
        .perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.chatPushEnabled").value(false))
        .andDo(
            document(
                "notification-preference-get-disabled",
                resourceDetails()
                    .tag(ApiDocsTags.USERS)
                    .summary(GET_SUMMARY)
                    .description(GET_DESCRIPTION),
                responseFields(preferenceResponseFields())));
  }

  /** false 변경 요청을 실제 DB에 upsert하고 변경값을 공통 응답 봉투로 반환하는지 문서화·검증한다. */
  @Test
  void patchStoresAndReturnsExplicitValue() throws Exception {
    mockMvc
        .perform(
            patch(PATH)
                .header(HttpHeaders.AUTHORIZATION, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatPushEnabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.chatPushEnabled").value(false))
        .andDo(
            document(
                "notification-preference-patch",
                resourceDetails()
                    .tag(ApiDocsTags.USERS)
                    .summary(PATCH_SUMMARY)
                    .description(PATCH_DESCRIPTION),
                requestFields(updateRequestFields()),
                responseFields(preferenceResponseFields())));

    assertThat(preferenceRepository.findByUserId(USER_ID).orElseThrow().chatPushEnabled())
        .isFalse();
  }

  /** false에서 true로 다시 켜는 요청과 최종 true 응답을 Swagger Examples에 함께 문서화한다. */
  @Test
  void patchEnablesChatPushAgain() throws Exception {
    preferenceService.updateChatPushEnabled(USER_ID, false);

    mockMvc
        .perform(
            patch(PATH)
                .header(HttpHeaders.AUTHORIZATION, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatPushEnabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.chatPushEnabled").value(true))
        .andDo(
            document(
                "notification-preference-patch-enable",
                resourceDetails()
                    .tag(ApiDocsTags.USERS)
                    .summary(PATCH_SUMMARY)
                    .description(PATCH_DESCRIPTION),
                requestFields(updateRequestFields()),
                responseFields(preferenceResponseFields())));

    assertThat(preferenceRepository.findByUserId(USER_ID).orElseThrow().chatPushEnabled()).isTrue();
  }

  /** 조회 API의 비인증과 온보딩 권한 오류를 같은 오퍼레이션의 Swagger 응답으로 문서화한다. */
  @Test
  void getErrorSnippets() throws Exception {
    mockMvc
        .perform(get(PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andDo(
            errorSnippet(
                "notification-preference-get-unauthenticated",
                ApiDocsTags.USERS,
                GET_SUMMARY,
                GET_DESCRIPTION,
                GET_401));

    mockMvc
        .perform(get(PATH).header(HttpHeaders.AUTHORIZATION, onboardingToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"))
        .andDo(
            errorSnippet(
                "notification-preference-get-onboarding-required",
                ApiDocsTags.USERS,
                GET_SUMMARY,
                GET_DESCRIPTION,
                GET_403));

    mockMvc
        .perform(get(PATH).header(HttpHeaders.AUTHORIZATION, expiredToken()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"))
        .andDo(
            errorSnippet(
                "notification-preference-get-token-expired",
                ApiDocsTags.USERS,
                GET_SUMMARY,
                GET_DESCRIPTION,
                GET_401));
  }

  /** 변경 API의 필수값 누락, 비인증과 온보딩 권한 오류를 Swagger 응답으로 문서화한다. */
  @Test
  void patchErrorSnippets() throws Exception {
    mockMvc
        .perform(
            patch(PATH)
                .header(HttpHeaders.AUTHORIZATION, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatPushEnabled\":null}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        .andDo(
            errorSnippet(
                "notification-preference-patch-invalid-input",
                ApiDocsTags.USERS,
                PATCH_SUMMARY,
                PATCH_DESCRIPTION,
                PATCH_400));

    mockMvc
        .perform(
            patch(PATH)
                .header(HttpHeaders.AUTHORIZATION, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        .andDo(
            errorSnippet(
                "notification-preference-patch-missing-field",
                ApiDocsTags.USERS,
                PATCH_SUMMARY,
                PATCH_DESCRIPTION,
                PATCH_400));

    mockMvc
        .perform(
            patch(PATH)
                .header(HttpHeaders.AUTHORIZATION, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatPushEnabled\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
        .andDo(
            errorSnippet(
                "notification-preference-patch-malformed-request",
                ApiDocsTags.USERS,
                PATCH_SUMMARY,
                PATCH_DESCRIPTION,
                PATCH_400));

    mockMvc
        .perform(
            patch(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatPushEnabled\":false}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andDo(
            errorSnippet(
                "notification-preference-patch-unauthenticated",
                ApiDocsTags.USERS,
                PATCH_SUMMARY,
                PATCH_DESCRIPTION,
                PATCH_401));

    mockMvc
        .perform(
            patch(PATH)
                .header(HttpHeaders.AUTHORIZATION, onboardingToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatPushEnabled\":false}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"))
        .andDo(
            errorSnippet(
                "notification-preference-patch-onboarding-required",
                ApiDocsTags.USERS,
                PATCH_SUMMARY,
                PATCH_DESCRIPTION,
                PATCH_403));

    mockMvc
        .perform(
            patch(PATH)
                .header(HttpHeaders.AUTHORIZATION, expiredToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatPushEnabled\":false}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"))
        .andDo(
            errorSnippet(
                "notification-preference-patch-token-expired",
                ApiDocsTags.USERS,
                PATCH_SUMMARY,
                PATCH_DESCRIPTION,
                PATCH_401));
  }

  /** 문서 테스트 사용자의 정식 access 토큰을 Bearer 헤더 값으로 만든다. */
  private String token() {
    return bearer(jwtTokenService.issueAccessToken(USER_ID));
  }

  /** 문서 테스트 사용자의 온보딩 임시 토큰을 Bearer 헤더 값으로 만든다. */
  private String onboardingToken() {
    return bearer(jwtTokenService.issueOnboardingToken(USER_ID));
  }

  /** Swagger에서 access token 만료와 인증 누락을 서로 다른 401 예시로 보여준다. */
  private String expiredToken() {
    return bearer(expiredAccessToken(jwtProperties));
  }
}
