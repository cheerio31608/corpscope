package io.corpscope.batch;

import java.util.Arrays;
import java.util.List;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;

/** Small explicit target list initially; replace with a keyset DB reader for full coverage. */
public class CorpCodeReader implements ItemStreamReader<String> {
    private static final String CHECKPOINT = "corpCodeReader.index";
    private final List<String> codes;
    private int index;

    public CorpCodeReader(String corpCodes) {
        if (corpCodes == null || corpCodes.isBlank()) throw new IllegalArgumentException("corpCodes is required");
        codes = Arrays.stream(corpCodes.split(",", -1)).map(String::trim).distinct().sorted().toList();
        if (codes.size() > 100 || codes.stream().anyMatch(code -> !code.matches("[0-9]{8}"))) {
            throw new IllegalArgumentException("Provide 1..100 eight-digit corpCodes");
        }
    }

    @Override public String read() { return index < codes.size() ? codes.get(index++) : null; }
    @Override public void open(ExecutionContext context) { index = context.getInt(CHECKPOINT, 0); }
    @Override public void update(ExecutionContext context) { context.putInt(CHECKPOINT, index); }
    @Override public void close() {}
}
