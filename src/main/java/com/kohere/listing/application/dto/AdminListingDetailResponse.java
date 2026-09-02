package com.kohere.listing.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.Nationality;
import java.util.Set;

/**
 * 관리자 심사용 매물 응답이다. <b>매물 문서에 저장된 모든 필드 + 등록 임대인의 계정 이름</b>을 담는다.
 *
 * <p>세입자 상세({@link ListingDetailResponse})를 그대로 싣고 그것이 감추는 값을 나란히 더하는 모양이다. 이렇게 조립하면 라벨·번역 같은 공통
 * 처리를 다시 만들지 않으면서 "세입자에게 보이는 전부 + 감춰진 전부"라는 계약이 타입으로 드러난다.
 *
 * <p><b>{@code landlordName}만은 매물 문서에서 오는 값이 아니다.</b> 정본은 {@code users.name}이고 응용 서비스가 {@code user
 * :: api}로 조회해 넘겨 준다. 그래서 이 레코드는 {@link Listing} 하나로 자기를 완성하지 못하고 {@link #of}가 이름을 <b>따로 받는다</b> —
 * 조합의 책임이 어디 있는지를 시그니처로 드러내려는 것이다.
 *
 * <p><b>감추지 않는 근거</b>: 매물 문서에는 임대인 개인 연락처가 저장되지 않으므로({@code contact.phone}은 지점 대표 전화, ADR-0039
 * Amended) 마스킹 대상 PII가 이 응답에 없다. {@code businessRegistrationNumber}는 오히려 <b>관리자가 심사에서 진위를 수동 확인해야
 * 하는 값</b>이라 반드시 포함한다(ADR-0033 개정). {@code landlordName}은 <b>심사자가 등록자를 알아보는 데 필요한 최소값</b>이다 —
 * {@code landlordId} 숫자만으로는 화면에서 누구의 매물인지 분간할 수 없다. 이름을 실었다고 그 선이 넓어지지는 않는다: 임대인의 연락처·이메일은 여전히 싣지
 * 않는다. 표시 여부는 관리자 화면이 정한다.
 *
 * @param listing 세입자 상세와 동일한 구조. 표시 언어는 한국어 고정이다
 * @param landlordId 매물 소유 임대인 계정 id
 * @param landlordName 매물 소유 임대인 계정 이름({@code users.name}). 매물 문서에 없는 값이라 조회 시점에 조합한다. 등록 폼의 지점
 *     담당자({@code listing.contact.managerName})와 <b>다른 값</b>이다. 이름이 없거나 계정을 찾을 수 없으면 {@code null}이
 *     아니라 키가 빠진다
 * @param businessRegistrationNumber 사업자등록번호 원문. 심사에서 진위를 수동 확인한다
 * @param rejectionReason 반려 사유. {@code REJECTED}와, 고쳐서 재심사 중인 {@code PENDING}에 값이 있다. 승인되면 사라지므로 그
 *     외에는 키가 빠진다
 */
public record AdminListingDetailResponse(
    ListingDetailResponse listing,
    Long landlordId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String landlordName,
    String businessRegistrationNumber,
    Set<Nationality> preferredNationalities,
    Set<ContractDifficulty> contractDifficulties,
    @JsonInclude(JsonInclude.Include.NON_NULL) String serviceFeedback,
    @JsonInclude(JsonInclude.Include.NON_NULL) String rejectionReason) {

  /**
   * 도메인 매물과 이미 만들어 둔 세입자 상세, 그리고 따로 조회한 임대인 이름을 묶어 심사 응답을 만든다.
   *
   * @param landlordName 알 수 없으면 {@code null}. 호출자가 부재를 이미 {@code null}로 접어서 넘긴다 — 빈 문자열을 그대로 흘리면
   *     응답에 빈 이름이 실려 "이름이 없다"와 "이름이 빈칸이다"가 클라이언트에서 갈리지 않는다
   */
  public static AdminListingDetailResponse of(
      Listing listing, ListingDetailResponse detail, String landlordName) {
    return new AdminListingDetailResponse(
        detail,
        listing.getLandlordId(),
        landlordName,
        listing.getBusinessRegistrationNumber(),
        listing.getPreferredNationalities(),
        listing.getContractDifficulties(),
        listing.getServiceFeedback(),
        listing.getRejectionReason());
  }
}
