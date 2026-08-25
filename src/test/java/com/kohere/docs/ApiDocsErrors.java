package com.kohere.docs;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import java.util.List;
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.request.ParameterDescriptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 실패 응답 스니펫과 그 변형(#151).
 *
 * <p>{@code summary}·{@code description}은 <b>성공 스니펫과 같은 상수</b>를 넘겨야 한다 — {@code OpenApi3Generator}는
 * 같은 {@code (path, method)} 모델들의 summary/description 중 <b>첫 non-blank 하나</b>만 채택하고 순서는 파일 순회에 좌우된다.
 * 지금 {@code DELETE /bookings/{bookingId}}는 에러 스니펫 문구가 성공 오퍼레이션의 제목·설명을 차지하고 있다(1단계 실측).
 */
public final class ApiDocsErrors {

  private ApiDocsErrors() {}

  public static RestDocumentationResultHandler errorSnippet(
      String identifier, String tag, String summary, String description, String... errorCodes) {
    return document(
        identifier,
        resource(
            ResourceSnippetParameters.builder()
                .tag(tag)
                .summary(summary)
                .description(description)
                .responseFields(ApiDocsFields.errorFields(errorCodes))
                .build()));
  }

  /**
   * 응답 기술자를 직접 넘기는 변형(#270) — {@code error.details.*}처럼 그 오퍼레이션에만 있는 필드를 실을 때 쓴다.
   *
   * <p>{@link ApiDocsFields#errorFieldsWith}가 만든 목록을 넘기며, <b>같은 {@code (path, method, status)}의
   * 스니펫은 전부 같은 목록</b>을 써야 한다(dedup·last-wins).
   */
  public static RestDocumentationResultHandler errorSnippet(
      String identifier,
      String tag,
      String summary,
      String description,
      List<FieldDescriptor> responseFields) {
    return document(
        identifier,
        resource(
            ResourceSnippetParameters.builder()
                .tag(tag)
                .summary(summary)
                .description(description)
                .responseFields(responseFields)
                .build()));
  }

  /**
   * path 변수가 있는 엔드포인트용. 에러 스니펫에도 선언해야 파라미터 설명이 순서에 좌우되지 않는다 — 생성기는 <b>path</b> 파라미터만 그룹의 <b>첫
   * ResourceModel</b> 하나에서 가져오기 때문이다({@code extractPathParameters(firstModelForPathAndMethod)}).
   *
   * <p>반대로 query·header 파라미터는 같은 {@code (path, method)} 모델 전체를 {@code flatMap} + {@code
   * distinctBy(name)}로 합치므로 에러 스니펫이 다시 선언할 필요가 없다 — 성공 스니펫의 선언이 그대로 남는다.
   */
  public static RestDocumentationResultHandler errorSnippet(
      String identifier,
      String tag,
      String summary,
      String description,
      ParameterDescriptor[] pathParameters,
      String... errorCodes) {
    return errorSnippet(identifier, tag, summary, description, false, pathParameters, errorCodes);
  }

  /**
   * 종료 예정 오퍼레이션용. {@code deprecated=true}는 OpenAPI {@code deprecated} 플래그로 나가 Swagger UI가 경로에 취소선과
   * 배지를 붙인다.
   *
   * <p>성공 스니펫이 없는 오퍼레이션(항상 4xx)은 이 에러 스니펫이 유일한 모델이므로 여기서 플래그를 세워야 한다 — 오퍼레이션의 {@code deprecated}는
   * 스니펫 모델에서 그대로 온다.
   */
  public static RestDocumentationResultHandler errorSnippet(
      String identifier,
      String tag,
      String summary,
      String description,
      boolean deprecated,
      ParameterDescriptor[] pathParameters,
      String... errorCodes) {
    return document(
        identifier,
        resource(
            ResourceSnippetParameters.builder()
                .tag(tag)
                .summary(summary)
                .description(description)
                .deprecated(deprecated)
                .pathParameters(pathParameters)
                .responseFields(ApiDocsFields.errorFields(errorCodes))
                .build()));
  }

  /**
   * OpenAPI에서만 제외하고 REST Docs HTML({@code /docs})은 유지한다.
   *
   * <p>{@code com.epages} 래퍼 대신 스프링 순정 {@code document()}를 쓰면 {@code resource.json}이 생기지 않아 {@code
   * openapi3} 수집 대상({@code walkTopDown().filter { name == "resource.json" }})에서 빠진다. {@code .adoc}
   * 스니펫은 그대로 생성되므로 {@code index.adoc}이 include하는 스니펫을 내릴 때 쓴다. {@code privateResource(true)}는 이 저장소
   * 설정({@code separatePublicApi} 미사용)에서는 효과가 없다.
   */
  public static void legacyDocError(
      MockMvc mockMvc,
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
            org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document(
                identifier, responseFields(ApiDocsFields.errorFields(errorCodes))));
  }

  /**
   * 문서화 없이 계약만 지킨다(스니펫 완전 제거 대상). 회귀 방어는 유지하고 Swagger 노이즈만 없앤다.
   *
   * <p>8단계에서 오퍼레이션당 401은 {@code -token-expired} 1건, 400은 {@code -invalid-input} 1건만 남기는데, 제거 대상이 전부
   * 파일별 공용 {@code perform(...)} 헬퍼를 통과하므로 호출부를 인라인으로 풀지 않고 인자 교체 수준으로 끝내기 위한 변형이다.
   */
  public static void assertError(
      MockMvc mockMvc,
      MockHttpServletRequestBuilder request,
      ResultMatcher expectedStatus,
      String expectedCode)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(expectedStatus)
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value(expectedCode));
  }
}
