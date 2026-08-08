package com.kohere.listing.infrastructure.external.naver;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 네이버 지역 검색 전용 HTTP 클라이언트의 네트워크 경계를 구성한다.
 *
 * <p>다른 외부 연동과 타임아웃이나 인증 정책이 섞이지 않도록 전용 {@link RestClient} 빈을 둔다. 짧은 connect/read 타임아웃으로 네이버 지연이
 * 애플리케이션 요청 스레드를 장시간 점유하지 않게 한다.
 */
@Configuration
public class NaverSearchClientConfig {

  /**
   * 네이버 검색 설정의 타임아웃을 적용한 전용 HTTP 클라이언트를 만든다.
   *
   * @param properties 환경별 네이버 검색 설정
   * @return 네이버 지역 검색 호출에만 사용하는 {@link RestClient}
   */
  @Bean
  @Qualifier("naverSearchRestClient")
  RestClient naverSearchRestClient(NaverSearchProperties properties) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) properties.getConnectTimeoutMillis());
    requestFactory.setReadTimeout((int) properties.getReadTimeoutMillis());
    return RestClient.builder()
        .baseUrl(properties.getBaseUrl())
        .requestFactory(requestFactory)
        .build();
  }
}
