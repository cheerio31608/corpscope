package io.corpscope.batch;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CorpScopeJobParametersConverterTest {
    private final CorpScopeJobParametersConverter converter = new CorpScopeJobParametersConverter();

    @Test void multipleCompaniesPreserveZerosAndTypedYearWithoutMutatingInput() {
        var input = new Properties();
        input.setProperty("corpCodes", "00164779,00164742,01204056");
        input.setProperty("fiscalYear", "2024,java.lang.Long");
        input.setProperty("requestId", "multi-company-test");
        var parameters = converter.getJobParameters(input);
        assertThat(parameters.getString("corpCodes")).isEqualTo("00164779,00164742,01204056");
        assertThat(parameters.getLong("fiscalYear")).isEqualTo(2024L);
        assertThat(parameters.getParameter("corpCodes").isIdentifying()).isTrue();
        assertThat(input).containsEntry("corpCodes", "00164779,00164742,01204056");
        assertThat(converter.getJobParameters(converter.getProperties(parameters))).isEqualTo(parameters);
    }

    @Test void singleCompanyParametersRemainCompatibleWithExistingInstances() {
        var input = new Properties();
        input.setProperty("corpCodes", "00126380");
        input.setProperty("fiscalYear", "2024,java.lang.Long");
        assertThat(converter.getJobParameters(input))
                .isEqualTo(new org.springframework.batch.core.converter.DefaultJobParametersConverter().getJobParameters(input));
    }
}
