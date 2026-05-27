package nz.co.eroad.qaisystem.execution;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RepoContext helper tests")
class RepoContextTest {

    @Test @DisplayName("effectivePackage returns repo pkg when available")
    void effectivePkg_available() {
        assertThat(RepoContext.builder().contextAvailable(true).basePackage("com.ex").build()
            .effectivePackage("fb")).isEqualTo("com.ex");
    }

    @Test @DisplayName("effectivePackage returns fallback when unavailable")
    void effectivePkg_fallback() {
        assertThat(RepoContext.builder().contextAvailable(false).build()
            .effectivePackage("fb")).isEqualTo("fb");
    }

    @Test @DisplayName("extendsClause includes base class name")
    void extendsClause_set() {
        assertThat(RepoContext.builder().contextAvailable(true).baseTestClass("Base").build()
            .extendsClause()).isEqualTo(" extends Base");
    }

    @Test @DisplayName("extendsClause empty when no base class")
    void extendsClause_empty() {
        assertThat(RepoContext.builder().contextAvailable(true).build().extendsClause()).isEmpty();
    }

    @Test @DisplayName("commonImportsBlock contains import statements")
    void importsBlock() {
        String block = RepoContext.builder().contextAvailable(true)
            .commonImports(List.of("io.rest.RA", "org.junit.Test")).build().commonImportsBlock();
        assertThat(block).contains("import io.rest.RA;").contains("import org.junit.Test;");
    }

    @Test @DisplayName("hasAgentInstructions true when map non-empty")
    void hasAgent_true() {
        assertThat(RepoContext.builder().contextAvailable(true)
            .agentInstructions(Map.of("f.md","c")).build().hasAgentInstructions()).isTrue();
    }

    @Test @DisplayName("hasAgentInstructions false when empty map")
    void hasAgent_false() {
        assertThat(RepoContext.builder().contextAvailable(true)
            .agentInstructions(Map.of()).build().hasAgentInstructions()).isFalse();
    }

    @Test @DisplayName("contextHeader uses agent instructions")
    void contextHeader_agent() {
        String h = RepoContext.builder().contextAvailable(true)
            .agentInstructions(Map.of("api.md","Use REST Assured")).build().contextHeader();
        assertThat(h).contains("AGENT INSTRUCTIONS").contains("Use REST Assured");
    }

    @Test @DisplayName("contextHeader falls back to samplesComment")
    void contextHeader_samples() {
        String h = RepoContext.builder().contextAvailable(true)
            .sampleTests(Map.of("ExTest.java","// x")).build().contextHeader();
        assertThat(h).contains("ExTest.java").doesNotContain("AGENT INSTRUCTIONS");
    }
}
