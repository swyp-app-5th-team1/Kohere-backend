package com.kohere.listing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.listing.application.dto.ListingPlaceSearchResponse;
import com.kohere.listing.domain.PlaceSearchClient;
import com.kohere.listing.domain.PlaceSearchResult;
import com.kohere.listing.presentation.dto.ListingPlaceSearchRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ListingPlaceSearchService}의 입력 검증과 외부 검색 포트 조율을 Spring 컨텍스트 없이 검증한다.
 *
 * <p>HTTP 변환은 인프라 테스트가 담당하므로 이 테스트는 검색어 정규화, 잘못된 입력의 조기 거절, 도메인 결과의 응답 DTO 매핑에 집중한다.
 */
@ExtendWith(MockitoExtension.class)
class ListingPlaceSearchServiceTest {

  @Mock private PlaceSearchClient placeSearchClient;

  private ListingPlaceSearchService service;

  /** 각 테스트가 독립된 서비스 인스턴스와 Mockito 포트를 사용하도록 생성자 주입으로 조립한다. */
  @BeforeEach
  void setUp() {
    service = new ListingPlaceSearchService(placeSearchClient);
  }

  /** 검색어 앞뒤 공백은 네이버 호출 전에 제거하고, 장소 필드는 손실 없이 응답 DTO로 옮긴다. */
  @Test
  void search_유효한_검색어를_trim하고_장소_후보를_반환한다() {
    ListingPlaceSearchRequest request = request("  경희대  ");
    PlaceSearchResult result =
        new PlaceSearchResult(
            "<b>경희대학교</b> 서울캠퍼스",
            "서울특별시 동대문구 회기동 1-5",
            "서울특별시 동대문구 경희대로 26",
            37.5964494,
            127.0525009);
    when(placeSearchClient.search("경희대")).thenReturn(List.of(result));

    ListingPlaceSearchResponse response = service.search(request);

    assertThat(response.items()).hasSize(1);
    assertThat(response.items().getFirst().title()).isEqualTo("<b>경희대학교</b> 서울캠퍼스");
    assertThat(response.items().getFirst().lat()).isEqualTo(37.5964494);
    assertThat(response.items().getFirst().lng()).isEqualTo(127.0525009);
    verify(placeSearchClient).search("경희대");
  }

  /** 네이버가 정상적으로 빈 목록을 반환하면 실패로 바꾸지 않고 프론트가 빈 상태를 그릴 수 있게 유지한다. */
  @Test
  void search_검색_결과가_없으면_빈_items를_반환한다() {
    ListingPlaceSearchRequest request = request("없는장소");
    when(placeSearchClient.search("없는장소")).thenReturn(List.of());

    ListingPlaceSearchResponse response = service.search(request);

    assertThat(response.items()).isEmpty();
  }

  /** 누락된 keyword는 외부 호출량을 소모하기 전에 400 입력 예외로 거절한다. */
  @Test
  void search_keyword가_누락되면_외부_검색을_호출하지_않는다() {
    assertThatThrownBy(() -> service.search(new ListingPlaceSearchRequest()))
        .isInstanceOf(InvalidInputException.class);
    verifyNoInteractions(placeSearchClient);
  }

  /** 공백만 있는 keyword도 유효한 장소 검색어가 아니므로 외부 검색을 호출하지 않는다. */
  @Test
  void search_keyword가_공백이면_외부_검색을_호출하지_않는다() {
    assertThatThrownBy(() -> service.search(request("   ")))
        .isInstanceOf(InvalidInputException.class);
    verifyNoInteractions(placeSearchClient);
  }

  /** 프로젝트 검색 정책인 최대 50자를 넘는 keyword는 네이버 호출 전에 차단한다. */
  @Test
  void search_keyword가_50자를_초과하면_외부_검색을_호출하지_않는다() {
    assertThatThrownBy(() -> service.search(request("가".repeat(51))))
        .isInstanceOf(InvalidInputException.class);
    verifyNoInteractions(placeSearchClient);
  }

  /** 테스트 입력을 간결하게 만들되 실제 MVC 바인딩과 같은 setter 경로로 요청 DTO를 구성한다. */
  private static ListingPlaceSearchRequest request(String keyword) {
    ListingPlaceSearchRequest request = new ListingPlaceSearchRequest();
    request.setKeyword(keyword);
    return request;
  }
}
