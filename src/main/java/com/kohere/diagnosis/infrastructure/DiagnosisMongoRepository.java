package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.domain.DiagnosisStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/** 진단 Spring Data MongoDB 리포지토리(infrastructure 내부). 어댑터에서만 사용한다. */
interface DiagnosisMongoRepository extends MongoRepository<DiagnosisDocument, Long> {

  Optional<DiagnosisDocument> findFirstByUserIdAndStatus(Long userId, DiagnosisStatus status);

  Optional<DiagnosisDocument> findFirstByUserIdAndStatusOrderBySubmittedAtDesc(
      Long userId, DiagnosisStatus status);

  Page<DiagnosisDocument> findByUserIdAndStatus(
      Long userId, DiagnosisStatus status, Pageable pageable);

  long countByUserIdAndStatus(Long userId, DiagnosisStatus status);
}
