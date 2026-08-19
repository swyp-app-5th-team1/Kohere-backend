package com.kohere.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kohere.chat.application.ChatService;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.presentation.ChatRoomController;
import com.kohere.chat.presentation.InquiryController;
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

class ChatApiSurfaceTest {

  @Test
  void inquiryReturnsCreatedOnlyForANewRoom() {
    ChatService chatService = mock(ChatService.class);
    InquiryController controller = new InquiryController(chatService);
    when(chatService.createInquiry("listing-id"))
        .thenReturn(new InquiryResponse(556L, true), new InquiryResponse(556L, false));

    assertThat(controller.createInquiry("listing-id").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(controller.createInquiry("listing-id").getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void restMessageSendReadAndRestoreAreNotExposed() {
    assertThat(mappedPaths(ChatRoomController.class, PostMapping.class)).isEmpty();
  }

  @Test
  void roomListDoesNotAcceptCategoryQuery() {
    Method listRooms = methodNamed(ChatRoomController.class, "listRooms");

    assertThat(requestParameterNames(listRooms)).containsExactly("page", "size");
  }

  @Test
  void messageHistorySupportsPastAndReconnectQueries() {
    Method getMessages = methodNamed(ChatRoomController.class, "getMessages");

    assertThat(mappedPaths(getMessages, GetMapping.class)).containsExactly("/{roomId}/messages");
    assertThat(requestParameterNames(getMessages))
        .containsExactly("cursor", "afterMessageId", "size");
  }

  private static Method methodNamed(Class<?> type, String name) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(method -> method.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

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

  private static Set<String> mappedPaths(Class<?> controller, Class<PostMapping> annotationType) {
    Set<String> paths = new LinkedHashSet<>();
    for (Method method : controller.getDeclaredMethods()) {
      paths.addAll(mappedPaths(method, annotationType));
    }
    return paths;
  }

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
