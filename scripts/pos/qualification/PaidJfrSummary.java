import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import jdk.jfr.consumer.RecordingFile;

/** Report only fixed aggregate labels. The recording stays on the runner. */
final class PaidJfrSummary {
    private enum Area {
        SQLite, RetainedFrames, StockProof, Catalogue, Crypto, NativeAnchor,
        BusinessPayment, ColdReplay, Serialization, Other
    }

    private static Area area(String name) {
        if (name.startsWith("androidx.sqlite.") || name.contains("AndroidSelfOwnedRoomDriver")) return Area.SQLite;
        if (name.contains("RetainedOriginalReader") || name.contains("RetainedCommandFramesV2") ||
                name.contains("StockFrameStreamDecoderV2")) return Area.RetainedFrames;
        if (name.contains("LocalStockRelational") || name.contains("StockOriginalTraversal") ||
                name.contains("StockReadProducerProof")) return Area.StockProof;
        if (name.contains("LocalCatalogUnitAuthority") || name.contains("LocalCatalogPublication") ||
                name.contains("LocalCompanyBusinessPublicationCore")) return Area.Catalogue;
        if (name.startsWith("sun.security.") || name.startsWith("java.security.") ||
                name.startsWith("javax.crypto.") || name.startsWith("org.bouncycastle.")) return Area.Crypto;
        if (name.contains("SelfOwnedHistoryAnchor") || name.contains("SelfOwnedNativeHistoryWire") ||
                name.contains("NativeHistoryFinalDecisionCapacity") || name.contains("NativeCompanyBusinessPublicationObservation")) {
            return Area.NativeAnchor;
        }
        if (name.contains("NormalizedPaymentTraversal") || name.contains("LocalFinancialEvidenceV3")) return Area.BusinessPayment;
        if (name.contains("RuntimeHistoryScanner") || name.contains("RuntimeHistoryCold") ||
                name.contains("RuntimeBusinessHistoryJoin")) return Area.ColdReplay;
        if (name.startsWith("kotlinx.serialization.")) return Area.Serialization;
        return Area.Other;
    }

    public static void main(String[] arguments) throws Exception {
        var inclusive = new EnumMap<Area, Integer>(Area.class);
        var innermost = new EnumMap<Area, Integer>(Area.class);
        int executionEvents = 0;
        int nativeEvents = 0;
        int applicationEvents = 0;
        try (var recording = new RecordingFile(Path.of(arguments[0]))) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                switch (event.getEventType().getName()) {
                    case "jdk.ExecutionSample" -> executionEvents++;
                    case "jdk.NativeMethodSample" -> nativeEvents++;
                    default -> { continue; }
                }
                var trace = event.getStackTrace();
                if (trace == null) continue;
                var encountered = EnumSet.noneOf(Area.class);
                Area first = Area.Other;
                boolean application = false;
                for (var frame : trace.getFrames()) {
                    var name = frame.getMethod().getType().getName();
                    application |= name.startsWith("com.ukkera.pos.");
                    var area = area(name);
                    if (area != Area.Other) {
                        if (first == Area.Other) first = area;
                        encountered.add(area);
                    }
                }
                if (!application) continue;
                applicationEvents++;
                for (var area : encountered) inclusive.merge(area, 1, Integer::sum);
                innermost.merge(first, 1, Integer::sum);
            }
        }
        System.out.printf("execution_events\t%d%nnative_events\t%d%napplication_events\t%d%n",
                executionEvents, nativeEvents, applicationEvents);
        System.out.println("area\tinclusive_samples\tinnermost_classified_samples");
        for (var area : Area.values()) {
            System.out.printf("%s\t%d\t%d%n", area, inclusive.getOrDefault(area, 0), innermost.getOrDefault(area, 0));
        }
    }
}
