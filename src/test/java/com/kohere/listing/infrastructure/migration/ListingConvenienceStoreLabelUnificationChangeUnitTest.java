package com.kohere.listing.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link ListingConvenienceStoreLabelUnificationChangeUnit}의 편의점 UI 문구 통일 규칙을 검증한다. */
class ListingConvenienceStoreLabelUnificationChangeUnitTest {

  /** CU와 GS25가 함께 있어도 시설 종류인 Convenience Store는 한 번만 표시해야 한다. */
  @Test
  void unify_CU와_GS25를_하나의_편의점문구로_통일한다() {
    String unified =
        ListingConvenienceStoreLabelUnificationChangeUnit.unify("GS25, CU, Cafe, Pharmacy");

    assertThat(unified).isEqualTo("Convenience Store, Cafe, Pharmacy");
  }

  /** 이마트24의 과거 한국어·영문 표기도 같은 공통 문구로 정규화한다. */
  @Test
  void unify_이마트24의_과거표기도_편의점문구로_통일한다() {
    assertThat(
            ListingConvenienceStoreLabelUnificationChangeUnit.unify(
                "이마트24, Laundry Service, Emart24, Hospital"))
        .isEqualTo("Convenience Store, Laundry Service, Hospital");
  }

  /** 이미 공통 문구가 있어도 뒤의 다른 편의점 브랜드를 제거하고 나머지 항목 순서는 유지한다. */
  @Test
  void unify_이미_통일된_문구와_브랜드가_섞여있으면_중복만_제거한다() {
    String unified =
        ListingConvenienceStoreLabelUnificationChangeUnit.unify(
            "Convenience Store, Laundry Service, CU, GS25, Cafe");

    assertThat(unified).isEqualTo("Convenience Store, Laundry Service, Cafe");
  }

  /** 편의점이 없는 주변시설은 공백 정리 외에 문구와 순서를 변경하지 않는다. */
  @Test
  void unify_편의점이_없는_주변시설은_그대로_유지한다() {
    String unified =
        ListingConvenienceStoreLabelUnificationChangeUnit.unify("Laundry Service, Cafe, Hospital");

    assertThat(unified).isEqualTo("Laundry Service, Cafe, Hospital");
  }

  /** 이미 보정된 값을 다시 처리해도 결과가 달라지지 않아야 한다. */
  @Test
  void unify_재실행해도_동일한_멱등변환이다() {
    String first =
        ListingConvenienceStoreLabelUnificationChangeUnit.unify("GS25, CU, Cafe, Pharmacy");
    String second = ListingConvenienceStoreLabelUnificationChangeUnit.unify(first);

    assertThat(second).isEqualTo(first);
  }
}
