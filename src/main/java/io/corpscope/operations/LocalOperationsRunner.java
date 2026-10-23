package io.corpscope.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.financial.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local CLI only: no controller or unauthenticated operations endpoint. */
@Component
@ConditionalOnProperty(name = "corpscope.operations.enabled", havingValue = "true")
public class LocalOperationsRunner implements ApplicationRunner, ExitCodeGenerator {
    private final BatchOperationsService batches;
    private final CollectionVerificationService collections;
    private final ObjectMapper mapper;
    private final PublicationCollectionService publications;
    private final QuarterlyPublicationCollectionService quarterlyPublications;
    private final SamsungQ3CoverageService samsungQ3Coverage;
    private final PeriodicFilingCoverageService periodicCoverage;
    private final PeriodicSourceExpansionService periodicSources;
    private final io.corpscope.financial.vintage.SamsungQ3AsOfService samsungQ3AsOf;
    private final FinancialReadinessService readiness;
    private final io.corpscope.financial.vintage.ReportVersionCollectionService reportVersions;
    private final String mode;
    private final long executionId;
    private final int limit;
    private final String corpCode;
    private final int year;
    private final String report;
    private final String statement;
    private final HistoricalCoverageService historicalCoverage;
    private final FinancialFeatureExportService featureExport;
    private final String asOf;
    private final String asOfDates;
    private final ReportDocumentCollectionService reportDocuments;
    private final String receiptNumbers;
    private final String receiptDate;
    private final io.corpscope.financial.vintage.SamsungReportReviewService samsungReviews;
    private final io.corpscope.financial.vintage.SamsungQ3ReviewService samsungQ3Reviews;
    private final io.corpscope.financial.vintage.ReviewedQ3ReportService reviewedQ3Reports;
    private final io.corpscope.financial.vintage.Reviewed2025EarlyPeriodService reviewedEarlyPeriods;
    private final io.corpscope.financial.vintage.ReviewedPlainPeriodService reviewedPlainPeriods;
    private final io.corpscope.financial.vintage.PeriodicAsOfService periodicAsOf;
    private final io.corpscope.financial.vintage.PeriodicReportedComparisonService periodicComparison;
    private final io.corpscope.financial.vintage.HynixReportReviewService hynixReviews;
    private final io.corpscope.financial.vintage.KepcoReportReviewService kepcoReviews;
    private final io.corpscope.financial.vintage.NaverReportReviewService naverReviews;
    private final io.corpscope.financial.vintage.LgesReportReviewService lgesReviews;
    private final io.corpscope.financial.vintage.HdhhiReportReviewService hdhhiReviews;
    private final io.corpscope.financial.vintage.SteelReportReviewService steelReviews;
    private final io.corpscope.financial.vintage.KiaReportReviewService kiaReviews;
    private final io.corpscope.financial.vintage.KogasReportReviewService kogasReviews;
    private final io.corpscope.financial.vintage.HyundaiMotorReportReviewService hyundaiMotorReviews;
    private final io.corpscope.financial.vintage.SteelAttachmentCorrectionReviewService steelAttachmentReviews;
    private final io.corpscope.financial.vintage.KogasSameDayReviewService kogasSameDayReviews;
    private final io.corpscope.dataset.DatasetReadinessService datasetReadiness;
    private final io.corpscope.dataset.TemporalDatasetBuilder datasetBuilder;
    private final String inputFile;
    private final io.corpscope.market.KrxArchiveImportService krxArchive;
    private final io.corpscope.market.KrxStagingAuditService krxAudit;
    private final io.corpscope.market.KrxReturnArithmeticAudit krxReturns;
    private final FinancialPanelExportService financialPanel;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private int exitCode;

