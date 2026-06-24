package com.kohere.diagnosis.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.diagnosis.application.dto.AnswerSavedResponse;
import com.kohere.diagnosis.application.dto.DiagnosisCreatedResponse;
import com.kohere.diagnosis.application.dto.DiagnosisResponse;
import com.kohere.diagnosis.application.dto.LatestDiagnosisResponse;
import com.kohere.diagnosis.application.dto.QuestionResponse;
import com.kohere.diagnosis.application.dto.RecommendationResponse;
import com.kohere.diagnosis.domain.ArcStatus;
import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisAccessDeniedException;
import com.kohere.diagnosis.domain.DiagnosisCondition;
import com.kohere.diagnosis.domain.DiagnosisNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisQuestion;
import com.kohere.diagnosis.domain.DiagnosisQuestionCatalog;
import com.kohere.diagnosis.domain.DiagnosisRepository;
import com.kohere.diagnosis.domain.District;
import com.kohere.diagnosis.domain.Purpose;
import com.kohere.diagnosis.domain.Region;
import com.kohere.diagnosis.domain.University;
import com.kohere.diagnosis.presentation.dto.AnswerRequest;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendationCriteria;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.user.api.UserAccountService;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 맞춤 진단·매물 추천 유스케이스 조율. 도메인(포트)을 호출하고 흐름만 조율하며, 도메인 규칙(완결성·조건부 필수)은 {@link Diagnosis}에 둔다
 * (docs/convention/code-style.md §3-3). 인증 주체(userId)는 표현 계층이 {@code AuthPrincipal}에서 꺼내 전달한다.
 *
 * <p>서버 stateful 진단: 사용자당 IN_PROGRESS 진단 1건에 단계별 답을 저장하고({@link #submitAnswer}), 확정 시 COMPLETED로
 * 전이한다({@link #submit}). 문항·선택지는 MongoDB {@code diagnosisQuestions} 카탈로그에서 받아 등록 국가→언어로
 * 번역한다(ADR-0028·0029). 추천은 listing 공개 쿼리({@link ListingRecommendationService})를 동기 호출한다(ADR-0002
 * D5).
 *
 * <p>MongoDB는 단일 노드에서 멀티도큐먼트 트랜잭션을 지원하지 않으므로 {@code @Transactional}을 두지 않는다(연산은 단건 위주).
 *
 * <p>스펙: docs/api/specs/02-diagnosis-recommendation.md.
 */
@Service
@RequiredArgsConstructor
public class DiagnosisService {

  private static final Set<String> HISTORY_SORT_KEYS = Set.of("submittedAt");
  private static final Set<String> RECOMMENDATION_SORT_KEYS =
      Set.of("recommended", "price", "distance");
  private static final String DEFAULT_RECOMMENDATION_SORT = "recommended,desc";

  /** 라벨·메시지 언어-키 맵에서 사용자 언어 값이 없을 때의 폴백 언어(ADR-0029). */
  private static final String DEFAULT_LANGUAGE = "en";

  private final DiagnosisRepository diagnosisRepository;
  private final DiagnosisQuestionCatalog questionCatalog;
  private final ListingRecommendationService listingRecommendationService;
  private final UserAccountService userAccountService;
  private final SuggestionMessages suggestionMessages;

  /** 단계별 질문 1개 조회(③은 저장된 purpose로 university/district 택일, 등록 국가 언어로 번역). */
  public QuestionResponse getQuestion(long userId, int step) {
    if (step < 1 || step > 6) {
      throw new InvalidInputException("step은 1~6 사이여야 합니다: " + step);
    }
    // ③(step 3)은 저장된 purpose로 어느 문항을 낼지 서버가 정한다(분기). 카탈로그 조회 전에 결정해 purpose
    // 미선행이면 INVALID_INPUT으로 먼저 막는다(카탈로그에는 분기 메타를 두지 않는다 — ADR-0028).
    String branchField = step == 3 ? resolveStep3Field(userId) : null;
    List<DiagnosisQuestion> questions = questionCatalog.findByStep(step);
    if (questions.isEmpty()) {
      throw new IllegalStateException("진단 문항 카탈로그가 없습니다: step=" + step);
    }
    DiagnosisQuestion question = selectQuestion(questions, branchField);
    String language = resolveLanguage(userId);
    return toQuestionResponse(question, language);
  }

  /** step 3은 {@code branchField}(university/district)로 택일하고, 그 외 단계는 단일 문항을 그대로 쓴다. */
  private static DiagnosisQuestion selectQuestion(
      List<DiagnosisQuestion> questions, String branchField) {
    if (branchField == null) {
      return questions.get(0);
    }
    return questions.stream()
        .filter(q -> branchField.equals(q.field()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("진단 문항 카탈로그가 없습니다: " + branchField));
  }

  /** 현재 step의 답 1개를 진행 중(IN_PROGRESS) 진단에 저장한다(없으면 초안 생성). */
  public AnswerSavedResponse submitAnswer(long userId, AnswerRequest request) {
    Diagnosis draft =
        diagnosisRepository
            .findInProgressByUserId(userId)
            .orElseGet(() -> Diagnosis.startInProgress(userId));
    Diagnosis updated = applyAnswer(draft, request);
    diagnosisRepository.save(updated);
    return new AnswerSavedResponse(true);
  }

  /** 진행 중 진단을 확정(IN_PROGRESS→COMPLETED). 저장된 답을 재검증하고 새 diagnosisId·submittedAt을 발급한다. */
  public DiagnosisCreatedResponse submit(long userId) {
    Diagnosis draft =
        diagnosisRepository
            .findInProgressByUserId(userId)
            .orElseThrow(() -> new InvalidInputException("진행 중인 진단이 없습니다."));
    Diagnosis completed = diagnosisRepository.save(draft.complete(Instant.now()));
    return new DiagnosisCreatedResponse(
        completed.getId(), completed.getStatus(), completed.getSubmittedAt());
  }

  /** 내 완료 진단 이력(최신순, 오프셋 페이지). */
  public PageResponse<DiagnosisResponse> getHistory(long userId, int page, int size, String sort) {
    validatePage(page, size);
    validateSort(sort, HISTORY_SORT_KEYS);
    List<DiagnosisResponse> content =
        diagnosisRepository.findCompletedByUserId(userId, page, size, isAscending(sort)).stream()
            .map(DiagnosisService::toResponse)
            .toList();
    long total = diagnosisRepository.countCompletedByUserId(userId);
    return PageResponse.of(content, pageInfo(page, size, total));
  }

  /** 최근 완료 진단 단건(없으면 completed=false). */
  public LatestDiagnosisResponse getLatest(long userId) {
    return diagnosisRepository
        .findLatestCompletedByUserId(userId)
        .map(DiagnosisService::toLatestResponse)
        .orElseGet(
            () ->
                new LatestDiagnosisResponse(
                    false, null, null, null, null, null, null, null, null, null));
  }

  /** 진단 단건 상세(본인 소유만, 타인 403·미존재 404). */
  public DiagnosisResponse getDetail(long userId, Long diagnosisId) {
    Diagnosis diagnosis =
        diagnosisRepository.findById(diagnosisId).orElseThrow(DiagnosisNotFoundException::new);
    requireOwner(diagnosis, userId);
    return toResponse(diagnosis);
  }

  /** 진단 결과 추천 매물·지도 좌표(본인 소유만). 0건이면 빈 목록 + 조정 제안(번역). */
  public RecommendationResponse getRecommendations(
      long userId, Long diagnosisId, int page, int size, String sort) {
    validatePage(page, size);
    validateSort(sort, RECOMMENDATION_SORT_KEYS);
    Diagnosis diagnosis =
        diagnosisRepository.findById(diagnosisId).orElseThrow(DiagnosisNotFoundException::new);
    requireOwner(diagnosis, userId);

    RecommendationCriteria criteria = toCriteria(diagnosis, page, size, sort);
    PageResponse<RecommendedListingView> result =
        listingRecommendationService.recommendByCriteria(criteria);

    List<RecommendationResponse.RecommendedListing> content =
        result.content().stream().map(DiagnosisService::toRecommendedListing).toList();
    List<RecommendationResponse.MapMarker> markers =
        result.content().stream()
            .map(v -> new RecommendationResponse.MapMarker(v.listingId(), v.lat(), v.lng()))
            .toList();
    RecommendationResponse.Suggestions suggestions =
        content.isEmpty() ? suggestionMessages.noMatch(resolveLanguage(userId)) : null;
    return new RecommendationResponse(content, markers, result.page(), suggestions);
  }

  // --- ③ 단계 분기(purpose → university/district) ---

  private String resolveStep3Field(long userId) {
    Purpose purpose =
        diagnosisRepository
            .findInProgressByUserId(userId)
            .map(Diagnosis::getPurpose)
            .orElseThrow(() -> new InvalidInputException("purpose 답변이 선행되어야 합니다."));
    if (purpose == null) {
      throw new InvalidInputException("purpose 답변이 선행되어야 합니다.");
    }
    return purpose == Purpose.STUDY ? "university" : "district";
  }

  // --- 답 적용(파싱·정합성 검증) ---

  private Diagnosis applyAnswer(Diagnosis draft, AnswerRequest request) {
    String field = request.field();
    if (field == null || field.isBlank()) {
      throw new InvalidInputException("field가 필요합니다.");
    }
    Diagnosis.DiagnosisBuilder builder = draft.toBuilder();
    switch (field) {
      case "region" -> builder.region(parseEnum(Region.class, requireCode(request)));
      case "purpose" ->
          builder
              .purpose(parseEnum(Purpose.class, requireCode(request)))
              .university(null)
              .district(null);
      case "university" -> {
        requirePurpose(draft, Purpose.STUDY, "university");
        builder.university(parseEnum(University.class, requireCode(request))).district(null);
      }
      case "district" -> {
        requirePurpose(draft, Purpose.NON_STUDY, "district");
        builder.district(parseEnum(District.class, requireCode(request))).university(null);
      }
      case "conditions" -> builder.conditions(parseConditions(request));
      case "monthlyBudgetMax" -> builder.monthlyBudgetMax(parseBudget(requireCode(request)));
      case "arcStatus" -> builder.arcStatus(parseEnum(ArcStatus.class, requireCode(request)));
      default -> throw new InvalidInputException("지원하지 않는 field입니다: " + field);
    }
    return builder.build();
  }

  private static String requireCode(AnswerRequest request) {
    if (request.code() == null || request.code().isBlank()) {
      throw new InvalidInputException("code가 필요합니다.");
    }
    return request.code();
  }

  private static void requirePurpose(Diagnosis draft, Purpose expected, String field) {
    if (draft.getPurpose() != expected) {
      throw new InvalidInputException(field + "는 purpose=" + expected.name() + "일 때만 저장할 수 있습니다.");
    }
  }

  private static Set<DiagnosisCondition> parseConditions(AnswerRequest request) {
    Set<DiagnosisCondition> result = new LinkedHashSet<>();
    if (request.codes() != null) {
      for (String code : request.codes()) {
        result.add(parseEnum(DiagnosisCondition.class, code));
      }
    }
    if (result.size() > Diagnosis.MAX_CONDITIONS) {
      throw new InvalidInputException("conditions는 최대 3개까지 선택할 수 있습니다.");
    }
    return result;
  }

  private static int parseBudget(String code) {
    try {
      int value = Integer.parseInt(code.trim());
      if (value < 0) {
        throw new InvalidInputException("monthlyBudgetMax는 0 이상이어야 합니다.");
      }
      return value;
    } catch (NumberFormatException e) {
      throw new InvalidInputException("monthlyBudgetMax는 숫자여야 합니다: " + code);
    }
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidInputException(type.getSimpleName() + " 값이 올바르지 않습니다: " + value);
    }
  }

  // --- 번역 ---

  private String resolveLanguage(long userId) {
    return userAccountService.getLanguage(userId);
  }

  private static QuestionResponse toQuestionResponse(DiagnosisQuestion question, String language) {
    QuestionResponse.Select select =
        new QuestionResponse.Select(question.select().type(), question.select().max());
    List<QuestionResponse.Option> options =
        question.options().stream()
            .map(o -> new QuestionResponse.Option(o.code(), pickLabel(o.label(), language)))
            .toList();
    return new QuestionResponse(
        question.step(),
        question.field(),
        pickLabel(question.question(), language),
        select,
        options);
  }

  private static String pickLabel(Map<String, String> labels, String language) {
    if (labels == null) {
      return null;
    }
    String value = labels.get(language);
    if (value != null) {
      return value;
    }
    return labels.getOrDefault(DEFAULT_LANGUAGE, labels.values().stream().findFirst().orElse(null));
  }

  // --- 매핑 ---

  private RecommendationCriteria toCriteria(Diagnosis d, int page, int size, String sort) {
    return new RecommendationCriteria(
        d.getRegion() == null ? null : d.getRegion().name(),
        d.getMonthlyBudgetMax() == null ? 0 : d.getMonthlyBudgetMax(),
        d.getConditions() == null
            ? Set.of()
            : d.getConditions().stream().map(Enum::name).collect(Collectors.toSet()),
        d.getUniversity() == null ? null : d.getUniversity().name(),
        d.getDistrict() == null ? null : d.getDistrict().name(),
        page,
        size,
        sort == null || sort.isBlank() ? DEFAULT_RECOMMENDATION_SORT : sort);
  }

  private static RecommendationResponse.RecommendedListing toRecommendedListing(
      RecommendedListingView v) {
    return new RecommendationResponse.RecommendedListing(
        v.listingId(),
        v.title(),
        v.type(),
        v.monthlyRent(),
        v.deposit(),
        v.thumbnailUrl(),
        v.lat(),
        v.lng(),
        v.conditions());
  }

  private static DiagnosisResponse toResponse(Diagnosis d) {
    return new DiagnosisResponse(
        d.getId(),
        d.getRegion(),
        d.getPurpose(),
        d.getUniversity(),
        d.getDistrict(),
        conditionsList(d),
        d.getMonthlyBudgetMax() == null ? 0 : d.getMonthlyBudgetMax(),
        d.getArcStatus(),
        d.getStatus(),
        d.getSubmittedAt());
  }

  private static LatestDiagnosisResponse toLatestResponse(Diagnosis d) {
    return new LatestDiagnosisResponse(
        true,
        d.getId(),
        d.getRegion(),
        d.getPurpose(),
        d.getUniversity(),
        d.getDistrict(),
        conditionsList(d),
        d.getMonthlyBudgetMax(),
        d.getArcStatus(),
        d.getSubmittedAt());
  }

  private static List<DiagnosisCondition> conditionsList(Diagnosis d) {
    return d.getConditions() == null ? List.of() : List.copyOf(d.getConditions());
  }

  private static void requireOwner(Diagnosis diagnosis, long userId) {
    if (diagnosis.getUserId() == null || diagnosis.getUserId() != userId) {
      throw new DiagnosisAccessDeniedException();
    }
  }

  // --- 페이지·정렬 검증 ---

  private static void validatePage(int page, int size) {
    if (page < 0) {
      throw new InvalidInputException("page는 0 이상이어야 합니다: " + page);
    }
    if (size < 1 || size > 100) {
      throw new InvalidInputException("size는 1~100 사이여야 합니다: " + size);
    }
  }

  private static void validateSort(String sort, Set<String> allowedKeys) {
    if (sort == null || sort.isBlank()) {
      return;
    }
    String[] parts = sort.split(",");
    String key = parts[0].trim();
    if (!allowedKeys.contains(key)) {
      throw new InvalidInputException("정렬 키가 올바르지 않습니다: " + key);
    }
    if (parts.length > 1) {
      String direction = parts[1].trim().toLowerCase();
      if (!direction.equals("asc") && !direction.equals("desc")) {
        throw new InvalidInputException("정렬 방향이 올바르지 않습니다: " + parts[1]);
      }
    }
  }

  /** sort 문자열의 방향(asc)을 해석한다(미지정·desc면 false). 검증은 {@link #validateSort}가 선행한다. */
  private static boolean isAscending(String sort) {
    if (sort == null || sort.isBlank()) {
      return false;
    }
    String[] parts = sort.split(",");
    return parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc");
  }

  private static PageInfo pageInfo(int page, int size, long total) {
    int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
    boolean hasNext = (page + 1) < totalPages;
    return new PageInfo(page, size, total, totalPages, hasNext);
  }
}
