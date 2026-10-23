package com.example.hotel.chat;

import static org.assertj.core.api.Assertions.*;

import com.example.hotel.audit.AuditProblem;
import java.time.*;
import org.junit.jupiter.api.Test;

class ConversationMemoryTest {
  final ConversationMemory memory = new ConversationMemory(6, Duration.ofMinutes(30), 2);

  @Test
  void ownerCanResumeButOtherUserCannot() {
    var first = memory.open("reader", null);
    assertThat(memory.open("reader", first.id)).isSameAs(first);
    assertThatThrownBy(() -> memory.open("booker", first.id))
        .isInstanceOf(AuditProblem.class)
        .hasMessage("CONVERSATION_ACCESS_DENIED");
    assertThat(memory.open("reader", first.id)).isSameAs(first);
  }

  @Test
  void NewConversationsAreDistinctEvenForSameUser() {
    var first = memory.open("reader", null);
    var second = memory.open("reader", null);
    assertThat(second.id).isNotEqualTo(first.id);
    assertThat(second.advisor).isNotSameAs(first.advisor);
  }

  @Test
  void unknownAndInvalidIdsCannotCreateOrClaimHistory() {
    assertThatThrownBy(() -> memory.open("reader", "not-a-uuid"))
        .hasMessage("INVALID_CONVERSATION_ID");
    assertThatThrownBy(() -> memory.open("reader", java.util.UUID.randomUUID().toString()))
        .hasMessage("CONVERSATION_NOT_FOUND");
  }

  @Test
  void limitEvictsLeastRecentlyUsedConversation() {
    var first = memory.open("reader", null);
    var second = memory.open("reader", null);
    memory.open("reader", first.id);
    memory.open("booker", null);
    assertThatThrownBy(() -> memory.open("reader", second.id)).hasMessage("CONVERSATION_NOT_FOUND");
    assertThat(memory.open("reader", first.id)).isSameAs(first);
  }

  @Test
  void idleHistoryExpiresWithoutSleeping() {
    var instant = new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
    var clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return instant.get();
          }
        };
    var expiring = new ConversationMemory(6, Duration.ofMinutes(30), 2, clock);
    var first = expiring.open("reader", null);
    instant.set(Instant.EPOCH.plus(Duration.ofMinutes(30)));
    assertThatThrownBy(() -> expiring.open("reader", first.id))
        .hasMessage("CONVERSATION_NOT_FOUND");
  }
}
