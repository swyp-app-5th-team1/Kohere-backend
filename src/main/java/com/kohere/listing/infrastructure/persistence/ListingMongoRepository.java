package com.kohere.listing.infrastructure.persistence;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data MongoDB 접근 세부사항을 infrastructure 계층 안에 감춘다. */
interface ListingMongoRepository extends MongoRepository<ListingDocument, ObjectId> {}
