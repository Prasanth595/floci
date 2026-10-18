package io.github.hectorvent.floci.services.lambdaweb;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts what one output stream of an environment writes into log events. An event keeps its
 * newline, and a line longer than {@link #MAX_EVENT_BYTES} is cut there without its newline, as on
 * AWS. A cut never splits a UTF-8 character.
 */
final class LambdaWebOutputLines {

    /** The CloudWatch Logs event limit of 256 KiB less the 26 bytes it charges for each event. */
    static final int MAX_EVENT_BYTES = 262_118;

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    List<String> append(byte[] payload) {
        List<String> events = new ArrayList<>();
        for (byte b : payload) {
            // Bytes that are not UTF-8 may never reach a character start, so they are cut after the longest character.
            if (MAX_EVENT_BYTES < pending.size() + characterLength(b) || MAX_EVENT_BYTES + 3 <= pending.size()) {
                events.add(take());
            }
            pending.write(b);
            if (b == '\n') {
                events.add(take());
            }
        }
        return events;
    }

    /** The output after the last newline, when the stream ends without one. */
    String flush() {
        return pending.size() == 0 ? null : take();
    }

    /** The bytes of the character {@code b} starts, 0 for a continuation byte. */
    private static int characterLength(byte b) {
        int value = b & 0xFF;
        if (value < 0x80) {
            return 1;
        }
        if (value < 0xC0) {
            return 0;
        }
        if (value < 0xE0) {
            return 2;
        }
        return value < 0xF0 ? 3 : 4;
    }

    private String take() {
        String event = pending.toString(StandardCharsets.UTF_8);
        pending.reset();
        return event;
    }
}
