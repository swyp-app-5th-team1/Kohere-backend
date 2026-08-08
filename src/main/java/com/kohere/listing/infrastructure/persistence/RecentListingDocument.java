package com.kohere.listing.infrastructure.persistence;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * MongoDB {@code recentListings} 컬렉션 전용 저장 모델.
 *
 * <p>최근 본 기록은 사용자별 화면 상태에 가까운 데이터라 매물 본문과 분리한다. 매물 카드에 필요한 제목·가격·주소는 {@code listings} 컬렉션에서 조회 시점에
 * 가져오고, 이 문서는 "누가 어떤 매물을 언제 마지막으로 봤는지"만 저장한다.
 */
@Document(collection = RecentListingDocument.COLLECTION_NAME)
@Getter
@Builder
@AllArgsConstructor
class RecentListingDocument {

  static final String COLLECTION_NAME = "recentListings";

  @MongoId private final ObjectId id;

  /** 인증 토큰에서 꺼낸 사용자 식별자다. user 모듈과 FK를 맺지 않고 값으로만 참조한다. */
  private final Long userId;

  /** 최근 본 대상 매물의 Mongo ObjectId다. API에서는 24자리 hex 문자열로 주고받는다. */
  private final ObjectId listingId;

  /** 최근 본 목록의 기본 정렬 키다. 같은 매물을 다시 보면 이 값만 최신 시각으로 갱신한다. */
  private final Instant viewedAt;
}
