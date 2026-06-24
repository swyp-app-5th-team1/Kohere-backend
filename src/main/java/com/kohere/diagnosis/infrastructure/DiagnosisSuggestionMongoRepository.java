package com.kohere.diagnosis.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

/** 추천 조정 제안 카탈로그 Spring Data MongoDB 리포지토리(infrastructure 내부). */
interface DiagnosisSuggestionMongoRepository
    extends MongoRepository<DiagnosisSuggestionDocument, String> {}
