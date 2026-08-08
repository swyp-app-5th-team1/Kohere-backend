package com.kohere.listing.infrastructure.persistence;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * MongoDB {@code favorites} 컬렉션 전용 저장 모델.
 *
 * <p>매물 본문은 {@code listings} 컬렉션에 두고, 사용자별 찜 사실은 별도 컬렉션으로 분리한다. 한 매물을 여러 사용자가 찜할 수 있고, 한 사용자가 여러
 * 매물을 찜할 수 있으므로 {@code listings} 문서 안에 사용자 배열을 넣기보다 {@code favorites} 문서를 독립적으로 저장하는 편이 조회·삭제·유니크
 * 제약 관리에 단순하다.
 */
@Document(collection = FavoriteDocument.COLLECTION_NAME)
@Getter
@Builder
@AllArgsConstructor
class FavoriteDocument {

  static final String COLLECTION_NAME = "favorites";

  @MongoId private final ObjectId id;

  /** 인증 토큰에서 꺼낸 사용자 식별자다. user 모듈과 FK를 맺지 않고 값으로만 참조한다. */
  private final Long userId;

  /** 찜 대상 매물의 Mongo ObjectId다. API에서는 24자리 hex 문자열로 주고받는다. */
  private final ObjectId listingId;

  /** 찜 목록의 기본 정렬 키다. 프론트에는 최신 찜한 순으로 내려간다. */
  private final Instant favoritedAt;
}
