package nz.co.eroad.qaisystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.model.PullRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@code products} field on {@link PullRequest} accepts both
 * a JSON array and a comma-separated string — handled by
 * {@link nz.co.eroad.qaisystem.model.CommaSeparatedListDeserializer}.
 */
@DisplayName("PullRequest.products deserialization")
class ProductsFieldDeserializerTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("accepts JSON array for products")
    void acceptsJsonArray() throws Exception {
        String json = """
                {"title":"t","author":"a","repositoryName":"r","products":["payments","auth"]}
                """;
        PullRequest pr = mapper.readValue(json, PullRequest.class);
        assertThat(pr.getProducts()).containsExactly("payments", "auth");
    }

    @Test
    @DisplayName("accepts comma-separated string for products")
    void acceptsCommaSeparatedString() throws Exception {
        String json = """
                {"title":"t","author":"a","repositoryName":"r","products":"payments, auth, reporting"}
                """;
        PullRequest pr = mapper.readValue(json, PullRequest.class);
        assertThat(pr.getProducts()).containsExactly("payments", "auth", "reporting");
    }

    @Test
    @DisplayName("trims whitespace from comma-separated products")
    void trimsWhitespace() throws Exception {
        String json = """
                {"title":"t","author":"a","repositoryName":"r","products":"  fleet ,  dashcam  "}
                """;
        PullRequest pr = mapper.readValue(json, PullRequest.class);
        assertThat(pr.getProducts()).containsExactly("fleet", "dashcam");
    }

    @Test
    @DisplayName("null products field deserializes to null")
    void nullProducts() throws Exception {
        String json = """
                {"title":"t","author":"a","repositoryName":"r"}
                """;
        PullRequest pr = mapper.readValue(json, PullRequest.class);
        assertThat(pr.getProducts()).isNull();
    }

    @Test
    @DisplayName("empty string products deserializes to empty list")
    void emptyStringProducts() throws Exception {
        String json = """
                {"title":"t","author":"a","repositoryName":"r","products":""}
                """;
        PullRequest pr = mapper.readValue(json, PullRequest.class);
        assertThat(pr.getProducts()).isEmpty();
    }

    @Test
    @DisplayName("single product as plain string")
    void singleProductString() throws Exception {
        String json = """
                {"title":"t","author":"a","repositoryName":"r","products":"myeroad"}
                """;
        PullRequest pr = mapper.readValue(json, PullRequest.class);
        assertThat(pr.getProducts()).containsExactly("myeroad");
    }

    @Test
    @DisplayName("single product as single-element JSON array")
    void singleProductArray() throws Exception {
        String json = """
                {"title":"t","author":"a","repositoryName":"r","products":["myeroad"]}
                """;
        PullRequest pr = mapper.readValue(json, PullRequest.class);
        assertThat(pr.getProducts()).containsExactly("myeroad");
    }
}

