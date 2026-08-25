package com.kohere.listing.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 임대인 「내 매물」 목록의 카드 하나다.
 *
 * <p>세입자 목록 카드를 그대로 싣고 <b>반려 사유만</b> 더한다. 상태는 카드가 이미 갖고 있다.
 *
 * <p>관리자 심사 목록처럼 항목마다 상세 전체를 담지 않는 이유는 두 가지다 — 목록 화면이 쓰지 않는 값(사업자등록번호·설문·동의)을 실어 보낼 이유가 없고, 관리자 목록은
 * 매물마다 번역 컨텍스트를 새로 만들어 <b>카탈로그를 페이지 크기만큼 다시 읽는다</b>. 여기서는 컨텍스트를 루프 밖에서 한 번만 만든다.
 *
 * @param listing 세입자 목록 카드와 같은 구조. 표시 언어는 임대인 계정 언어를 따른다
 * @param rejectionReason 반려 사유. 반려된 매물에만 값이 있어 그 외에는 키가 빠진다
 */
public record LandlordListingSummaryResponse(
    ListingSummaryResponse listing,
    @JsonInclude(JsonInclude.Include.NON_NULL) String rejectionReason) {}
