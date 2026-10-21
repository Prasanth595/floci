package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CodeBuildListProjectsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeBuildService service = new CodeBuildService(
            mock(CodeBuildRunner.class), mock(EmulatorConfig.class), null);
    private final CodeBuildJsonHandler handler = new CodeBuildJsonHandler(service, mapper);

    @ParameterizedTest
    @CsvSource({
            "NAME, ASCENDING, alpha:mu:zeta",
            "NAME, DESCENDING, zeta:mu:alpha",
            "CREATED_TIME, ASCENDING, zeta:alpha:mu",
            "CREATED_TIME, DESCENDING, mu:alpha:zeta",
            "LAST_MODIFIED_TIME, ASCENDING, mu:zeta:alpha",
            "LAST_MODIFIED_TIME, DESCENDING, alpha:zeta:mu"
    })
    void sortsByEveryRequestedCriterionAndDirection(String sortBy, String sortOrder, String expected)
            throws Exception {
        createProject(REGION, "zeta", 1.0, 2.0);
        createProject(REGION, "alpha", 2.0, 3.0);
        createProject(REGION, "mu", 3.0, 1.0);
        createProject("eu-west-1", "other-region", 0.0, 0.0);

        assertEquals(List.of(expected.split(":")), list(Map.of("sortBy", sortBy, "sortOrder", sortOrder)));
    }

    @ParameterizedTest
    @CsvSource({"sortBy, UNKNOWN", "sortBy, name", "sortBy, ''", "sortOrder, UNKNOWN", "sortOrder, ascending"})
    void rejectsInvalidSortOptionsEvenWhenThereAreNoProjects(String parameter, String value) {
        AwsException error = assertThrows(AwsException.class, () -> list(Map.of(parameter, value)));
        assertEquals("InvalidInputException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("Invalid " + parameter + ": " + value, error.getMessage());
    }

    @Test
    void retainsUnspecifiedOrderAndHandlesOneSortOption() throws Exception {
        createProject(REGION, "zeta", 1.0, 2.0);
        createProject(REGION, "alpha", 2.0, 3.0);
        assertEquals(service.listProjects(REGION), list(Map.of()));
        assertEquals(List.of("alpha", "zeta"), list(Map.of("sortBy", "NAME")));
        assertEquals(List.of("alpha", "zeta"), list(Map.of("sortOrder", "ASCENDING")));
        assertEquals(List.of("zeta", "alpha"), list(Map.of("sortOrder", "DESCENDING")));
    }

    @Test
    void sortsEmptyLists() throws Exception {
        assertEquals(List.of(), list(Map.of("sortBy", "CREATED_TIME", "sortOrder", "DESCENDING")));
    }

    @ParameterizedTest
    @CsvSource({"CREATED_TIME", "LAST_MODIFIED_TIME"})
    void toleratesMissingTimestampsInStoredProjects(String sortBy) throws Exception {
        createProject(REGION, "zeta", 1.0, 1.0);
        createProject(REGION, "alpha", null, null);
        assertEquals(List.of("alpha", "zeta"), list(Map.of("sortBy", sortBy, "sortOrder", "ASCENDING")));
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Map<String, String> request) throws Exception {
        try (Response response = handler.handle("ListProjects", mapper.valueToTree(request), REGION, ACCOUNT)) {
            assertEquals(200, response.getStatus());
            return (List<String>) ((Map<?, ?>) response.getEntity()).get("projects");
        }
    }

    private void createProject(String region, String name, Double created, Double modified) {
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");
        Project project = service.createProject(region, ACCOUNT, name, null, source, null, null,
                artifacts, null, new ProjectEnvironment(), "arn:aws:iam::000000000000:role/codebuild",
                null, null, null, null, null, null, null);
        project.setCreated(created);
        project.setLastModified(modified);
    }
}
