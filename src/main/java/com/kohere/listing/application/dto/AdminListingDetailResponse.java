package com.kohere.listing.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.Nationality;
import java.util.Set;

/**
 * 관리자 심사용 매물 응답이다. <b>매물 문서에 저장된 모든 필드</b>를 담는다.
 *
 * <p>세입자 상세({@link ListingDetailResponse})를 그대로 싣고 그것이 감추는 값을 나란히 더하는 모양이다. 이렇게 조립하면 라벨·번역 같은 공통
 * 처리를 다시 만들지 않으면서 "세입자에게 보이는 전부 + 감춰진 전부"라는 계약이 타입으로 드러난다.
 *
 * <p><b>감추지 않는 근거</b>: 매물 문서에는 임대인 개인 연락처가 저장되지 않으므로({@code contact.phone}은 지점 대표 전화, ADR-0039
 * Amended) 마스킹 대상 PII가 이 응답에 없다. {@code businessRegistrationNumber}는 오히려 <b>관리자가 심사에서 진위를 수동 확인해야
 * 하는 값</b>이라 반드시 포함한다(ADR-0033 개정). 표시 여부는 관리자 화면이 정한다.
 *
 * @param listing 세입자 상세와 동일한 구조. 표시 언어는 한국어 고정이다
 * @param landlordId 매물 소유 임대인 계정 id
 * @param businessRegistrationNumber 사업자등록번호 원문. 심사에서 진위를 수동 확인한다
 * @param rejectionReason 반려 사유. {@code REJECTED}와, 고쳐서 재심사 중인 {@code PENDING}에 값이 있다. 승인되면 사라지므로 그
 *     외에는 키가 빠진다
 */
public record AdminListingDetailResponse(
    ListingDetailResponse listing,
    Long landlordId,
    String businessRegistrationNumber,
    Set<Nationality> preferredNationalities,
    Set<ContractDifficulty> contractDifficulties,
    @JsonInclude(JsonInclude.Include.NON_NULL) String serviceFeedback,
    @JsonInclude(JsonInclude.Include.NON_NULL) String rejectionReason) {

  /** 도메인 매물과 이미 만들어 둔 세입자 상세를 묶어 심사 응답을 만든다. */
  public static AdminListingDetailResponse of(Listing listing, ListingDetailResponse detail) {
    return new AdminListingDetailResponse(
        detail,
        listing.getLandlordId(),
        listing.getBusinessRegistrationNumber(),
        listing.getPreferredNationalities(),
        listing.getContractDifficulties(),
        listing.getServiceFeedback(),
        listing.getRejectionReason());
  }
}
