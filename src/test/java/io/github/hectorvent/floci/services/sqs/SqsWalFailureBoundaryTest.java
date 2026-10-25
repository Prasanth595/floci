package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqsWalFailureBoundaryTest {
    private static final String KEY = "us-east-1::/000000000000/failure";
    @TempDir Path directory;

    @Test
    void failedReceiveMustNotHideOrCountAnUndeliveredMessage() throws Exception {
        WalStorage<String, List<Message>> store = store();
        try {
            GuardedMessageQueue queue = queue(store);
            failWriter(store);
            assertThrows(UncheckedIOException.class,
                    () -> queue.claimVisibleMessages(1, 900, true, -1, null));
            Message message = queue.peekAll().getFirst();
            assertAll(
                    () -> assertTrue(message.isVisible(), "failed receive hid the message"),
                    () -> assertEquals(0, message.getReceiveCount(), "failed receive advanced redrive count"),
                    () -> assertNull(message.getReceiptHandle(), "receipt was never delivered to caller"),
                    () -> assertTrue(store.get(KEY).orElseThrow().getFirst().isVisible(),
                            "stored mutable object was changed before WAL commit"));
        } finally { store.shutdown(); }
    }

    @Test
    void failedVisibilityChangeMustRetainTheAcknowledgedDeadline() throws Exception {
        WalStorage<String, List<Message>> store = store();
        try {
            GuardedMessageQueue queue = queue(store);
            Message claimed = queue.claimVisibleMessages(1, 900, true, -1, null).claimed().getFirst();
            Instant deadline = claimed.getVisibleAt();
            ReceiptHandle receipt = ReceiptHandle.parse(claimed.getReceiptHandle());
            failWriter(store);
            assertThrows(UncheckedIOException.class, () -> queue.changeVisibility(receipt, 0, true));
            assertEquals(deadline, queue.peekAll().getFirst().getVisibleAt(),
                    "failed change released the message");
        } finally { store.shutdown(); }
    }

    @Test
    void failedDeleteMustRetainTheMessageInTheLiveQueue() throws Exception {
        WalStorage<String, List<Message>> store = store();
        try {
            GuardedMessageQueue queue = queue(store);
            Message claimed = queue.claimVisibleMessages(1, 900, true, -1, null).claimed().getFirst();
            ReceiptHandle receipt = ReceiptHandle.parse(claimed.getReceiptHandle());
            failWriter(store);
            assertThrows(UncheckedIOException.class, () -> queue.removeByReceiptHandle(receipt, true));
            assertEquals(1, queue.peekAll().size(), "failed delete removed the live message");
        } finally { store.shutdown(); }
    }

    private WalStorage<String, List<Message>> store() {
        WalStorage<String, List<Message>> store = new WalStorage<>(directory.resolve("snapshot.json"),
                directory.resolve("data.wal"), new TypeReference<Map<String, List<Message>>>() {}, Long.MAX_VALUE);
        store.load();
        return store;
    }

    private GuardedMessageQueue queue(WalStorage<String, List<Message>> store) {
        GuardedMessageQueue queue = new GuardedMessageQueue(store, KEY);
        queue.addMessage(new Message("synthetic"));
        return queue;
    }

    private void failWriter(WalStorage<?, ?> store) throws Exception {
        Field field = WalStorage.class.getDeclaredField("walWriter");
        field.setAccessible(true);
        ((DataOutputStream) field.get(store)).close();
        field.set(store, new DataOutputStream(new OutputStream() {
            @Override public void write(int value) { }
            @Override public void flush() throws IOException {
                throw new IOException("synthetic flush failure");
            }
        }));
    }
}
