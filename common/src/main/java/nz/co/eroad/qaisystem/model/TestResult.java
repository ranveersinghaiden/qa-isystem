package nz.co.eroad.qaisystem.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResult {

    private String resultId;
    private String scriptId;
    private String prId;
    private boolean passed;
    private int attemptNumber;
    private long executionTimeMs;
    private String output;
    private String errorMessage;
    private String stackTrace;
    private List<String> failureReasons;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime executedAt;

    private boolean stabilized;
    private String finalScriptContent;
    private String prUrl;
}