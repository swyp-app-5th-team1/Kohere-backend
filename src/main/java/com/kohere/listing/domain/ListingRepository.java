package com.kohere.listing.domain;

import com.kohere.common.response.PageResponse;
import java.util.Optional;
import java.util.Set;

/**
 * 매물 영속 포트. 구현은 infrastructure 계층에 두어 의존성을 역전한다(docs/convention/code-style.md §3-3). 도메인은 영속 기술을
 * 모른다.
 *
 * <p>목록 조회는 {@link ListingSearchCondition}으로 지도 범위·가격·옵션 조건을 한 번에 전달한다.
 */
public interface ListingRepository {

  /** ObjectId 문자열로 매물 한 건을 조회한다. */
  Optional<Listing> findById(String listingId);

  /**
   * 심사용 조회. 상태 필터가 비어 있으면 <b>모든 상태</b>를 대상으로 한다(관리자 전용).
   *
   * <p>세입자용 조회 3종({@link #findPublished} · {@link #search} · {@link #searchForMap})은 구현에서 {@code
   * status = PUBLISHED}를 조건 앞머리에 고정한다. 그 자리를 파라미터로 열면 호출자가 상태를 넘기지 않았을 때 비공개 매물이 세입자에게 샐 수 있으므로,
   * 상태를 받는 경로를 <b>따로</b> 두어 "세입자 경로는 공개 고정, 관리자 경로만 상태를 받는다"를 타입으로 가른다.
   *
   * @param statuses 조회할 상태 집합. 비어 있으면 상태 조건을 생략한다
   * @param page 0부터 시작하는 페이지 번호
   * @param size 페이지 크기
   * @param sort 정렬 키. {@code null}이면 등록 최신순
   */
  PageResponse<Listing> findForAdmin(
      Set<Listing.ListingStatus> statuses, int page, int size, String sort);

  /**
   * 임대인 소유 매물을 상태와 무관하게 페이지로 조회한다.
   *
   * <p>세입자용 조회 3종과 달리 {@code landlordId}로 먼저 좁히므로 비공개 매물이 새지 않는다 — 소유자에게만 자기 매물을 보여주는 경로라 상태를 열어도
   * 안전하다. 관리자용 {@link #findForAdmin}과도 나눈다: 그쪽은 소유자 조건이 없다.
   *
   * <p>정렬은 <b>최근 수정순 고정</b>이다. 정렬 키를 파라미터로 열면 세입자 경로의 {@code LISTING_INVALID_SORT_PARAM} 계약과 어긋나는
   * 규칙을 새로 만들게 되고, 나중에 여는 것은 하위 호환을 깨지 않는다.
   *
   * @param landlordId 소유 임대인 계정 id
   * @param statuses 조회할 상태 집합. 비어 있으면 상태 조건을 생략한다
   */
  PageResponse<Listing> findByLandlord(
      long landlordId, Set<Listing.ListingStatus> statuses, int page, int size);

  /** 공개 중이고 활성 방 상품이 있는 매물만 페이지로 조회한다. */
  PageResponse<Listing> findPublished(int page, int size);

  /**
   * 지도 범위와 필터 조건을 적용해 목록 카드 후보를 조회한다.
   *
   * <p>목록 화면은 매물/건물 1개를 카드 1개로 보여준다. 다만 가격·보증금·관리비·계약기간·재고는 방 상품별로 달라질 수 있으므로, 반환값은 {@link
   * Listing}과 검색 조건을 통과한 방 상품 목록을 함께 담은 {@link ListingSearchResult}를 페이지 단위로 반환한다.
   */
  PageResponse<ListingSearchResult> search(ListingSearchCondition condition);

  /** 지도 SDK에 전달할 마커 후보를 조회한다. 전체 건수와 지정 상한 내 매물만 반환한다. */
  ListingMapSearchResult searchForMap(ListingSearchCondition condition, int limit);

  /**
   * 진단 결과 조건을 MongoDB 필터로 적용해 추천 매물 페이지를 조회한다.
   *
   * <p>대학은 diagnosis의 그룹 코드가 아니라 그룹에서 펼친 개별 대학 코드 집합으로 받는다. 빈 집합이면 대학 조건을 생략하고, 값이 있으면 Listing 루트의
   * {@code nearbyUniversityCodes}와 ANY 매칭한다. 월세 하한/상한은 같은 활성 roomOffer가 조건 태그와 함께 모두 만족해야 하므로 저장소
   * 구현에서 {@code roomOffers} 배열의 단일 원소 기준으로 검사한다.
   */
  PageResponse<Listing> recommend(
      String region,
      Integer monthlyRentMin,
      Integer monthlyRentMax,
      Set<ConditionTag> conditions,
      Set<String> includedUniversityCodes,
      Set<String> excludedUniversityCodes,
      String district,
      String arcStatus,
      int page,
      int size,
      String sort);

  /**
   * 저장 전에 식별자를 하나 발급한다.
   *
   * <p>보통은 저장할 때 저장소가 식별자를 붙이면 되지만, 사진 저장 키가 매물·방 식별자를 포함해서(ADR-0041 §2) 저장보다 먼저 알아야 한다. 식별자 형식은 저장
   * 기술이 정하므로 발급도 저장소가 맡는다.
   */
  String nextIdentity();

  /** 매물 도메인 객체를 저장하고 저장된 결과를 반환한다. */
  Listing save(Listing listing);

  /**
   * <b>읽은 시점의 상태를 조건으로 걸어</b> 저장한다. 조건이 어긋나면 아무것도 쓰지 않고 빈 값을 반환한다.
   *
   * <p>매물 문서에는 낙관적 락 필드가 없고 {@link #save}는 문서 전체를 교체하므로, 조건 없이 저장하면 나중에 쓴 쪽이 앞의 변경을 <b>소리 없이</b>
   * 지운다. 임대인 수정은 읽기와 저장 사이에 사진 확정 복사(네트워크)가 끼어 그 창이 수백 ms에 이르므로, 그 사이 관리자가 승인하면 임대인의 교체가 승인을 덮거나
   * 임대인의 수정이 통째로 사라진다.
   *
   * <p>낙관적 락(@Version) 대신 상태 CAS인 이유는 기존 문서에 버전 필드가 없어 로드 시 {@code null}이 되고, 그것이 신규 저장으로 취급돼 식별자
   * 중복으로 전면 실패하기 때문이다 — 도입하려면 백필이 선행돼야 한다.
   *
   * @param listing 저장할 매물
   * @param expected 읽은 시점의 상태. 저장 직전 문서의 상태가 이 값일 때만 교체한다
   * @return 교체된 매물. 조건이 어긋나 교체하지 않았으면 빈 값
   */
  Optional<Listing> saveIfStatus(Listing listing, Listing.ListingStatus expected);

  /** 매물 찜 수를 원자적으로 1 증가시키고 변경 후 값을 반환한다. */
  int increaseFavoriteCount(String listingId);

  /** 매물 찜 수를 원자적으로 1 감소시키되 0 미만으로 내리지 않고 변경 후 값을 반환한다. */
  int decreaseFavoriteCount(String listingId);
}
