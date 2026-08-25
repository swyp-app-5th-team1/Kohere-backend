package com.kohere;

import static org.assertj.core.api.Assertions.assertThat;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * 모든 {@link ChangeUnit}이 Mongock의 구조 계약을 지키는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> Mongock은 이 계약을 <b>애플리케이션 기동 시점</b>에 검사한다 — 어기면 {@code initializingBeanRunner} 빈
 * 생성이 실패해 앱이 아예 뜨지 않는다. 그런데 changeUnit을 검증하는 테스트는 {@code execution()}을 직접 호출하므로 그 검사를 <b>우회한다</b>.
 * 실제로 {@code @RollbackExecution}이 둘인 changeUnit이 모든 테스트를 통과한 뒤 기동에서야 터진 적이 있다(#265) — 스키마 본문을 앞선
 * 유닛에서 복사하면서 그쪽 롤백 메서드까지 함께 딸려 온 경우다.
 *
 * <p>기동 없이 리플렉션만으로 같은 계약을 확인해 그 간극을 메운다. 클래스를 나열하지 않고 스캔하므로 <b>앞으로 추가되는 changeUnit도 자동으로</b> 대상이
 * 된다.
 */
class ChangeUnitContractTest {

  private static final String BASE_PACKAGE = "com.kohere";

  @Test
  @DisplayName("모든 ChangeUnit은 @Execution과 @RollbackExecution을 정확히 하나씩 갖는다")
  void everyChangeUnitHasExactlyOneExecutionAndRollback() {
    List<Class<?>> changeUnits = scanChangeUnits();

    // 스캔이 비면 이 테스트는 아무것도 지키지 못한다 — 패키지가 바뀌었다는 뜻이므로 먼저 막는다.
    assertThat(changeUnits).isNotEmpty();

    for (Class<?> changeUnit : changeUnits) {
      assertThat(countAnnotated(changeUnit, Execution.class))
          .as("%s의 @Execution 메서드 수", changeUnit.getSimpleName())
          .isEqualTo(1);
      assertThat(countAnnotated(changeUnit, RollbackExecution.class))
          .as("%s의 @RollbackExecution 메서드 수", changeUnit.getSimpleName())
          .isEqualTo(1);
    }
  }

  private static List<Class<?>> scanChangeUnits() {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(ChangeUnit.class));
    return scanner.findCandidateComponents(BASE_PACKAGE).stream()
        .map(BeanDefinition::getBeanClassName)
        .<Class<?>>map(name -> ClassUtils.resolveClassName(name, null))
        .toList();
  }

  private static long countAnnotated(Class<?> type, Class<? extends Annotation> annotation) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(annotation))
        .map(Method::getName)
        .count();
  }
}
