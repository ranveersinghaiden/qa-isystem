package nz.co.eroad.qaisystem.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import nz.co.eroad.qaisystem.model.PullRequest;
import nz.co.eroad.qaisystem.service.PRService;
import nz.co.eroad.qaisystem.service.PrContextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller layer tests using standalone MockMvc and a real PRService test double (no Mockito).
 */
@DisplayName("PRController web layer tests")
class PRControllerTest {

    /** Fixed-return PRService that never calls Kafka. */
    static class FixedPRService extends PRService {
        private final PullRequest response;
        FixedPRService(PullRequest response) { super(null, null, new PrContextExtractor()); this.response = response; }
        @Override public PullRequest processPullRequest(PullRequest pr) { return response; }
        @Override public PullRequest createSamplePullRequest() { return response; }
    }

    MockMvc      mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        PullRequest pr = PullRequest.builder()
                .prId("PR-001").title("feat: test").author("a@b.com")
                .repositoryName("repo").targetBranch("main")
                .status(PullRequest.PrStatus.OPEN).diffs(new ArrayList<>())
                .build();

        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new PRController(new FixedPRService(pr)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private PullRequest pr() {
        return PullRequest.builder()
                .prId("PR-001").title("feat: test").author("a@b.com")
                .repositoryName("repo").targetBranch("main")
                .status(PullRequest.PrStatus.OPEN).diffs(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("GET /api/pr/health returns 200 UP")
    void health() throws Exception {
        mockMvc.perform(get("/api/pr/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("POST /api/pr/webhook returns 202 ACCEPTED")
    void webhook() throws Exception {
        mockMvc.perform(post("/api/pr/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pr())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.prId").value("PR-001"));
    }

    @Test
    @DisplayName("POST /api/pr/submit returns 201 QUEUED")
    void submit() throws Exception {
        mockMvc.perform(post("/api/pr/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pr())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("POST /api/pr/demo returns 202 DEMO_TRIGGERED")
    void demo() throws Exception {
        mockMvc.perform(post("/api/pr/demo"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DEMO_TRIGGERED"));
    }
}
