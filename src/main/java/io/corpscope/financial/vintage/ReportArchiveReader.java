package io.corpscope.financial.vintage;

import io.corpscope.ingestion.DataQualityException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/** Shared bounded decoding for explicitly reviewed report parsers; never extracts files or loads resources. */
final class ReportArchiveReader {
    record Source(String mainEntry,String encoding,String text) {}
    static Source read(String receipt,byte[] zip) {
        return read(receipt,zip,12*1024*1024);
    }
    /** KEPCO's reviewed 2025 main entry is 24,017,956 bytes; opt-in keeps older profiles at 12 MiB. */
    static Source read(String receipt,byte[] zip,int maxEntryBytes) {
        if(maxEntryBytes<1 || maxEntryBytes>24*1024*1024) throw invalid("Unsupported entry limit");
        if (zip == null || zip.length > 20*1024*1024) throw invalid("Invalid report size");
        String entryName = receipt + ".xml";
        byte[] main = null;
        int count=0, expanded=0;
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry=in.getNextEntry(); entry!=null; entry=in.getNextEntry()) {
                if (++count > 20 || entry.isDirectory() || entry.getName().contains("/") || entry.getName().contains("\\")) {
                    throw invalid("Unsupported report ZIP layout");
                }
                byte[] bytes=in.readNBytes(maxEntryBytes+1);
                expanded+=bytes.length;
                if (bytes.length > maxEntryBytes || expanded > 30*1024*1024) throw invalid("Expanded report too large");
                if (entryName.equals(entry.getName())) {
                    if (main!=null) throw invalid("Duplicate main report entry");
                    main=bytes;
                }
            }
        } catch (IOException ex) { throw invalid("Cannot read official report ZIP"); }
        if (main==null) throw invalid("Main report entry missing");
        String encoding="UTF-8", text;
        try { text=decode(main,StandardCharsets.UTF_8); }
        catch (CharacterCodingException ex) {
            encoding="MS949";
            try { text=decode(main,Charset.forName("MS949")); }
            catch (CharacterCodingException bad) { throw invalid("Unsupported report encoding"); }
        }
        if (text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") || text.toUpperCase(Locale.ROOT).contains("<!ENTITY")) {
            throw invalid("Report declarations are unsupported");
        }
        return new Source(entryName,encoding,text);
    }
    private static DataQualityException invalid(String message) {
        return new DataQualityException(DataQualityException.Code.RESPONSE_STRUCTURE,message);
    }
    private static String decode(byte[] bytes,Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
