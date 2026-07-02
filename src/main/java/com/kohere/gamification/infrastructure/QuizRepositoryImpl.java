package com.kohere.gamification.infrastructure;

import com.kohere.gamification.domain.ChoiceKey;
import com.kohere.gamification.domain.Quiz;
import com.kohere.gamification.domain.QuizChoice;
import com.kohere.gamification.domain.QuizRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/**
 * 퀴즈 카탈로그 영속 어댑터. 도메인 포트 {@link QuizRepository}를 MongoDB로 구현하고 도큐먼트↔도메인 값을 매핑한다(ADR-0035). 랜덤 조회는
 * 활성 문서에 대한 {@code $sample} 애그리게이션으로 1건을 뽑는다.
 */
@Repository
@RequiredArgsConstructor
public class QuizRepositoryImpl implements QuizRepository {

  private final QuizMongoRepository mongoRepository;
  private final MongoTemplate mongoTemplate;

  @Override
  public Optional<Quiz> findRandomActive() {
    Aggregation aggregation =
        Aggregation.newAggregation(
            Aggregation.match(Criteria.where("active").is(true)), Aggregation.sample(1));
    QuizDocument document =
        mongoTemplate
            .aggregate(aggregation, QuizDocument.class, QuizDocument.class)
            .getUniqueMappedResult();
    return Optional.ofNullable(document).map(QuizRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Quiz> findById(Long id) {
    return mongoRepository.findById(id).map(QuizRepositoryImpl::toDomain);
  }

  private static Quiz toDomain(QuizDocument d) {
    List<QuizChoice> choices =
        d.getChoices() == null
            ? List.of()
            : d.getChoices().stream()
                .map(c -> new QuizChoice(ChoiceKey.valueOf(c.getKey()), c.getText()))
                .toList();
    return new Quiz(
        d.getId(),
        d.getQuestion(),
        choices,
        ChoiceKey.valueOf(d.getCorrectChoice()),
        d.getExplanation());
  }
}
