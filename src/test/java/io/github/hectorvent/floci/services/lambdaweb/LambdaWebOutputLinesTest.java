package io.github.hectorvent.floci.services.lambdaweb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LambdaWebOutputLinesTest {

    @Test
    void eachLineIsAnEventWithItsNewline() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();

        assertEquals(List.of("one\n", "two\n"), lines.append(bytes("one\ntwo\nthr")));
        assertEquals(List.of("three\n"), lines.append(bytes("ee\n")));
        assertNull(lines.flush());
    }

    @Test
    void theOutputAfterTheLastNewlineComesOutWhenTheStreamEnds() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();

        assertEquals(List.of(), lines.append(bytes("no newline")));
        assertEquals("no newline", lines.flush());
    }

    @Test
    void aLongLineIsCutAtTheEventLimitWithoutSplittingACharacter() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();
        String head = "x".repeat(LambdaWebOutputLines.MAX_EVENT_BYTES - 1);

        List<String> events = lines.append(bytes(head + "é" + "rest\n"));

        assertEquals(List.of(head, "érest\n"), events);
    }

    @Test
    void aLineOfExactlyTheLimitIsOneEvent() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();
        String full = "y".repeat(LambdaWebOutputLines.MAX_EVENT_BYTES);

        assertEquals(List.of(full), lines.append(bytes(full + "z")));
        assertEquals("z", lines.flush());
    }

    @Test
    void aCharacterSplitAcrossPayloadsStaysWhole() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();
        byte[] word = bytes("日本\n");

        assertEquals(List.of(), lines.append(new byte[] {word[0], word[1]}));
        assertEquals(List.of("日本\n"), lines.append(Arrays.copyOfRange(word, 2, word.length)));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
