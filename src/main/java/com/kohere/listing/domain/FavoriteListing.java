package com.kohere.listing.domain;

/**
 * 찜 목록 한 행을 만들기 위한 도메인 조회 결과.
 *
 * <p>{@link Favorite}에는 사용자·매물 id와 찜한 시각만 있고, 화면에 필요한 매물 제목·가격·주소는 {@link Listing}에 있다. 저장소가
 * MongoDB에서 두 컬렉션을 조회한 뒤 응용 계층이 DTO로 변환할 수 있도록 둘을 한 묶음으로 전달한다.
 */
public record FavoriteListing(Favorite favorite, Listing listing) {}
