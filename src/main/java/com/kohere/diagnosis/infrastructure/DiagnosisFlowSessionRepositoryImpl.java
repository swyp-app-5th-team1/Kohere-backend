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
 * 역전). {@code _id}는 Mongo가 부여하는 ObjectId 문자열이며, "사용자당 1 세션"·"게스트 키당 1 세션"은 신원별 partial UNIQUE 인덱스가
 * 보장한다({@link DiagnosisFlowSessionIndexInitializer}).
 *
 * <p><b>비어 있는 신원 필드는 문서에 <i>존재하지 않아야</i> 한다</b>(#181) — partial 인덱스의 필터가 {@code $exists}라, 예컨대 게스트
 * 문서에 {@code userId: null}이 실제로 기록되면 회원용 인덱스에 포함되어 두 번째 게스트가 중복 키로 실패한다. 쓰기 경로 둘이 각각 이를 보장한다:
 * {@link #save}는 Spring Data의 기본 매핑 정책({@code Field.Write.NON_NULL} — null 프로퍼티를 기록하지 않는다)에 기대고,
 * {@link #upsertByUserId}는 {@code $unset}으로 명시적으로 지운다.
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
  public Optional<DiagnosisFlowSession> findByGuestSessionId(String guestSessionId) {
    return mongoRepository
        .findByGuestSessionId(guestSessionId)
        .map(DiagnosisFlowSessionRepositoryImpl::toDomain);
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
            .set("pendingField", document.getPendingField())
            // 회원 세션에는 게스트 키가 없어야 한다 — 남겨 두면 게스트용 partial UNIQUE($exists)에 끼어든다.
            .unset("guestSessionId");
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

  @Override
  public void deleteByGuestSessionId(String guestSessionId) {
    mongoRepository.deleteByGuestSessionId(guestSessionId);
  }

  private static DiagnosisFlowSessionDocument toDocument(DiagnosisFlowSession s) {
    Diagnosis d = s.getDraft();
    DraftDocument draft =
        DraftDocument.builder()
            .region(d.getRegion())
            .purpose(d.getPurpose())
            .university(d.getUniversity())
            .district(d.getDistrict())
            .conditions(DiagnosisConditionCodes.toCodes(d.getConditions()))
            .monthlyRentMin(d.getMonthlyRentMin())
            .monthlyRentMax(d.getMonthlyRentMax())
            .arcStatus(d.getArcStatus())
            .build();
    return DiagnosisFlowSessionDocument.builder()
        .id(s.getId())
        .userId(s.getUserId())
        .guestSessionId(s.getGuestSessionId())
        .draft(draft)
        .pendingField(s.getPendingField())
        .build();
  }

  private static DiagnosisFlowSession toDomain(DiagnosisFlowSessionDocument e) {
    // 초안의 신원은 세션의 신원에서 그대로 이어진다(둘 중 하나만 채워져 있다) — 완료 시 이 초안이 정본 진단이 되므로,
    // 여기서 신원을 옮겨 두지 않으면 확정된 게스트 진단의 소유자가 사라져 본인도 추천을 못 본다.
    Diagnosis.DiagnosisBuilder draftBuilder =
        Diagnosis.builder()
            .userId(e.getUserId())
            .guestSessionId(e.getGuestSessionId())
            .status(DiagnosisStatus.IN_PROGRESS);
    DraftDocument dd = e.getDraft();
    if (dd != null) {
      draftBuilder
          .region(dd.getRegion())
          .purpose(dd.getPurpose())
          .university(dd.getUniversity())
          .district(dd.getDistrict())
          .conditions(DiagnosisConditionCodes.toDomain(dd.getConditions()))
          .monthlyRentMin(dd.getMonthlyRentMin())
          .monthlyRentMax(dd.getMonthlyRentMax())
          .arcStatus(dd.getArcStatus());
    } else {
      draftBuilder.conditions(new LinkedHashSet<>());
    }
    return DiagnosisFlowSession.builder()
        .id(e.getId())
        .userId(e.getUserId())
        .guestSessionId(e.getGuestSessionId())
        .draft(draftBuilder.build())
        .pendingField(e.getPendingField())
        .build();
  }
}
