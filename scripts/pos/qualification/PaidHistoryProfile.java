import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/** Print only declared category counts. Raw JFR stays on the ephemeral runner. */
class PaidHistoryProfile {
    public static void main(String[] args) throws Exception {
        var counts = new TreeMap<String, Integer>();
        try (var samples = new RecordingFile(Path.of(args[0]))) {
            while (samples.hasMoreEvents()) {
                var event = samples.readEvent();
                var type = event.getEventType().getName();
                if ((type.equals("jdk.ExecutionSample") || type.equals("jdk.NativeMethodSample"))
                        && event.getStackTrace() != null) {
                    counts.merge(category(event.getStackTrace().getFrames()), 1, Integer::sum);
                }
            }
        }
        System.out.println("category\tsamples");
        for (Map.Entry<String, Integer> row : counts.entrySet()) {
            System.out.printf("%s\t%d%n", row.getKey(), row.getValue());
        }
    }

    private static String category(List<RecordedFrame> frames) {
        for (var frame : frames) {
            var method = frame.getMethod();
            var name = method.getType().getName();
            if (name.startsWith("androidx.sqlite.") || name.contains("AndroidOwnedRoomCoroutines")
                    || name.contains("AndroidSelfOwnedRoomDriver")) return "SQLite_and_owned_statement_calls";
            if (name.startsWith("java.security.") || name.startsWith("sun.security.")
                    || name.startsWith("org.bouncycastle.")) return "cryptography";
            if (name.contains("RetainedOriginalReader") || name.contains("StockFrameStreamDecoderV2"))
                return "retained_original_reads";
            if (name.contains("StockReadProducerProof") || name.contains("StockOriginalTraversal")
                    || name.contains("LocalStockRelationalInspection")) return "stock_proof";
            if (name.contains("CatalogUnitAuthority") || name.contains("CatalogPublication")) return "catalogue";
            if (name.contains("RuntimeHistoryScanner") || name.contains("RuntimeBusinessHistoryJoin")) return "history_replay";
            if (name.contains("Journal") && name.startsWith("com.ukkera.pos.")) return "journal";
            if (name.contains("NormalizedPaymentTraversal")) return "payment_proof";
        }
        return "other_or_unclassified";
    }
}
