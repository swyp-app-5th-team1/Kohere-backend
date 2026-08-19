package com.kohere.chat.presentation.stomp.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** 개인 control queue의 구독 준비 여부를 확인하기 위해 앱이 보내는 ping. */
public record ChatControlPingPayload(int version, @NotNull UUID requestId) {}
