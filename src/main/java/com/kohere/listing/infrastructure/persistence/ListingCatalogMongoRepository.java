package com.kohere.listing.infrastructure.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

/** {@code listingCatalog} 문서를 읽는 Spring Data MongoDB 저장소다. */
interface ListingCatalogMongoRepository extends MongoRepository<ListingCatalogDocument, String> {}
