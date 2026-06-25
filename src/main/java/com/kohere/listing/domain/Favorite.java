package com.kohere.listing.domain;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/** 찜. {@code (userId, listingId)} 유니크로 멱등을 보장한다. docs/api/specs/03-listings-favorites.md. */
@Getter
@Builder
public class Favorite {

  private final Long id;
  private final Long userId;
  private final String listingId;
  private final Instant favoritedAt;
}
