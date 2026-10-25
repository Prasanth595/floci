package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardedMessageQueueWriteFailureTest {

    private static final String KEY = "us-east-1::/000000000000/failure";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedReceiveKeepsTheMessageVisibleWithoutCountingADelivery(boolean fifo) {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message original = queue.peekAll().getFirst();
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> queue.claimVisibleMessages(1, 900, fifo, -1, null));

        assertSame(original, queue.peekAll().getFirst());
        assertTrue(original.isVisible());
        assertEquals(0, original.getReceiveCount());
        assertNull(original.getFirstReceiveTimestamp());
        assertNull(original.getReceiptHandle());
        assertEquals(0, store.get(KEY).orElseThrow().getFirst().getReceiveCount());
        assertNull(store.get(KEY).orElseThrow().getFirst().getReceiptHandle());
    }

    @Test
    void failedReceiveDoesNotAdvanceADeadLetterCandidate() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message first = queue.claimVisibleMessages(1, 0, false, -1, null).claimed().getFirst();
        Instant timestamp = first.getFirstReceiveTimestamp();
        String receipt = first.getReceiptHandle();
        store.fail = true;

        assertThrows(IllegalStateException.class,
                () -> queue.claimVisibleMessages(1, 900, false, 1, "synthetic-destination"));

        assertEquals(1, first.getReceiveCount());
        assertEquals(timestamp, first.getFirstReceiveTimestamp());
        assertEquals(receipt, first.getReceiptHandle());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedMixedReceiveRestoresEveryClaimAndDeadLetterCandidate(boolean fifo) {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = new GuardedMessageQueue(store, KEY);
        Instant earlier = Instant.now().minusSeconds(60);
        Message deadLetter = new Message("past-delivery");
        deadLetter.setMessageGroupId("old-group");
        deadLetter.setReceiveCount(1);
        deadLetter.setFirstReceiveTimestamp(earlier.minusSeconds(30));
        deadLetter.setVisibleAt(earlier);
        String previousReceipt = ReceiptHandle.issue(KEY, deadLetter.getMessageId(),
                ReceiptHandle.DEFAULT_SECRET).encode();
        deadLetter.setReceiptHandle(previousReceipt);
        Message first = new Message("first-fresh");
        first.setMessageGroupId("first-group");
        Message second = new Message("second-fresh");
        second.setMessageGroupId("second-group");
        Message hidden = new Message("still-hidden");
        hidden.setMessageGroupId("hidden-group");
        Instant future = Instant.now().plusSeconds(900);
        hidden.setVisibleAt(future);
        queue.addAll(List.of(deadLetter, first, second, hidden));
        store.fail = true;

        assertThrows(IllegalStateException.class,
                () -> queue.claimVisibleMessages(2, 900, fifo, 1, "synthetic-destination"));

        assertEquals(List.of(deadLetter, first, second, hidden), queue.peekAll());
        assertEquals(1, deadLetter.getReceiveCount());
        assertEquals(earlier.minusSeconds(30), deadLetter.getFirstReceiveTimestamp());
        assertEquals(previousReceipt, deadLetter.getReceiptHandle());
        assertEquals(earlier, deadLetter.getVisibleAt());
        for (Message fresh : List.of(first, second)) {
            assertEquals(0, fresh.getReceiveCount());
            assertNull(fresh.getFirstReceiveTimestamp());
            assertNull(fresh.getReceiptHandle());
            assertNull(fresh.getVisibleAt());
            assertTrue(fresh.isVisible());
        }
        assertEquals(0, hidden.getReceiveCount());
        assertNull(hidden.getFirstReceiveTimestamp());
        assertNull(hidden.getReceiptHandle());
        assertEquals(future, hidden.getVisibleAt());
        assertEquals(List.of(1, 0, 0, 0), store.get(KEY).orElseThrow().stream()
                .map(Message::getReceiveCount).toList());
        assertEquals(previousReceipt, store.get(KEY).orElseThrow().getFirst().getReceiptHandle());
        assertEquals(earlier, store.get(KEY).orElseThrow().getFirst().getVisibleAt());
    }

    @Test
    void failedReceiveRetainsTheQueueOwnersStateDespiteADifferentRequestAccount() {
        String owner = "111111111111";
        String caller = "222222222222";
        String fallback = "333333333333";
        String key = "us-east-1::/" + owner + "/owner-queue";
        RequestContext request = new RequestContext();
        request.setAccountId(caller);
        @SuppressWarnings("unchecked")
        Instance<RequestContext> requestContext = mock(Instance.class);
        when(requestContext.get()).thenReturn(request);
        FailingStore raw = new FailingStore();
        Message otherAccount = new Message("other-account");
        raw.put(caller + "/" + key, List.of(otherAccount));
        AccountAwareStorageBackend<List<Message>> store =
                new AccountAwareStorageBackend<>(raw, requestContext, fallback);
        assertEquals(caller, store.accountId());
        GuardedMessageQueue queue = new GuardedMessageQueue(store, key);
        queue.addMessage(new Message("owner-message"));
        Message live = queue.peekAll().getFirst();
        Message acknowledged = raw.get(owner + "/" + key).orElseThrow().getFirst();
        raw.fail = true;

        assertThrows(IllegalStateException.class,
                () -> queue.claimVisibleMessages(1, 900, true, -1, null));

        assertEquals(owner + "/" + key, raw.lastKey);
        assertSame(live, queue.peekAll().getFirst());
        assertEquals(0, live.getReceiveCount());
        assertNull(live.getFirstReceiveTimestamp());
        assertNull(live.getReceiptHandle());
        assertNull(live.getVisibleAt());
        assertSame(acknowledged, raw.get(owner + "/" + key).orElseThrow().getFirst());
        assertEquals(0, acknowledged.getReceiveCount());
        assertNull(acknowledged.getReceiptHandle());
        assertSame(otherAccount, raw.get(caller + "/" + key).orElseThrow().getFirst());
        assertTrue(raw.get(fallback + "/" + key).isEmpty());
        assertEquals(2, raw.keys().size());
    }

    @Test
    void failedVisibilityChangeKeepsTheAcknowledgedDeadline() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message message = queue.claimVisibleMessages(1, 900, true, -1, null).claimed().getFirst();
        Instant deadline = message.getVisibleAt();
        ReceiptHandle receipt = ReceiptHandle.parse(message.getReceiptHandle());
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> queue.changeVisibility(receipt, 0, true));

        assertEquals(deadline, message.getVisibleAt());
        assertEquals(deadline, store.get(KEY).orElseThrow().getFirst().getVisibleAt());
    }

    @Test
    void failedDeleteKeepsTheLiveAndStoredMessage() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message message = queue.claimVisibleMessages(1, 900, true, -1, null).claimed().getFirst();
        ReceiptHandle receipt = ReceiptHandle.parse(message.getReceiptHandle());
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> queue.removeByReceiptHandle(receipt, true));

        assertSame(message, queue.peekAll().getFirst());
        assertEquals(1, store.get(KEY).orElseThrow().size());
    }

    enum ListMutation { ADD, ADD_ALL, REMOVE, EXPIRE, PURGE, DRAIN, DRAIN_FIRST, RESTORE_FIRST }

    @ParameterizedTest
    @EnumSource(ListMutation.class)
    void aFailedListMutationKeepsTheAcknowledgedQueue(ListMutation mutation) {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message original = queue.peekAll().getFirst();
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> {
            switch (mutation) {
                case ADD -> queue.addMessage(new Message("incoming"));
                case ADD_ALL -> queue.addAll(List.of(new Message("incoming")));
                case REMOVE -> queue.removeMessages(List.of(original));
                case EXPIRE -> queue.removeExpired(Instant.now().plusSeconds(60));
                case PURGE -> queue.purge();
                case DRAIN -> queue.drainAll();
                case DRAIN_FIRST -> queue.drainFirstIf(message -> true);
                case RESTORE_FIRST -> queue.restoreFirst(new Message("incoming"));
            }
        });

        assertEquals(List.of(original), queue.peekAll());
        assertEquals(List.of("synthetic"), store.get(KEY).orElseThrow().stream().map(Message::getBody).toList());
    }

    @Test
    void aSnapshotStoredBeforeReceiveIsNotChangedBeforeTheNextCommit() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = queue(store);
        Message stored = store.get(KEY).orElseThrow().getFirst();
        Message live = queue.peekAll().getFirst();
        assertNotSame(live, stored);
        store.beforeFailure = () -> {
            assertEquals(0, stored.getReceiveCount());
            assertNull(stored.getFirstReceiveTimestamp());
            assertNull(stored.getReceiptHandle());
            assertTrue(stored.isVisible());
        };
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> queue.claimVisibleMessages(1, 900, true, -1, null));
    }

    @Test
    void aReloadedQueueDoesNotMutateTheStoredSnapshotBeforeCommit() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue original = queue(store);
        Message stored = store.get(KEY).orElseThrow().getFirst();
        GuardedMessageQueue restored = new GuardedMessageQueue(store.get(KEY).orElseThrow(), store, KEY);
        store.beforeFailure = () -> assertEquals(0, stored.getReceiveCount());
        store.fail = true;

        assertThrows(IllegalStateException.class, () -> restored.claimVisibleMessages(1, 900, true, -1, null));

        assertEquals(0, original.peekAll().getFirst().getReceiveCount());
        assertEquals(0, stored.getReceiveCount());
    }

    @Test
    void compactionDuringAFailedReceiveWritesOnlyTheAcknowledgedState() throws Exception {
        Path snapshot = directory.resolve("messages.json");
        class CompactingStore extends WalStorage<String, List<Message>> {
            private boolean fail;

            CompactingStore() {
                super(snapshot, directory.resolve("messages.wal"), new TypeReference<Map<String, List<Message>>>() {},
                        Long.MAX_VALUE);
            }

            @Override
            public void put(String key, List<Message> value) {
                if (fail) {
                    flush();
                    throw new IllegalStateException("synthetic write failure after compaction");
                }
                super.put(key, value);
            }
        }
        CompactingStore store = new CompactingStore();
        store.load();
        try {
            GuardedMessageQueue queue = new GuardedMessageQueue(store, KEY);
            queue.addMessage(new Message("synthetic"));
            store.fail = true;

            assertThrows(IllegalStateException.class, () -> queue.claimVisibleMessages(1, 900, true, -1, null));

            assertEquals(0, new ObjectMapper().readTree(snapshot.toFile()).path(KEY).get(0).path("receiveCount").asInt());
            assertEquals(0, queue.peekAll().getFirst().getReceiveCount());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void persistedSnapshotsDetachAttributesAndBinaryValues() {
        FailingStore store = new FailingStore();
        GuardedMessageQueue queue = new GuardedMessageQueue(store, KEY);
        Message message = new Message("synthetic");
        byte[] binary = {1, 2, 3};
        MessageAttributeValue attribute = new MessageAttributeValue(binary, "Binary");
        message.getMessageAttributes().put("payload", attribute);
        queue.addMessage(message);

        attribute.setDataType("changed");
        binary[0] = 9;
        message.getMessageAttributes().clear();
        Message stored = store.get(KEY).orElseThrow().getFirst();

        assertEquals("Binary", stored.getMessageAttributes().get("payload").getDataType());
        assertArrayEquals(new byte[] {1, 2, 3}, stored.getMessageAttributes().get("payload").getBinaryValue());
    }

    @Test
    void aDetachedMessageCopyPreservesItsIdentityContentAndDeliveryMetadata() {
        Message message = new Message("synthetic-body");
        Instant sent = Instant.parse("2026-01-01T00:00:00Z");
        message.setMessageId("synthetic-message-id");
        message.setSentTimestamp(sent);
        message.setFirstReceiveTimestamp(sent.plusSeconds(30));
        message.setReceiveCount(7);
        message.setMd5OfBody("00000000000000000000000000000000");
        message.setMd5OfMessageAttributes("11111111111111111111111111111111");
        message.setMessageGroupId("synthetic-group");
        message.setMessageDeduplicationId("synthetic-deduplication");
        message.setSequenceNumber(42);
        message.setOriginalSourceQueueUrl("http://localhost:4566/111111111111/source");
        message.setAwsTraceHeader("Root=1-00000000-000000000000000000000000");
        message.setSenderId("synthetic-sender");
        message.setRetentionStartTimestamp(sent.plusSeconds(10));
        message.setReceiptHandle("synthetic-receipt");
        message.setVisibleAt(sent.plusSeconds(60));
        message.getMessageAttributes().put("text", new MessageAttributeValue("synthetic-text", "String"));
        message.getMessageAttributes().put("binary", new MessageAttributeValue(new byte[] {1, 2, 3}, "Binary"));

        Message copy = message.copy();

        assertNotSame(message, copy);
        assertEquals(message.getMessageId(), copy.getMessageId());
        assertEquals(message.getBody(), copy.getBody());
        assertEquals(message.getSentTimestamp(), copy.getSentTimestamp());
        assertEquals(message.getFirstReceiveTimestamp(), copy.getFirstReceiveTimestamp());
        assertEquals(message.getReceiveCount(), copy.getReceiveCount());
        assertEquals(message.getMd5OfBody(), copy.getMd5OfBody());
        assertEquals(message.getMd5OfMessageAttributes(), copy.getMd5OfMessageAttributes());
        assertEquals(message.getMessageGroupId(), copy.getMessageGroupId());
        assertEquals(message.getMessageDeduplicationId(), copy.getMessageDeduplicationId());
        assertEquals(message.getSequenceNumber(), copy.getSequenceNumber());
        assertEquals(message.getOriginalSourceQueueUrl(), copy.getOriginalSourceQueueUrl());
        assertEquals(message.getAwsTraceHeader(), copy.getAwsTraceHeader());
        assertEquals(message.getSenderId(), copy.getSenderId());
        assertEquals(message.getRetentionStartTimestamp(), copy.getRetentionStartTimestamp());
        assertEquals(message.getReceiptHandle(), copy.getReceiptHandle());
        assertEquals(message.getVisibleAt(), copy.getVisibleAt());
        assertNotSame(message.getMessageAttributes(), copy.getMessageAttributes());
        MessageAttributeValue text = copy.getMessageAttributes().get("text");
        assertNotSame(message.getMessageAttributes().get("text"), text);
        assertEquals("synthetic-text", text.getStringValue());
        assertEquals("String", text.getDataType());
        assertNull(text.getBinaryValue());
        MessageAttributeValue binary = copy.getMessageAttributes().get("binary");
        assertNotSame(message.getMessageAttributes().get("binary"), binary);
        assertNotSame(message.getMessageAttributes().get("binary").getBinaryValue(), binary.getBinaryValue());
        assertArrayEquals(new byte[] {1, 2, 3}, binary.getBinaryValue());
        assertEquals("Binary", binary.getDataType());
        assertNull(binary.getStringValue());
    }

    private GuardedMessageQueue queue(FailingStore store) {
        GuardedMessageQueue queue = new GuardedMessageQueue(store, KEY);
        queue.addMessage(new Message("synthetic"));
        return queue;
    }

    private static class FailingStore extends InMemoryStorage<String, List<Message>> {
        private boolean fail;
        private String lastKey;
        private Runnable beforeFailure = () -> { };

        @Override
        public void put(String key, List<Message> value) {
            lastKey = key;
            if (fail) {
                beforeFailure.run();
                throw new IllegalStateException("synthetic write failure");
            }
            super.put(key, value);
        }
    }
}
