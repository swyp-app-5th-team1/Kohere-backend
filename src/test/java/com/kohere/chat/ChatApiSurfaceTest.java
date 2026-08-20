package com.kohere.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kohere.chat.application.ChatService;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.presentation.ChatRoomController;
import com.kohere.chat.presentation.InquiryController;
import com.kohere.common.security.AuthPrincipal;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 1단계에서 합의한 REST 표면이 다시 구 API로 돌아가지 않도록 지키는 계약 테스트다.
 *
 * <p>서비스 구현 전에도 controller annotation과 status 선택 규칙은 검증할 수 있다. 실제 참여자 검증·DB 저장·페이지 결과는 후속 단계의 통합
 * 테스트가 담당한다.
 */
class ChatApiSurfaceTest {

  /** 새 방과 기존 방을 앱이 구분할 수 있도록 201/200 규칙을 고정한다. */
  @Test
  void inquiryReturnsCreatedOnlyForANewRoom() {
    ChatService chatService = mock(ChatService.class);
    InquiryController controller = new InquiryController(chatService);
    AuthPrincipal principal = new AuthPrincipal(7L, true);
    when(chatService.createInquiry(7L, "listing-id"))
        .thenReturn(new InquiryResponse(556L, true), new InquiryResponse(556L, false));

    assertThat(controller.createInquiry(principal, "listing-id").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(controller.createInquiry(principal, "listing-id").getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  /**
   * TEXT 전송을 STOMP 한 경로로 통일하고 읽음·사용자 복원은 후속 범위로 남겼는지 확인한다.
   *
   * <p>실수로 POST mapping을 되살리면 구현되지 않은 API가 노출되거나 REST와 STOMP의 멱등 규칙이 갈릴 수 있다.
   */
  @Test
  void restMessageSendReadAndRestoreAreNotExposed() {
    assertThat(mappedPaths(ChatRoomController.class, PostMapping.class)).isEmpty();
  }

  /** 읽음 수와 함께 이연한 category 필터가 목록 query에 다시 섞이지 않도록 보호한다. */
  @Test
  void roomListDoesNotAcceptCategoryQuery() {
    Method listRooms = methodNamed(ChatRoomController.class, "listRooms");

    assertThat(requestParameterNames(listRooms)).containsExactly("page", "size");
  }

  /** 목록·알림에서 받은 roomId로 채팅방 한 건을 여는 GET 경로를 고정한다. */
  @Test
  void roomDetailUsesServerRoomIdPath() {
    Method getRoom = methodNamed(ChatRoomController.class, "getRoom");

    assertThat(mappedPaths(getRoom, GetMapping.class)).containsExactly("/{roomId}");
    assertThat(requestParameterNames(getRoom)).isEmpty();
  }

  /** 과거 스크롤과 재연결 누락 보충이 같은 조회 endpoint에서 명시적으로 구분되는지 확인한다. */
  @Test
  void messageHistorySupportsPastAndReconnectQueries() {
    Method getMessages = methodNamed(ChatRoomController.class, "getMessages");

    assertThat(mappedPaths(getMessages, GetMapping.class)).containsExactly("/{roomId}/messages");
    assertThat(requestParameterNames(getMessages))
        .containsExactly("cursor", "afterMessageId", "size");
  }

  /** reflection 단정을 읽기 쉽게 유지하기 위한 이름 기반 메서드 조회 도우미. */
  private static Method methodNamed(Class<?> type, String name) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(method -> method.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  /** 선언 순서를 보존해 query parameter의 와이어 계약까지 비교한다. */
  private static Set<String> requestParameterNames(Method method) {
    Set<String> names = new LinkedHashSet<>();
    for (Parameter parameter : method.getParameters()) {
      RequestParam annotation = parameter.getAnnotation(RequestParam.class);
      if (annotation != null) {
        String declaredName = annotation.name().isBlank() ? annotation.value() : annotation.name();
        names.add(declaredName.isBlank() ? parameter.getName() : declaredName);
      }
    }
    return names;
  }

  /** 컨트롤러에 노출된 특정 HTTP method의 모든 상대 경로를 모은다. */
  private static Set<String> mappedPaths(Class<?> controller, Class<PostMapping> annotationType) {
    Set<String> paths = new LinkedHashSet<>();
    for (Method method : controller.getDeclaredMethods()) {
      paths.addAll(mappedPaths(method, annotationType));
    }
    return paths;
  }

  /** 테스트가 사용하는 GET·POST mapping의 경로만 안전하게 추출한다. */
  private static <A extends java.lang.annotation.Annotation> Set<String> mappedPaths(
      Method method, Class<A> annotationType) {
    A annotation = method.getAnnotation(annotationType);
    if (annotation == null) {
      return Set.of();
    }
    if (annotation instanceof PostMapping postMapping) {
      return Set.of(postMapping.value());
    }
    if (annotation instanceof GetMapping getMapping) {
      return Set.of(getMapping.value());
    }
    throw new IllegalArgumentException("Unsupported mapping annotation: " + annotationType);
  }
}
