package com.kohere.listing.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.Nationality;
import java.util.List;
import java.util.Set;

/**
 * 임대인 「내 매물」 상세다. 수정 화면이 폼을 채우는 데 쓴다.
 *
 * <p>계약은 <b>"수정 요청에 실을 수 있는 전 필드 + 읽기 전용 표시값"</b>이다 — 편집 대상이 하나라도 빠지면 임대인이 그 값을 그대로 다시 제출할 수 없고, 전체
 * 교체 방식이라 <b>빠진 필드는 지워진다</b>.
 *
 * <p>관리자 심사 응답({@link AdminListingDetailResponse})과 나눈 이유는 계약이 다르기 때문이다. 이쪽은 사진 키와 비활성 방까지 필요하고,
 * 저쪽은 심사 이력으로 넓어질 자리다. 한 타입을 공유하면 관리자 전용 값이 임대인에게 새는 것을 리뷰로만 막게 된다.
 *
 * @param listing 세입자 상세와 같은 구조. 표시·미리보기용이며 <b>활성 방만</b> 담는다
 * @param status 심사 상태. 읽기 전용이라 수정 요청에는 칸이 없다
 * @param rejectionReason 반려 사유. 읽기 전용이며 반려된 매물에만 값이 있다
 * @param businessRegistrationNumber 사업자등록번호. 세입자에게는 감추지만 <b>수정 요청 필드</b>다
 * @param preferredNationalities 등록 폼 설문. 수정 요청 필드다
 * @param contractDifficulties 등록 폼 설문. 수정 요청 필드다
 * @param serviceFeedback 등록 폼 설문. 값이 없으면 키가 빠진다
 * @param consents 최초 동의 이력. 표시용 참고값이며 수정 폼은 동의를 새로 받는다
 * @param imageKeys 대표사진의 저장 키. 그대로 둘 사진을 되돌려 보낼 때 쓴다
 * @param rooms <b>비활성 방까지 포함한</b> 전 방. 되살리려면 응답에 보여야 한다
 */
public record LandlordListingDetailResponse(
    ListingDetailResponse listing,
    Listing.ListingStatus status,
    @JsonInclude(JsonInclude.Include.NON_NULL) String rejectionReason,
    String businessRegistrationNumber,
    Set<Nationality> preferredNationalities,
    Set<ContractDifficulty> contractDifficulties,
    @JsonInclude(JsonInclude.Include.NON_NULL) String serviceFeedback,
    @JsonInclude(JsonInclude.Include.NON_NULL) AdminListingDetailResponse.ConsentsResponse consents,
    List<String> imageKeys,
    List<LandlordRoomResponse> rooms) {

  /**
   * 수정 요청이 요구하는 모양의 방 하나다.
   *
   * <p>{@code listing.roomOffers}와 겹치는 값이 있지만 그쪽은 <b>활성 방만</b> 담고 라벨을 붙인 표시용이다. 이 배열은 <b>다시 제출할
   * 값</b> — 식별자·상태·원본 코드·사진 키 — 을 비활성 방까지 그대로 준다.
   *
   * @param filterTags 라벨 없는 원본 코드. 수정 요청에 그대로 싣는 값이다
   * @param roomImageKeys 방 사진의 저장 키
   */
  public record LandlordRoomResponse(
      String roomOfferId,
      Listing.RoomOfferStatus status,
      String name,
      Listing.Contract contract,
      Listing.Pricing pricing,
      Set<ConditionTag> filterTags,
      List<String> roomImageUrls,
      List<String> roomImageKeys) {}
}