    public LocalOperationsRunner(BatchOperationsService batches, CollectionVerificationService collections, ObjectMapper mapper,
            PublicationCollectionService publications, QuarterlyPublicationCollectionService quarterlyPublications,
            SamsungQ3CoverageService samsungQ3Coverage,
            PeriodicFilingCoverageService periodicCoverage,
            PeriodicSourceExpansionService periodicSources,
            io.corpscope.financial.vintage.SamsungQ3AsOfService samsungQ3AsOf,
            FinancialReadinessService readiness,
            io.corpscope.financial.vintage.ReportVersionCollectionService reportVersions,
            HistoricalCoverageService historicalCoverage,FinancialFeatureExportService featureExport,
            ReportDocumentCollectionService reportDocuments,
            io.corpscope.financial.vintage.SamsungReportReviewService samsungReviews,
            io.corpscope.financial.vintage.SamsungQ3ReviewService samsungQ3Reviews,
            io.corpscope.financial.vintage.ReviewedQ3ReportService reviewedQ3Reports,
            io.corpscope.financial.vintage.Reviewed2025EarlyPeriodService reviewedEarlyPeriods,
            io.corpscope.financial.vintage.ReviewedPlainPeriodService reviewedPlainPeriods,
            io.corpscope.financial.vintage.PeriodicAsOfService periodicAsOf,
            io.corpscope.financial.vintage.PeriodicReportedComparisonService periodicComparison,
            io.corpscope.financial.vintage.HynixReportReviewService hynixReviews,
            io.corpscope.financial.vintage.KepcoReportReviewService kepcoReviews,
            io.corpscope.financial.vintage.NaverReportReviewService naverReviews,
            io.corpscope.financial.vintage.LgesReportReviewService lgesReviews,
            io.corpscope.financial.vintage.HdhhiReportReviewService hdhhiReviews,
            io.corpscope.financial.vintage.SteelReportReviewService steelReviews,
            io.corpscope.financial.vintage.KiaReportReviewService kiaReviews,
            io.corpscope.financial.vintage.KogasReportReviewService kogasReviews,
            io.corpscope.financial.vintage.HyundaiMotorReportReviewService hyundaiMotorReviews,
            io.corpscope.financial.vintage.SteelAttachmentCorrectionReviewService steelAttachmentReviews,
            io.corpscope.financial.vintage.KogasSameDayReviewService kogasSameDayReviews,
            io.corpscope.dataset.DatasetReadinessService datasetReadiness,
            io.corpscope.dataset.TemporalDatasetBuilder datasetBuilder,
            io.corpscope.market.KrxArchiveImportService krxArchive,
            io.corpscope.market.KrxStagingAuditService krxAudit,
            io.corpscope.market.KrxReturnArithmeticAudit krxReturns,
            FinancialPanelExportService financialPanel,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            @Value("${corpscope.operations.input-file:}") String inputFile,
            @Value("${corpscope.operations.receipt-numbers:}") String receiptNumbers,
            @Value("${corpscope.operations.receipt-date:}") String receiptDate,
            @Value("${corpscope.operations.as-of:}") String asOf,
            @Value("${corpscope.operations.as-of-dates:}") String asOfDates,
            @Value("${corpscope.operations.mode:batch}") String mode,
            @Value("${corpscope.operations.execution-id:0}") long executionId,
            @Value("${corpscope.operations.limit:20}") int limit,
            @Value("${corpscope.operations.corp-code:}") String corpCode,
            @Value("${corpscope.operations.fiscal-year:2024}") int year,
            @Value("${corpscope.operations.report-type:ANNUAL}") String report,
            @Value("${corpscope.operations.statement-type:CFS}") String statement) {
        this.batches = batches;
        this.collections = collections;
        this.mapper = mapper;
        this.publications = publications;
        this.quarterlyPublications = quarterlyPublications;
        this.samsungQ3Coverage = samsungQ3Coverage;
        this.periodicCoverage = periodicCoverage;
        this.periodicSources = periodicSources;
        this.samsungQ3AsOf = samsungQ3AsOf;
        this.readiness = readiness;
        this.reportVersions = reportVersions;
        this.mode = mode;
        this.executionId = executionId;
        this.limit = limit;
        this.corpCode = corpCode;
        this.year = year;
        this.report = report;
        this.statement = statement;
        this.historicalCoverage=historicalCoverage;
        this.featureExport=featureExport;
        this.asOf=asOf;
        this.asOfDates=asOfDates;
        this.reportDocuments=reportDocuments;
        this.receiptNumbers=receiptNumbers;
        this.receiptDate=receiptDate;
        this.samsungReviews=samsungReviews;this.samsungQ3Reviews=samsungQ3Reviews;
        this.reviewedQ3Reports=reviewedQ3Reports;this.periodicAsOf=periodicAsOf;
        this.periodicComparison=periodicComparison;this.hynixReviews=hynixReviews;
        this.reviewedEarlyPeriods=reviewedEarlyPeriods;
        this.reviewedPlainPeriods=reviewedPlainPeriods;
        this.kepcoReviews=kepcoReviews;this.naverReviews=naverReviews;this.lgesReviews=lgesReviews;this.hdhhiReviews=hdhhiReviews;this.steelReviews=steelReviews;this.kiaReviews=kiaReviews;this.kogasReviews=kogasReviews;this.hyundaiMotorReviews=hyundaiMotorReviews;
        this.steelAttachmentReviews=steelAttachmentReviews;
        this.kogasSameDayReviews=kogasSameDayReviews;
        this.datasetReadiness=datasetReadiness;this.datasetBuilder=datasetBuilder;this.inputFile=inputFile;
        this.krxArchive=krxArchive;this.krxAudit=krxAudit;this.krxReturns=krxReturns;this.financialPanel=financialPanel;
        this.jdbc=jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Object result;
        try {
            if ("batch".equals(mode)) {
                result = executionId > 0 ? batches.execution(executionId) : batches.recent(limit);
            } else if ("verify".equals(mode)) {
                var verification = collections.verify(corpCode,
                        new FinancialPeriod(year, ReportType.valueOf(report), StatementType.valueOf(statement)));
                result = verification;
                if ("FAIL".equals(verification.status())) exitCode = 2;
            } else if ("publications".equals(mode)) {
                result = publications.collect(corpCode);
            } else if ("quarterly-publication".equals(mode)) {
                result = quarterlyPublications.collect(corpCode, receiptNumbers, java.time.LocalDate.parse(receiptDate));
            } else if ("samsung-q3-coverage".equals(mode)) {
                result = samsungQ3Coverage.collect(java.time.LocalDate.parse(asOf));
            } else if ("periodic-coverage".equals(mode)) {
                result = periodicCoverage.collect(corpCode,year,ReportType.valueOf(report),java.time.LocalDate.parse(asOf));
            } else if ("periodic-source-expansion".equals(mode)) {
                io.corpscope.operations.PeriodicSourceExpansionService.Request request;
                try {
                    var path=java.nio.file.Path.of(inputFile);
                    if(inputFile.isBlank() || java.nio.file.Files.size(path)>65536)
                        throw new IllegalArgumentException();
                    request=mapper.readerFor(io.corpscope.operations.PeriodicSourceExpansionService.Request.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(java.nio.file.Files.readString(path));
                } catch(java.io.IOException | IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Invalid bounded periodic source input");
                }
                result=periodicSources.collect(request);
            } else if ("samsung-q3-as-of".equals(mode)) {
                var ids=jdbc.queryForList("SELECT id FROM company WHERE corp_code=?",Long.class,corpCode);
                if(ids.size()!=1) throw new io.corpscope.common.NotFoundException("Company not found");
                result = samsungQ3AsOf.find(ids.getFirst(),java.time.LocalDate.parse(asOf));
            } else if ("periodic-as-of".equals(mode)) {
                var ids=jdbc.queryForList("SELECT id FROM company WHERE corp_code=?",Long.class,corpCode);
                if(ids.size()!=1) throw new io.corpscope.common.NotFoundException("Company not found");
                result = periodicAsOf.find(ids.getFirst(),year,ReportType.valueOf(report),
                    StatementType.valueOf(statement),java.time.LocalDate.parse(asOf));
            } else if ("periodic-reported-comparison".equals(mode)) {
                var ids=jdbc.queryForList("SELECT id FROM company WHERE corp_code=?",Long.class,corpCode);
                if(ids.size()!=1) throw new io.corpscope.common.NotFoundException("Company not found");
                result=periodicComparison.find(ids.getFirst(),java.time.LocalDate.parse(asOf));
            } else if ("readiness".equals(mode)) {
                result = readiness.review();
            } else if ("report-versions".equals(mode)) {
                result = reportVersions.collectPilot(StatementType.valueOf(statement));
            } else if ("historical-coverage".equals(mode)) {
                result = historicalCoverage.review(java.time.LocalDate.parse(asOf),limit,corpCode);
            } else if ("financial-features".equals(mode)) {
                result = featureExport.export(corpCode,year,StatementType.valueOf(statement),
                    java.util.Arrays.stream(asOfDates.split(",",-1)).map(String::trim).map(java.time.LocalDate::parse).toList());
            } else if ("financial-panel".equals(mode)) {
                FinancialPanelExportService.Request request;
                try {
                    var path=java.nio.file.Path.of(inputFile);
                    if(inputFile.isBlank() || java.nio.file.Files.size(path)>200000)
                        throw new IllegalArgumentException();
                    request=mapper.readerFor(FinancialPanelExportService.Request.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(java.nio.file.Files.readString(path));
                } catch(java.io.IOException | IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Invalid bounded financial panel input");
                }
                result=financialPanel.export(request);
            } else if ("report-documents".equals(mode)) {
                result = reportDocuments.collect(corpCode,
                    java.util.Arrays.stream(receiptNumbers.split(",",-1)).map(String::trim).toList());
            } else if ("review-samsung-versions".equals(mode)) {
                result=samsungReviews.review(args.containsOption("corpscope.operations.fiscal-year") ? year : 2025);
            } else if ("review-samsung-q3".equals(mode)) {
                result=samsungQ3Reviews.review();
            } else if ("review-2025-q3".equals(mode)) {
                result=reviewedQ3Reports.review(corpCode);
            } else if ("review-2025-early-period".equals(mode)) {
                result=reviewedEarlyPeriods.review(corpCode,report);
            } else if ("review-plain-period".equals(mode)) {
                result=reviewedPlainPeriods.review(corpCode,year,report);
            } else if ("review-hynix-versions".equals(mode)) {
                result=hynixReviews.review(args.containsOption("corpscope.operations.fiscal-year") ? year : 2025);
            } else if ("review-kepco-versions".equals(mode)) {
                result=kepcoReviews.review(args.containsOption("corpscope.operations.fiscal-year") ? year : 2025);
            } else if ("krx-price-import".equals(mode)) {
                result=krxArchive.importDirectory(inputFile);
            } else if ("krx-price-audit".equals(mode)) {
                io.corpscope.market.KrxStagingAuditService.Request request;
                try {
                    var path=java.nio.file.Path.of(inputFile);
                    if(inputFile.isBlank() || java.nio.file.Files.size(path)>65536) throw new IllegalArgumentException();
                    request=mapper.readerFor(io.corpscope.market.KrxStagingAuditService.Request.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(java.nio.file.Files.readString(path));
                } catch(java.io.IOException | IllegalArgumentException ex) {throw new IllegalArgumentException("Invalid bounded KRX audit input");}
                result=krxAudit.audit(request);
            } else if ("krx-return-arithmetic".equals(mode)) {
                io.corpscope.market.KrxReturnArithmeticAudit.Request request;
                try {
                    var path=java.nio.file.Path.of(inputFile);
                    if(inputFile.isBlank() || java.nio.file.Files.size(path)>65536) throw new IllegalArgumentException("Invalid audit input");
                    request=mapper.readerFor(io.corpscope.market.KrxReturnArithmeticAudit.Request.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(java.nio.file.Files.readString(path));
                } catch(java.io.IOException | IllegalArgumentException ex) {throw new IllegalArgumentException("Invalid arithmetic audit input");}
                result=krxReturns.review(request);
            } else if ("dataset-readiness".equals(mode)) {
                result=datasetReadiness.review(java.time.LocalDate.parse(asOf));
            } else if ("dataset-preview".equals(mode)) {
                io.corpscope.dataset.TemporalDatasetBuilder.Request request;
                try {
                    var path=java.nio.file.Path.of(inputFile);
                    if(inputFile.isBlank() || java.nio.file.Files.size(path)>10*1024*1024)
                        throw new IllegalArgumentException("Invalid preview input size");
                    request=mapper.readerFor(io.corpscope.dataset.TemporalDatasetBuilder.Request.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(java.nio.file.Files.readString(path));
                } catch(java.io.IOException | IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Invalid normalized price preview file");
                }
                result=datasetBuilder.build(request);
            } else if ("review-naver-versions".equals(mode)) {
                result=naverReviews.review(args.containsOption("corpscope.operations.fiscal-year") ? year : 2025);
            } else if ("review-lges-versions".equals(mode)) {
                result=lgesReviews.review();
            } else if ("review-hdhhi-versions".equals(mode)) {
                result=hdhhiReviews.review();
            } else if ("review-steel-versions".equals(mode)) {
                result=steelReviews.review();
            } else if ("review-kia-versions".equals(mode)) {
                result=kiaReviews.review();
            } else if ("review-kogas-versions".equals(mode)) {
                result=kogasReviews.review();
            } else if ("review-hyundai-motor-versions".equals(mode)) {
                result=hyundaiMotorReviews.review();
            } else if ("review-steel-attachments".equals(mode)) {
                result=steelAttachmentReviews.review(java.nio.file.Path.of(inputFile));
            } else if ("review-kogas-same-day".equals(mode)) {
                result=kogasSameDayReviews.review(java.nio.file.Path.of(inputFile));
            } else {
                throw new IllegalArgumentException("Unknown operations mode");
            }
        } catch (io.corpscope.common.NotFoundException ex) {
            exitCode = 2;
            result = Map.of("status", "FAIL", "error", "NOT_FOUND", "message", ex.getMessage());
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            exitCode = 2;
            result = Map.of("status", "FAIL", "error", "INVALID_INPUT", "message",
                ("review-samsung-q3".equals(mode) || "review-2025-q3".equals(mode) ||
                    "review-2025-early-period".equals(mode) ||
                    "review-plain-period".equals(mode) ||
                    "review-steel-attachments".equals(mode) || "review-kia-versions".equals(mode) ||
                    "review-kogas-versions".equals(mode) || "review-hyundai-motor-versions".equals(mode) ||
                    "review-kogas-same-day".equals(mode))
                    ? ex.getMessage() : "Check local command arguments");
        }
        System.out.println(mapper.writeValueAsString(result));
    }

    @Override public int getExitCode() { return exitCode; }
}
