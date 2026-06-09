package nz.co.eroad.qaisystem.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestScript {

    private String scriptId;
    private String bddScenarioId;
    private String prId;
    /** Original title of the source pull request — used as the GitHub test PR title. */
    private String prTitle;
    private TestType testType;
    private String scriptContent;
    private String fileName;
    private String targetPackage;
    private List<String> dependencies;
    private ScriptStatus status;
    private int retryCount;
    private List<String> executionErrors;

    public enum TestType {
        API, UI, MOBILE
    }

    public enum ScriptStatus {
        GENERATED, EXECUTING, PASSED, FAILED,
        STABILIZED, ABANDONED, PENDING_REVIEW
    }
}