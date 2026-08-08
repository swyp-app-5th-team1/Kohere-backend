package com.kohere.listing.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.kohere.listing.domain.place.PlaceSearchResult;
import com.kohere.listing.domain.place.PlaceSearchUpstreamException;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link NaverPlaceSearchClient}의 HTTP 계약과 응답 변환을 실제 네트워크 없이 검증한다.
 *
 * <p>{@link MockRestServiceServer}로 요청 URL·고정 파라미터·인증 헤더를 확인하고, 네이버 원본 좌표 변환 및 외부 오류의 502 예외 매핑을
 * 고정한다. 테스트에는 운영 인증정보 대신 명시적인 더미 값만 사용한다.
 */
class NaverPlaceSearchClientTest {

  private static final String BASE_URL = "https://openapi.naver.com";
  private static final String CLIENT_ID = "test-client-id";
  private static final String CLIENT_SECRET = "test-client-secret";

  private MockRestServiceServer server;
  private NaverPlaceSearchClient client;

  /** 실제 어댑터와 같은 base URL을 쓰되 요청을 Mock 서버가 가로채도록 RestClient를 조립한다. */
  @BeforeEach
  void setUp() {
    NaverSearchProperties properties = configuredProperties();
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new NaverPlaceSearchClient(properties, builder.build());
  }

