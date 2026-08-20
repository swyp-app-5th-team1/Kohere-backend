package com.kohere.chat.domain;

/**
 * 채팅방을 처음 만들 때 고정하는 매물 표시 정보다.
 *
 * <p>매물이 나중에 비공개 또는 삭제되어도 기존 대화의 제목과 주소를 표시해야 하므로, 채팅방은 listing 모듈을 매번 다시 조회하지 않고 이 사본을 사용한다.
 *
 * @param title 채팅방 헤더와 목록에 표시할 생성 당시 매물 제목
 * @param address 생성 당시 사용자 표시용 주소
 */
public record ListingSnapshot(String title, String address) {}
