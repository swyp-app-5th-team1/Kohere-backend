package com.kohere.listing.presentation.dto;

import com.kohere.listing.domain.ArcRequirement;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.Nationality;
import com.kohere.listing.domain.NearbyFacility;
import com.kohere.listing.domain.SupportedLanguage;
import java.util.Set;

/**
 * 매물 등록과 수정이 <b>똑같이 받는 필드</b>의 계약이다.
 *
 * <p>임대인이 수정하는 것은 등록 때 보낸 속성 그대로다 — 수정에서 새로 편집 가능해지는 값도, 편집 대상에서 빠지는 값도 없다. 그래서 두 요청 DTO는 거의 같은
 * 모양이고, 조립 코드가 두 벌로 갈라지면 한쪽만 고쳐지는 사고가 난다. 이 인터페이스는 그 공통 부분을 타입으로 묶어 조립을 한 곳에서 하게 한다.
 *
 * <p>{@code roomOffers}와 사진 키는 여기 없다. 수정 요청의 방에는 {@code roomOfferId}·{@code status}가 더 있고 사진 키는 임시
 * 키와 확정 키가 섞여 오므로, 두 경로가 실제로 다르게 다뤄야 하는 유일한 부분이다.
 *
 * <p>{@code consents}는 <b>있다</b>. 동의는 수정할 때도 다시 받으며 게이트도 그대로다. 다만 저장 값({@code version}·{@code
 * agreedAt})은 등록 시점 것을 승계하고 덮어쓰지 않는다 — 그 판단은 조립이 아니라 각 서비스가 한다.
 */
public interface ListingWriteRequest {

  String title();

  ListingType type();

  ListingRegisterRequest.ContactRequest contact();

  String businessRegistrationNumber();

  String blogUrl();

  ListingRegisterRequest.AddressRequest address();

  ListingRegisterRequest.BuildingRequest building();

  Listing.GenderPolicy genderPolicy();

  Set<SupportedLanguage> languagesSupported();

  String ageRange();

  ArcRequirement arcRequired();

  ListingRegisterRequest.FacilitiesRequest facilities();

  ListingRegisterRequest.NearestTransitRequest nearestTransit();

  Set<NearbyFacility> nearbyFacilities();

  String description();

  String extraNotes();

  String refundPolicy();

  Set<Nationality> preferredNationalities();

  Set<ContractDifficulty> contractDifficulties();

  String serviceFeedback();

  ListingRegisterRequest.ConsentsRequest consents();
}
