package io.corpscope.batch;

import io.corpscope.external.dart.DartException;
import io.corpscope.ingestion.DataQualityException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BatchFailureTest {
    @Test void unwrapsQualityFailureAndDoesNotReportRawExceptionMessages() {
        var failure = new RuntimeException("https://example.invalid/?crtfc_key=private",
                new DataQualityException(DataQualityException.Code.AMOUNT_FORMAT, "private"));
        var classified = BatchFailure.from(failure);
        assertThat(classified.category()).isEqualTo("DATA_QUALITY_AMOUNT_FORMAT");
        assertThat(classified.summary()).doesNotContain("private", "crtfc_key");
    }

    @Test void distinguishesMissingCredentialsFromTransientOutages() {
        assertThat(BatchFailure.from(new DartException("Set a valid OPENDART_API_KEY before collecting real data")).category())
                .isEqualTo("API_KEY_REQUIRED");
        assertThat(BatchFailure.from(new DartException.Transient("timeout")).category()).isEqualTo("EXTERNAL_TRANSIENT");
        assertThat(BatchFailure.from(new DartException("quota")).category()).isEqualTo("EXTERNAL_REJECTED");
    }

    @Test void databaseFailureDoesNotExposeSqlDetails() {
        var result = BatchFailure.from(new org.springframework.dao.DataIntegrityViolationException("secret SQL detail"));
        assertThat(result.category()).isEqualTo("DATABASE");
        assertThat(result.summary()).doesNotContain("secret SQL detail");
    }
}
