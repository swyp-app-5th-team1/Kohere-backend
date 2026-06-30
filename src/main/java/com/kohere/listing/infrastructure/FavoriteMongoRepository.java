package com.kohere.listing.infrastructure;

import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data MongoDB favorite 접근 세부사항을 infrastructure 계층 안에 감춘다.
 *
 * <p>단건 존재 확인처럼 단순한 조회는 Spring Data 메서드 이름 쿼리를 쓰고, 공개 매물만 포함한 찜 목록처럼 {@code listings} 컬렉션과 함께 봐야 하는
 * 조회는 {@link FavoriteRepositoryImpl}에서 {@code MongoTemplate} aggregation으로 처리한다.
 */
interface FavoriteMongoRepository extends MongoRepository<FavoriteDocument, ObjectId> {

  Optional<FavoriteDocument> findByUserIdAndListingId(Long userId, ObjectId listingId);
}
