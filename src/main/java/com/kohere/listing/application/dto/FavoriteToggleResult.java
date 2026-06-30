package com.kohere.listing.application.dto;

/**
 * 찜 등록 유스케이스의 내부 결과.
 *
 * <p>클라이언트에게 내려가는 응답 본문은 {@link FavoriteToggleResponse} 하나면 충분하지만, 컨트롤러는 신규 찜이면 {@code 201
 * Created}, 이미 찜한 상태면 {@code 200 OK}를 골라야 한다. 그래서 응용 계층은 API 본문과 함께 "이번 요청에서 실제로 favorites 문서가 새로
 * 생성됐는지"를 {@code created}로 알려준다.
 */
public record FavoriteToggleResult(boolean created, FavoriteToggleResponse response) {}
