package io.corpscope.market;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Bounded manual source review. Dates, session times and complete action coverage are separate claims. */
@Component
public class KrxEvidenceReview {
    public record CalendarCheck(String profile, boolean datePatternReviewed, boolean observedDatesMatch,
        Integer expectedNonemptyDays, Integer expectedClosedDays, List<LocalDate> missingResponses,
        List<LocalDate> unexpectedEmptyDates, List<LocalDate> unexpectedNonemptyDates,
        boolean sessionTimesReviewed, String reason) {}
    public record ReviewedEvent(String stockCode, String type, LocalDate retiredOn, LocalDate listedOn,
        String receiptNumber, String source, String rawSha256, boolean matchesObservedSharesChange) {}
    public record SessionNotice(LocalDate tradingDate, LocalTime regularOpen, LocalTime regularClose,
        String zone, String source, String rawSha256) {}
    public record CashDividendNotice(String stockCode, LocalDate exDividendDate, OffsetDateTime announcedAt,
        String receiptNumber, String source, String rawSha256) {}
    public record MarketEvidence(String profile, List<SessionNotice> sessionNotices,
        List<CashDividendNotice> cashDividendNotices, boolean fullSessionCoverageReviewed,
        String currentProviderPublicationPolicy, boolean historicalPublicationTimesReviewed,
        boolean fullCorporateActionCoverageReviewed, boolean approvedForDataset) {}
    public record WindowCoverage(String basis, int observedDates, int missingTwoPriorDates,
        int missing63DateForwardWindow, int structurallyCoveredDates, List<LocalDate> candidateEntryDates,
        boolean approvedForLabels) {}

    private final JsonNode profile;
    private final JsonNode marketProfile;
    private final List<JsonNode> shareCancellations;
    private final LocalDate from, through;
    private final Set<LocalDate> closures = new HashSet<>();

    public KrxEvidenceReview(ObjectMapper mapper) {
        try (var input = getClass().getResourceAsStream("/market/krx-calendar-date-review-2025.json")) {
            if (input == null) throw new IllegalStateException("Missing KRX reviewed evidence");
            profile = mapper.readTree(input);
            from = LocalDate.parse(profile.required("from").asText());
            through = LocalDate.parse(profile.required("through").asText());
            profile.required("weekdayClosures").fieldNames().forEachRemaining(d -> closures.add(LocalDate.parse(d)));
            try (var legacy = getClass().getResourceAsStream("/market/krx-reviewed-evidence-202503-202507.json");
                 var additional = getClass().getResourceAsStream("/market/krx-naver-cancellation-202508.json")) {
                if (legacy == null || additional == null) throw new IllegalStateException("Missing cancellation evidence");
                shareCancellations = List.of(mapper.readTree(legacy).required("samsungCancellation"), mapper.readTree(additional));
            }
            try (var market = getClass().getResourceAsStream("/market/krx-market-evidence-20261015.json")) {
                if (market == null) throw new IllegalStateException("Missing partial market evidence");
                marketProfile = mapper.readTree(market);
            }
        } catch (IOException ex) { throw new IllegalStateException("Invalid KRX reviewed evidence", ex); }
    }

    /** Source-backed scheduled exceptions only; never fill other dates with assumed hours or availability. */
    public MarketEvidence market(LocalDate start, LocalDate end, Set<String> stockCodes) {
        List<SessionNotice> sessions = new ArrayList<>();
        for (var item : marketProfile.required("sessionNotices")) {
            var date = LocalDate.parse(item.required("tradingDate").asText());
            if (date.isBefore(start) || date.isAfter(end)) continue;
            sessions.add(new SessionNotice(date, LocalTime.parse(item.required("regularOpen").asText()),
                LocalTime.parse(item.required("regularClose").asText()), item.required("zone").asText(),
                item.required("source").asText(), item.required("rawSha256").asText()));
        }
        List<CashDividendNotice> dividends = new ArrayList<>();
        for (var item : marketProfile.required("cashDividendNotices")) {
            var date = LocalDate.parse(item.required("exDividendDate").asText());
            var code = item.required("stockCode").asText();
            if (date.isBefore(start) || date.isAfter(end) || !stockCodes.contains(code)) continue;
            dividends.add(new CashDividendNotice(code, date, OffsetDateTime.parse(item.required("announcedAt").asText()),
                item.required("receiptNumber").asText(), item.required("source").asText(), item.required("rawSha256").asText()));
        }
        // Administrative review, including later notices: do not consume this response as point-in-time model inputs.
        return new MarketEvidence(marketProfile.required("profile").asText(), List.copyOf(sessions), List.copyOf(dividends),
            false, marketProfile.required("providerPublication").required("currentPolicy").asText(), false, false, false);
    }

