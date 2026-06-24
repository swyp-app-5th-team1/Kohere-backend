package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisRepository;
import com.kohere.diagnosis.domain.DiagnosisStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * 진단 영속 어댑터. 도메인 포트 {@link DiagnosisRepository}를 MongoDB로 구현하고 도메인↔도큐먼트를 매핑한다(의존성 역전,
 * docs/convention/code-style.md §3-3). 신규 저장 시 {@link SequenceGenerator}로 Long id를 부여한다.
 */
@Repository
@RequiredArgsConstructor
public class DiagnosisRepositoryImpl implements DiagnosisRepository {

  private static final String SEQ_KEY = "diagnoses";

  private final DiagnosisMongoRepository mongoRepository;
  private final SequenceGenerator sequenceGenerator;

  @Override
  public Diagnosis save(Diagnosis diagnosis) {
    DiagnosisDocument document = toDocument(diagnosis);
    if (document.getId() == null) {
      document.setId(sequenceGenerator.nextId(SEQ_KEY));
    }
    return toDomain(mongoRepository.save(document));
  }

  @Override
  public Optional<Diagnosis> findById(Long id) {
    return mongoRepository.findById(id).map(DiagnosisRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Diagnosis> findInProgressByUserId(Long userId) {
    return mongoRepository
        .findFirstByUserIdAndStatus(userId, DiagnosisStatus.IN_PROGRESS)
        .map(DiagnosisRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Diagnosis> findLatestCompletedByUserId(Long userId) {
    return mongoRepository
        .findFirstByUserIdAndStatusOrderBySubmittedAtDesc(userId, DiagnosisStatus.COMPLETED)
        .map(DiagnosisRepositoryImpl::toDomain);
  }

  @Override
  public List<Diagnosis> findCompletedByUserId(Long userId, int page, int size, boolean ascending) {
    Sort.Direction direction = ascending ? Sort.Direction.ASC : Sort.Direction.DESC;
    PageRequest pageable = PageRequest.of(page, size, Sort.by(direction, "submittedAt"));
    return mongoRepository
        .findByUserIdAndStatus(userId, DiagnosisStatus.COMPLETED, pageable)
        .getContent()
        .stream()
        .map(DiagnosisRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public long countCompletedByUserId(Long userId) {
    return mongoRepository.countByUserIdAndStatus(userId, DiagnosisStatus.COMPLETED);
  }

  private static DiagnosisDocument toDocument(Diagnosis d) {
    return DiagnosisDocument.builder()
        .id(d.getId())
        .userId(d.getUserId())
        .region(d.getRegion())
        .purpose(d.getPurpose())
        .university(d.getUniversity())
        .district(d.getDistrict())
        .conditions(d.getConditions())
        .monthlyBudgetMax(d.getMonthlyBudgetMax())
        .arcStatus(d.getArcStatus())
        .status(d.getStatus())
        .submittedAt(d.getSubmittedAt())
        .build();
  }

  private static Diagnosis toDomain(DiagnosisDocument e) {
    return Diagnosis.builder()
        .id(e.getId())
        .userId(e.getUserId())
        .region(e.getRegion())
        .purpose(e.getPurpose())
        .university(e.getUniversity())
        .district(e.getDistrict())
        .conditions(e.getConditions())
        .monthlyBudgetMax(e.getMonthlyBudgetMax())
        .arcStatus(e.getArcStatus())
        .status(e.getStatus())
        .submittedAt(e.getSubmittedAt())
        .build();
  }
}
