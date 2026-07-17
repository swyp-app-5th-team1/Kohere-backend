package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisFlowSession;
import com.kohere.diagnosis.domain.DiagnosisFlowSessionRepository;
import com.kohere.diagnosis.domain.DiagnosisStatus;
import com.kohere.diagnosis.infrastructure.DiagnosisFlowSessionDocument.DraftDocument;
import java.util.LinkedHashSet;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * v2 진행 세션 영속 어댑터. 도메인 포트 {@link DiagnosisFlowSessionRepository}를 MongoDB로 구현하고 도메인↔도큐먼트를 매핑한다(의존성
 * 역전). {@code _id}는 Mongo가 부여하는 ObjectId 문자열이며, 사용자당 1 세션은 {@code userId} UNIQUE 인덱스로 보장한다.
 */
@Repository
@RequiredArgsConstructor
public class DiagnosisFlowSessionRepositoryImpl implements DiagnosisFlowSessionRepository {

  private final DiagnosisFlowSessionMongoRepository mongoRepository;
  private final MongoTemplate mongoTemplate;

  @Override
  public Optional<DiagnosisFlowSession> findByUserId(long userId) {
    return mongoRepository.findByUserId(userId).map(DiagnosisFlowSessionRepositoryImpl::toDomain);
  }

  @Override
  public DiagnosisFlowSession save(DiagnosisFlowSession session) {
    return toDomain(mongoRepository.save(toDocument(session)));
  }

  @Override
  public DiagnosisFlowSession upsertByUserId(DiagnosisFlowSession session) {
    DiagnosisFlowSessionDocument document = toDocument(session);
    Update update =
        new Update()
            .set("userId", document.getUserId())
            .set("draft", document.getDraft())
            .set("pendingField", document.getPendingField());
    // userId UNIQUE 인덱스와 같은 조건으로 upsert해, 삭제 후 삽입 사이에 낀 동시 요청이 중복 키로 깨지지 않게 한다.
    // returnNew로 부여된 _id까지 한 번에 받는다(덮어쓰는 이전 세션은 그냥 버린다 — ADR-0036 결정 12).
    DiagnosisFlowSessionDocument saved =
        mongoTemplate.findAndModify(
            new Query(Criteria.where("userId").is(session.getUserId())),
            update,
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            DiagnosisFlowSessionDocument.class);
    return toDomain(saved);
  }

  @Override
  public void deleteByUserId(long userId) {
    mongoRepository.deleteByUserId(userId);
  }

  private static DiagnosisFlowSessionDocument toDocument(DiagnosisFlowSession s) {
    Diagnosis d = s.getDraft();
    DraftDocument draft =
        DraftDocument.builder()
            .region(d.getRegion())
            .purpose(d.getPurpose())
            .university(d.getUniversity())
            .district(d.getDistrict())
            .conditions(d.getConditions())
            .monthlyRentMin(d.getMonthlyRentMin())
            .monthlyRentMax(d.getMonthlyRentMax())
            .arcStatus(d.getArcStatus())
            .build();
    return DiagnosisFlowSessionDocument.builder()
        .id(s.getId())
        .userId(s.getUserId())
        .draft(draft)
        .pendingField(s.getPendingField())
        .build();
  }

  private static DiagnosisFlowSession toDomain(DiagnosisFlowSessionDocument e) {
    Diagnosis.DiagnosisBuilder draftBuilder =
        Diagnosis.builder().userId(e.getUserId()).status(DiagnosisStatus.IN_PROGRESS);
    DraftDocument dd = e.getDraft();
    if (dd != null) {
      draftBuilder
          .region(dd.getRegion())
          .purpose(dd.getPurpose())
          .university(dd.getUniversity())
          .district(dd.getDistrict())
          .conditions(dd.getConditions() == null ? new LinkedHashSet<>() : dd.getConditions())
          .monthlyRentMin(dd.getMonthlyRentMin())
          .monthlyRentMax(dd.getMonthlyRentMax())
          .arcStatus(dd.getArcStatus());
    } else {
      draftBuilder.conditions(new LinkedHashSet<>());
    }
    return DiagnosisFlowSession.builder()
        .id(e.getId())
        .userId(e.getUserId())
        .draft(draftBuilder.build())
        .pendingField(e.getPendingField())
        .build();
  }
}