    public CalendarCheck calendar(LocalDate start, LocalDate end, Set<LocalDate> stored, Set<LocalDate> nonempty) {
        if (start.isBefore(from) || end.isAfter(through))
            return new CalendarCheck(profile.required("profile").asText(), false, false, null, null,
                List.of(), List.of(), List.of(), false, "OUTSIDE_REVIEWED_DATE_RANGE");
        List<LocalDate> missing = new ArrayList<>(), unexpectedEmpty = new ArrayList<>(), unexpectedNonempty = new ArrayList<>();
        int expectedOpen = 0, expectedClosed = 0;
        for (var day : start.datesUntil(end.plusDays(1)).toList()) {
            boolean open = day.getDayOfWeek().getValue() <= 5 && !closures.contains(day);
            if (open) expectedOpen++; else expectedClosed++;
            if (!stored.contains(day)) missing.add(day);
            else if (open && !nonempty.contains(day)) unexpectedEmpty.add(day);
            else if (!open && nonempty.contains(day)) unexpectedNonempty.add(day);
        }
        boolean matches = missing.isEmpty() && unexpectedEmpty.isEmpty() && unexpectedNonempty.isEmpty();
        return new CalendarCheck(profile.required("profile").asText(), true, matches, expectedOpen, expectedClosed,
            List.copyOf(missing), List.copyOf(unexpectedEmpty), List.copyOf(unexpectedNonempty), false,
            matches ? "DATE_PATTERN_MATCH_ONLY_SESSION_TIMES_UNVERIFIED" : "DATE_PATTERN_MISMATCH");
    }

    public List<ReviewedEvent> events(LocalDate start, LocalDate end, List<KrxStagingAuditService.Company> companies) {
        return shareCancellations.stream().flatMap(event -> {
            var listing = LocalDate.parse(event.required("listedOn").asText());
            if (listing.isBefore(start) || listing.isAfter(end)) return java.util.stream.Stream.<ReviewedEvent>empty();
            return companies.stream().filter(c -> c.stockCode().equals(event.required("stockCode").asText())).map(c -> {
                boolean matches = c.shareCountChanges().stream().anyMatch(change -> change.date().equals(listing)
                    && change.previousShares().compareTo(new BigDecimal(event.required("beforeShares").asText())) == 0
                    && change.shares().compareTo(new BigDecimal(event.required("afterShares").asText())) == 0);
                return new ReviewedEvent(c.stockCode(), "TREASURY_SHARE_CANCELLATION", LocalDate.parse(event.required("retiredOn").asText()),
                    listing, event.required("receiptNumber").asText(), event.required("source").asText(),
                    event.required("rawSha256").asText(), matches);
            });
        }).toList();
    }

    /** Counts date positions only. Does not invent session times, price availability or numeric returns. */
    public WindowCoverage windows(Set<LocalDate> nonempty) {
        var dates = nonempty.stream().sorted().toList();
        int missingPrior = 0, missingForward = 0;
        List<LocalDate> candidates = new ArrayList<>();
        for (int index = 0; index < dates.size(); index++) {
            if (index < 2) missingPrior++;
            else if (index + 62 >= dates.size()) missingForward++;
            else candidates.add(dates.get(index));
        }
        return new WindowCoverage("OBSERVED_RESPONSE_DATES_ONLY_NOT_PUBLISHED_PRICE_OR_VERIFIED_SESSIONS",
            dates.size(), missingPrior, missingForward, candidates.size(), List.copyOf(candidates), false);
    }
}
