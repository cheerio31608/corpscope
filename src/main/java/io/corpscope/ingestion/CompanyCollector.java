package io.corpscope.ingestion;

import io.corpscope.external.dart.DartGateway;
import io.corpscope.financial.FinancialPeriod;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CompanyCollector {
    private final DartGateway gateway;
    private final DartTransformer transformer;

    public CompanyCollector(DartGateway gateway, DartTransformer transformer) {
        this.gateway = gateway;
        this.transformer = transformer;
    }

    public CollectedCompany collect(String corpCode, FinancialPeriod period) {
        var company = gateway.company(corpCode);
        var companyData = transformer.company(company.data(), corpCode);
        var financial = gateway.financials(corpCode, period);
        boolean noData = "013".equals(financial.data().status());
        if (!noData && (!"000".equals(financial.data().status())
                || financial.data().list() == null || financial.data().list().isEmpty())) {
            throw new DataQualityException(DataQualityException.Code.RESPONSE_STRUCTURE, "Success requires financial rows");
        }
        var rows = noData ? List.<FinancialData>of() : transformer.financials(financial.data().list(), corpCode, period);
        var keys = new HashSet<String>();
        for (var row : rows) {
            if (!keys.add(row.statementDivision() + ":" + row.accountKey())) {
                // Do not silently overwrite ambiguous accounts; retain existing period on failure.
                throw new DataQualityException(DataQualityException.Code.DUPLICATE_ACCOUNT, "Duplicate financial account identity");
            }
        }
        return new CollectedCompany(companyData, period, rows,
                company.rawJson(), financial.rawJson(), noData, gateway.source());
    }

    public String source() { return gateway.source(); }
}
