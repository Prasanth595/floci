package io.github.hectorvent.floci.services.dsql.dataplane;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DsqlSessionTest {

    @Test
    void startupOptionsCountOnlyTheirFirstDashCSettingForEachName() {
        Map<String, String> settings = DsqlSession.optionSettings(
                "--dsql.iam_session_user=spoofed -c search_path=first -csearch_path=x  -c Search_Path=second"
                        + " -c TimeZone=UTC -B 100 garbage -c application_name -c");

        assertEquals(Map.of("search_path", "first", "TimeZone", "UTC"), settings);
        assertEquals(List.of("search_path", "TimeZone"), List.copyOf(settings.keySet()));
        assertEquals(Map.of(), DsqlSession.optionSettings(null));
        assertEquals(Map.of(), DsqlSession.optionSettings("   "));
    }

    @Test
    void startupOptionsTakeNoEscapesAndReachTheDatabaseEscaped() {
        Map<String, String> settings = DsqlSession.optionSettings("-c search_path=a\\ b");

        assertEquals(Map.of("search_path", "a\\"), settings);
        assertEquals("-c search_path=a\\\\", DsqlSession.backendOptions(settings));
    }

    @Test
    void aCustomParameterInTheStartupIsTheOneRefused() {
        assertEquals("myapp.foo", DsqlSession.customParameter(Map.of("myapp.foo", "x"), Map.of("user", "admin")));
        assertEquals("dsql.iam_session_user", DsqlSession.customParameter(Map.of(),
                Map.of("user", "admin", "dsql.iam_session_user", "spoofed")));
        assertEquals(null, DsqlSession.customParameter(Map.of("search_path", "public"),
                Map.of("user", "admin", "options", "-c search_path=public")));
    }
}
