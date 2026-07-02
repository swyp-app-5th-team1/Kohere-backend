package com.kohere.lifetip.infrastructure;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** 생활 팁 주제 카탈로그 Spring Data MongoDB 리포지토리(infrastructure 내부). */
interface LifeTipTopicMongoRepository extends MongoRepository<LifeTipTopicDocument, String> {

  List<LifeTipTopicDocument> findAllByOrderByOrderAsc();
}