  /** 네이버 문서의 고정 파라미터와 인증 헤더를 보내고, 원본 좌표를 WGS84 십진수로 변환한다. */
  @Test
  void search_정상_응답을_장소_후보로_변환한다() {
    server
        .expect(requestTo(Matchers.containsString("/v1/search/local.json")))
        .andExpect(method(HttpMethod.GET))
        // MockRestRequestMatchers는 URI의 raw query를 비교하므로 UTF-8 percent-encoding 결과를 단정한다.
        .andExpect(queryParam("query", "%EA%B2%BD%ED%9D%AC%EB%8C%80%ED%95%99%EA%B5%90"))
        .andExpect(queryParam("display", "5"))
        .andExpect(queryParam("start", "1"))
        .andExpect(queryParam("sort", "random"))
        .andExpect(header("X-Naver-Client-Id", CLIENT_ID))
        .andExpect(header("X-Naver-Client-Secret", CLIENT_SECRET))
        .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

    List<PlaceSearchResult> results = client.search("경희대학교");

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().title()).isEqualTo("<b>경희대학교</b> 서울캠퍼스");
    assertThat(results.getFirst().address()).isEqualTo("서울특별시 동대문구 회기동 1-5");
    assertThat(results.getFirst().roadAddress()).isEqualTo("서울특별시 동대문구 경희대로 26");
    assertThat(results.getFirst().lat()).isEqualTo(37.5964494);
    assertThat(results.getFirst().lng()).isEqualTo(127.0525009);
    server.verify();
  }

  /** 네이버가 정상 200과 빈 items를 반환하면 장애가 아닌 빈 검색 결과로 유지한다. */
  @Test
  void search_정상_빈_응답이면_빈_목록을_반환한다() {
    server
        .expect(requestTo(Matchers.containsString("/v1/search/local.json")))
        .andRespond(
            withSuccess(
                "{\"total\":0,\"start\":1,\"display\":0,\"items\":[]}",
                MediaType.APPLICATION_JSON));

    assertThat(client.search("없는장소")).isEmpty();
    server.verify();
  }

  /** 방어적으로 네이버가 5개를 초과해 내려주더라도 공개 계약의 최대 5개를 넘기지 않는다. */
  @Test
  void search_응답이_5개를_초과하면_앞의_5개만_반환한다() {
    server
        .expect(requestTo(Matchers.containsString("/v1/search/local.json")))
        .andRespond(withSuccess(sixItemsBody(), MediaType.APPLICATION_JSON));

    assertThat(client.search("대학교")).hasSize(5);
    server.verify();
  }

  /** 네이버 4xx는 사용자가 해결할 수 있는 빈 결과가 아니므로 502 외부 연동 오류로 변환한다. */
  @Test
  void search_네이버_4xx이면_상류_예외를_던진다() {
    server
        .expect(requestTo(Matchers.containsString("/v1/search/local.json")))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> client.search("경희대")).isInstanceOf(PlaceSearchUpstreamException.class);
    server.verify();
  }

  /** 네이버 5xx도 재시도 가능한 외부 장애로 분류해 공통 502 응답 경로로 전달한다. */
  @Test
  void search_네이버_5xx이면_상류_예외를_던진다() {
    server
        .expect(requestTo(Matchers.containsString("/v1/search/local.json")))
        .andRespond(withServerError());

    assertThatThrownBy(() -> client.search("경희대")).isInstanceOf(PlaceSearchUpstreamException.class);
    server.verify();
  }

  /** 본문에 items가 없으면 정상 빈 배열과 구분해 네이버 응답 계약 위반으로 처리한다. */
  @Test
  void search_items가_누락되면_상류_예외를_던진다() {
    server
        .expect(requestTo(Matchers.containsString("/v1/search/local.json")))
        .andRespond(withSuccess("{\"total\":1}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.search("경희대")).isInstanceOf(PlaceSearchUpstreamException.class);
    server.verify();
  }

  /** 숫자로 해석할 수 없는 mapx/mapy는 잘못된 장소 좌표를 노출하지 않고 상류 계약 위반으로 처리한다. */
  @Test
  void search_좌표가_잘못되면_상류_예외를_던진다() {
    server
        .expect(requestTo(Matchers.containsString("/v1/search/local.json")))
        .andRespond(
            withSuccess(
                "{\"items\":[{\"title\":\"경희대학교\",\"address\":\"\","
                    + "\"roadAddress\":\"\",\"mapx\":\"not-a-number\",\"mapy\":\"375964494\"}]}",
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.search("경희대")).isInstanceOf(PlaceSearchUpstreamException.class);
    server.verify();
  }

  /** 로컬 인증정보가 비어 있으면 무인증 외부 요청을 보내지 않고 즉시 상류 설정 오류로 처리한다. */
  @Test
  void search_인증정보가_없으면_HTTP를_호출하지_않고_상류_예외를_던진다() {
    NaverSearchProperties properties = new NaverSearchProperties();
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer isolatedServer = MockRestServiceServer.bindTo(builder).build();
    NaverPlaceSearchClient unconfiguredClient =
        new NaverPlaceSearchClient(properties, builder.build());

    assertThatThrownBy(() -> unconfiguredClient.search("경희대"))
        .isInstanceOf(PlaceSearchUpstreamException.class);
    isolatedServer.verify();
  }

  /** 모든 테스트가 동일한 더미 인증정보와 base URL을 사용하도록 설정 객체를 만든다. */
  private static NaverSearchProperties configuredProperties() {
    NaverSearchProperties properties = new NaverSearchProperties();
    properties.setBaseUrl(BASE_URL);
    properties.setClientId(CLIENT_ID);
    properties.setClientSecret(CLIENT_SECRET);
    return properties;
  }

  /** 사용자가 포스트맨으로 확인한 경희대학교 응답의 필요한 필드를 재현한다. */
  private static String successBody() {
    return """
        {
          "lastBuildDate": "Sat, 11 Jul 2026 21:50:55 +0900",
          "total": 1,
          "start": 1,
          "display": 1,
          "items": [
            {
              "title": "<b>경희대학교</b> 서울캠퍼스",
              "link": "http://www.khu.ac.kr/",
              "category": "교육,학문>대학교",
              "address": "서울특별시 동대문구 회기동 1-5",
              "roadAddress": "서울특별시 동대문구 경희대로 26",
              "mapx": "1270525009",
              "mapy": "375964494"
            }
          ]
        }
        """;
  }

  /** 제공자 이상 상황에서 최대 5개 방어 제한이 유지되는지 확인할 6건 응답을 만든다. */
  private static String sixItemsBody() {
    String item =
        "{\"title\":\"대학교\",\"address\":\"주소\",\"roadAddress\":\"도로명\","
            + "\"mapx\":\"1270525009\",\"mapy\":\"375964494\"}";
    return "{\"items\":[" + String.join(",", List.of(item, item, item, item, item, item)) + "]}";
  }
}
