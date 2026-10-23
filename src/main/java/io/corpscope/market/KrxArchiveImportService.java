package io.corpscope.market;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;

/** File/network work is outside transactions; each validated daily response commits atomically. */
@Service
public class KrxArchiveImportService {
    public record Result(int requestedSnapshots,int completedSnapshots,int insertedSnapshots,long processedQuotes,
        String priceBasis,boolean forecastReady) {}
    private final ObjectMapper mapper;private final KrxSnapshotParser parser;private final KrxStagingStore store;
    public KrxArchiveImportService(ObjectMapper mapper,KrxSnapshotParser parser,KrxStagingStore store) {
        this.mapper=mapper;this.parser=parser;this.store=store;
    }
    public Result importDirectory(String directory) {
        Path root;List<Path> files;
        try {
            root=Path.of(directory).toRealPath();
            try(var stream=Files.list(root)) {files=stream.filter(p -> p.getFileName().toString().endsWith(".metadata.json")).sorted().toList();}
        } catch(Exception ex) {throw new IllegalArgumentException("Cannot read KRX archive directory");}
        if(files.isEmpty() || files.size()>732) throw new IllegalArgumentException("Use 1..732 daily archive metadata files");
        int completed=0,inserted=0;long quotes=0;
        for(var file:files) {
            JsonNode meta;byte[] bytes;String api,hash;LocalDate day;Instant collected;
            try {
                if(!file.toRealPath().startsWith(root) || Files.size(file)>65536) throw new IllegalArgumentException();
                meta=mapper.readTree(Files.readString(file));api=meta.path("api").asText();hash=meta.path("sha256").asText();
                String date=meta.path("requestedDate").asText();
                if(!"KRX".equals(meta.path("source").asText()) || !Set.of("stk_bydd_trd","ksq_bydd_trd").contains(api) ||
                    !date.matches("[0-9]{8}") || !hash.matches("[a-f0-9]{64}") ||
                    !"UNVERIFIED_RAW_API_PRICE".equals(meta.path("priceBasis").asText()) ||
                    !meta.path("officialPublicationAt").isNull() || !isFalse(meta,"calendarVerified") || !isFalse(meta,"corporateActionsVerified") ||
                    !isFalse(meta,"forecastReady") || !meta.path("rows").isIntegralNumber()) throw new IllegalArgumentException();
                String name=api+"-"+date+"-"+hash+".json";
                if(!meta.path("file").asText().equals(name) || !file.getFileName().toString().equals(name+".metadata.json")) throw new IllegalArgumentException();
                var raw=root.resolve(name).toRealPath();
                if(!raw.startsWith(root) || Files.size(raw)>5*1024*1024) throw new IllegalArgumentException();
                bytes=Files.readAllBytes(raw);day=LocalDate.parse(date,DateTimeFormatter.BASIC_ISO_DATE);
                collected=OffsetDateTime.parse(meta.path("fetchedAt").asText()).toInstant();
                if(!hash.equals(KrxStagingStore.hash(bytes)) || parser.parse(api,day,bytes).size()!=meta.path("rows").intValue()) throw new IllegalArgumentException();
            } catch(Exception ex) {throw new IllegalArgumentException("KRX archive provenance/bytes failed validation; stored days remain restartable");}
            var result=store.save(api,day,hash,collected,bytes);
            completed++;quotes+=result.rows();if(result.inserted()) inserted++;
        }
        return new Result(files.size(),completed,inserted,quotes,"UNADJUSTED",false);
    }
    private static boolean isFalse(JsonNode root,String name) {return root.path(name).isBoolean() && !root.path(name).booleanValue();}
}
