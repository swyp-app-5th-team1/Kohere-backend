package com.kohere.gamification.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

/** 학습 퀴즈 카탈로그 Spring Data MongoDB 리포지토리(infrastructure 내부). */
interface QuizMongoRepository extends MongoRepository<QuizDocument, Long> {}
