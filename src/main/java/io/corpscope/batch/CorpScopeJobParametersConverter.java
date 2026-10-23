package io.corpscope.batch;

import java.util.Properties;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.converter.DefaultJobParametersConverter;
import org.springframework.stereotype.Component;

/** Keeps the CLI company list literal; other parameters retain Spring Batch's typed syntax. */
@Component
public class CorpScopeJobParametersConverter extends DefaultJobParametersConverter {
    @Override
    public JobParameters getJobParameters(Properties properties) {
        String codes = properties.getProperty("corpCodes");
        if (codes == null || !codes.matches("[0-9]{8}(,[0-9]{8})*")) {
            return super.getJobParameters(properties);
        }
        var remaining = new Properties();
        remaining.putAll(properties);
        remaining.remove("corpCodes");
        return new JobParametersBuilder(super.getJobParameters(remaining))
                .addString("corpCodes", codes, true).toJobParameters();
    }

    @Override
    public Properties getProperties(JobParameters parameters) {
        var properties = super.getProperties(parameters);
        var codes = parameters.getParameter("corpCodes");
        if (codes != null && codes.getType() == String.class && codes.isIdentifying()
                && ((String) codes.getValue()).matches("[0-9]{8}(,[0-9]{8})*")) {
            properties.setProperty("corpCodes", (String) codes.getValue());
        }
        return properties;
    }
}
