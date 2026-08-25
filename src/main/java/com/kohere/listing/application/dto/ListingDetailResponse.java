package com.kohere.listing.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kohere.listing.domain.Listing;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 매물 상세 화면 응답이다.
 *
 * <p>프론트는 {@link CodeLabelResponse#label()}을 화면에 표시하고 {@link CodeLabelResponse#code()}를 필터 요청이나 내부
 * 조건 비교에 사용한다. 매물명·주소·역명·방 이름·설명은 사용자 언어에 맞는 문자열 하나만 내려가므로 프론트에서 ko/en을 다시 선택할 필요가 없다.
 *
 * @param status 게시 상태는 일반 사용자 UI의 번역 대상이 아닌 서버 내부 상태이므로 기존 enum 값으로 유지한다.
 * @param location 매물 좌표. 등록이 주소 검색으로 받은 값을 채우고 저장 계약이 필수로 요구하므로(ADR-0042) 등록·조회 어느 응답에도 항상 실린다. 좌표
 *     없이 저장된 옛 문서에 한해 {@code null}이 아니라 <b>키 자체가 빠진다</b>({@code JsonInclude.Include#NON_NULL}).
 * @param conditions 상세 상단 조건 배지. 각 항목의 label을 표시하고 code는 필터 요청에 사용한다.
 */
public record ListingDetailResponse(
    String listingId,
    String title,
    CodeLabelResponse type,
    Listing.ListingStatus status,
    CodeLabelResponse rentalType,
    String refundPolicy,
    CodeLabelResponse genderPolicy,
    CodeLabelResponse arcRequired,
    int ageMin,
    int ageMax,
    List<CodeLabelResponse> languagesSupported,
    ContactResponse contact,
    @JsonInclude(JsonInclude.Include.NON_NULL) String blogUrl,
    @JsonInclude(JsonInclude.Include.NON_NULL) GeoPoint location,
    AddressResponse address,
    NearestTransitResponse nearestTransit,
    List<CodeLabelResponse> nearbyFacilities,
    Set<String> nearbyUniversityCodes,
    BuildingResponse building,
    FacilitiesResponse facilities,
    List<CodeLabelResponse> conditions,
    List<RoomOfferResponse> roomOffers,
    String description,
    String extraNotes,
    List<String> imageUrls,
    boolean favorited,
    int favoriteCount,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * 세입자가 매물 문의에 쓰는 담당자 연락처다. {@code phone}은 지점 대표 전화라 임대인 개인 연락처와 별개 값이고, 그래서 마스킹하지 않는다.
   *
   * <p>임대인 개인 번호는 애초에 매물 문서에 복사되지 않으므로 이 응답으로 새어 나갈 수 없다 — 그 통로였던 문자문의 번호({@code sms})를 저장 계약에서
   * 제거했다(ADR-0039 Amended · ADR-0034 §6).
   */
  public record ContactResponse(String managerName, String phone) {}

  /** 프론트 지도 컴포넌트에서 바로 쓰는 위도·경도 값이다. */
  public record GeoPoint(double lat, double lng) {}

  /** 검색용 행정 코드는 유지하고, 화면 주소만 사용자 언어로 선택한 응답이다. */
  public record AddressResponse(
      CodeLabelResponse city, CodeLabelResponse district, String fullAddress, String detail) {}

  /** 가까운 교통수단의 code/label과 사용자 언어의 역명·주변 안내를 담는다. */
  public record NearestTransitResponse(CodeLabelResponse type, String name, int walkMinutes) {}

  /** 건물 종류는 code/label로, 숫자와 boolean은 언어와 무관한 원래 값으로 내린다. */
  public record BuildingResponse(
      CodeLabelResponse type,
      int usedFloorMin,
      int usedFloorMax,
      int totalFloors,
      boolean parkingAvailable,
      boolean elevatorAvailable) {}

  /**
   * 시설 그룹별 공통 코드를 모두 code/label 형태로 내려주는 상세 응답이다.
   *
   * <p>해당 시설이 하나도 없는 그룹은 <b>{@code NONE} 하나만</b> 담긴다 — 빈 배열이 아니다. 「없음」도 카탈로그가 라벨을 주는 정상 코드라 화면은 다른
   * 값과 똑같이 칩을 그리면 된다.
   */
  public record FacilitiesResponse(
      List<CodeLabelResponse> heatingSystem,
      List<CodeLabelResponse> kitchen,
      List<CodeLabelResponse> laundry,
      List<CodeLabelResponse> livingAmenities,
      List<CodeLabelResponse> securityFeatures,
      List<CodeLabelResponse> commonSpaces,
      List<CodeLabelResponse> providedSupplies) {}

  /**
   * 동일 가격·조건을 공유하는 실제 방 묶음 하나다.
   *
   * <p>프론트는 name을 그대로 표시하고 filterTags의 label을 배지에 표시한다. 필터 요청에는 같은 항목의 code를 사용한다.
   */
  public record RoomOfferResponse(
      String roomOfferId,
      String name,
      Listing.RoomOfferStatus status,
      Listing.Contract contract,
      Listing.Pricing pricing,
      List<CodeLabelResponse> filterTags,
      List<String> roomImageUrls) {}
}
