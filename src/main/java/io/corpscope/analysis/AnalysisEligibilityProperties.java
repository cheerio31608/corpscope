package io.corpscope.analysis;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit review decisions; removing an exclusion requires documented comparable inputs. */
@ConfigurationProperties("corpscope.analysis")
public record AnalysisEligibilityProperties(Map<String, String> excludedCompanyReasons,
                                           Map<String, String> excludedPeriodReasons) {
    /** Current review policy, not a financial feature available at a historical date. */
    public String exclusionReasonFor(String corpCode,int year,io.corpscope.financial.ReportType report,
            io.corpscope.financial.StatementType statement) {
        if(corpCode==null) return null;
        String company=excludedCompanyReasons.get(corpCode);
        return company!=null ? company:excludedPeriodReasons.get(corpCode+":"+year+":"+report+":"+statement);
    }
    public AnalysisEligibilityProperties {
        excludedCompanyReasons = excludedCompanyReasons == null ? Map.of() : Map.copyOf(excludedCompanyReasons);
        excludedCompanyReasons.forEach((code, reason) -> {
            if (!code.matches("[0-9]{8}") || reason.isBlank()) {
                throw new IllegalArgumentException("Analysis exclusions require a corp_code and review reason");
            }
        });
        excludedPeriodReasons = excludedPeriodReasons == null ? Map.of() : Map.copyOf(excludedPeriodReasons);
        excludedPeriodReasons.forEach((key, reason) -> {
            if (!key.matches("[0-9]{8}:[0-9]{4}:ANNUAL:(CFS|OFS)") || reason.isBlank()) {
                throw new IllegalArgumentException("Period exclusions require corp_code:year:ANNUAL:CFS/OFS and a reason");
            }
            int year = Integer.parseInt(key.split(":")[1]);
            if (year < 2015 || year > 2100) throw new IllegalArgumentException("Invalid excluded fiscal year");
        });
    }
}
