import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Registry metadata only. Never calls a handler, authorizer, native factory or history join. */
public final class RegistryMetadataProbe {
    private static Object call(Object owner, String method) throws ReflectiveOperationException {
        Method selected = owner.getClass().getMethod(method);
        selected.setAccessible(true);
        return selected.invoke(owner);
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field selected = owner.getClass().getDeclaredField(name);
        selected.setAccessible(true);
        return selected.get(owner);
    }
    private static Object singleton(String name) throws ReflectiveOperationException {
        return Class.forName(name).getField("INSTANCE").get(null);
    }
    private static String key(Object value) throws ReflectiveOperationException {
        return call(value, "getName") + "/v" + call(value, "getVersion");
    }
    private static List<String> strings(Object values) {
        List<String> result = new ArrayList<>();
        for (Object value : (Iterable<?>)values) result.add(value.toString());
        Collections.sort(result);
        return result;
    }
    private static List<String> keys(Object values) throws ReflectiveOperationException {
        List<String> result = new ArrayList<>();
        for (Object value : (Iterable<?>)values) result.add(key(value));
        Collections.sort(result);
        return result;
    }
    private static Map<String,Object> producer(Object value, String dispatch) throws ReflectiveOperationException {
        Map<String,Object> result = new TreeMap<>();
        result.put("class", value.getClass().getName());
        result.put("commands", keys(call(value, "getCommands")));
        result.put("dispatch", dispatch);
        return result;
    }
    private static List<String> rpcSelectors(Object route) throws ReflectiveOperationException {
        List<String> selectors = new ArrayList<>();
        for (String name : List.of("InvoiceMatching", "InvoiceCapture", "Report", "StockTransfer",
                "Kitchen", "RetailOrder", "PaymentCorrection", "CustomerReceipt", "FoodCheck",
                "FoodCashFinancial", "FoodTableAssignment")) {
            Object selector = singleton("com.ukkera.pos.database.LocalMaster" + name + "RpcKeysV1");
            Method accepts = selector.getClass().getMethod("accepts", route.getClass());
            if ((Boolean)accepts.invoke(selector, route)) selectors.add(selector.getClass().getSimpleName());
        }
        return selectors;
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            StringBuilder result = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch(c) {
                    case '"' -> result.append("\\\"");
                    case '\\' -> result.append("\\\\");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    default -> { if (c < 32) result.append(String.format("\\u%04x", (int)c)); else result.append(c); }
                }
            }
            return result.append('"').toString();
        }
        if (value instanceof Map<?,?> map) {
            List<String> entries = new ArrayList<>();
            for (var entry : map.entrySet()) entries.add(json(entry.getKey().toString()) + ":" + json(entry.getValue()));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> list) {
            List<String> entries = new ArrayList<>();
            for (Object entry : list) entries.add(json(entry));
            return "[" + String.join(",", entries) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("Unsupported metadata type " + value.getClass());
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("new output JSON path and inspected rejection-profile class list required");
        Class<?> type = Class.forName("com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry");
        Object registry = null;
        for (Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() == 10) {
                // The four required collaborators are explicit unavailable sources. The mask
                // selects the production constructor's actual defaults for remaining fields.
                registry = constructor.newInstance(
                    singleton("com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier"),
                    singleton("com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy"),
                    singleton("com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity"),
                    singleton("com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2"),
                    null, null, null, null, 240, null);
            }
        }
        if (registry == null) throw new IllegalStateException("Registry constructor changed: inspect before updating probe");
        List<Map<String,Object>> history = new ArrayList<>();
        for (Object source : (Iterable<?>)field(registry, "historyProducers")) history.add(producer(source, "command-key"));
        history.add(producer(field(registry, "supervisorReviewHistory"), "decoded-review-subtype"));
        history.sort(Comparator.comparing(x -> x.get("class").toString()));
        Map<String,List<String>> byCommand = new TreeMap<>();
        for (Map<String,Object> source : history) {
            for (Object command : (Iterable<?>)source.get("commands"))
                byCommand.computeIfAbsent(command.toString(), ignored -> new ArrayList<>()).add(source.get("class").toString());
        }
        List<Map<String,Object>> commands = new ArrayList<>();
        for (Object handler : (Iterable<?>)call(registry, "getCommands")) {
            Map<String,Object> row = new TreeMap<>();
            String route = key(call(handler, "getKey"));
            row.put("key", route); row.put("handler_class", handler.getClass().getName());
            row.put("rpc_key_selectors", rpcSelectors(call(handler, "getKey")));
            row.put("writable_kinds", strings(call(handler, "getWritableKinds")));
            row.put("claim_namespaces", strings(call(handler, "getClaimNamespaces")));
            row.put("physical_recording_kinds", strings(call(handler, "getPhysicalRecordingKinds")));
            row.put("physical_recording_claim_namespaces", strings(call(handler, "getPhysicalRecordingClaimNamespaces")));
            row.put("history_producer_classes", byCommand.getOrDefault(route, List.of()));
            row.put("history_registration", byCommand.containsKey(route) ? "REGISTERED_SUBTYPES_REQUIRE_JOIN" : "NO_REGISTERED_PRODUCER");
            commands.add(row);
        }
        commands.sort(Comparator.comparing(x -> x.get("key").toString()));
        List<Map<String,Object>> reads = new ArrayList<>();
        for (Object handler : (Iterable<?>)call(registry, "getReads")) {
            Map<String,Object> row = new TreeMap<>();
            row.put("key", key(call(handler, "getKey"))); row.put("handler_class", handler.getClass().getName());
            row.put("rpc_key_selectors", rpcSelectors(call(handler, "getKey")));
            reads.add(row);
        }
        reads.sort(Comparator.comparing(x -> x.get("key").toString()));
        List<Map<String,Object>> imports = new ArrayList<>();
        for (Object interpreter : (Iterable<?>)call(registry, "getNativeOriginalImports")) {
            Object domain = call(interpreter, "getDomain");
            Map<String,Object> row = new TreeMap<>();
            row.put("domain", domain.toString());row.put("command", key(call(domain, "getCommand")));
            row.put("domain_profile", call(domain, "getDomainProfile"));row.put("interpreter_class", interpreter.getClass().getName());
            imports.add(row);
        }
        imports.sort(Comparator.comparing(x -> x.get("domain").toString()));
        List<Map<String,Object>> rejectionProfiles = new ArrayList<>();
        for (String name : Files.readAllLines(Path.of(args[1]))) {
            if (!name.matches("com\\.ukkera\\.pos\\.database\\.RuntimeHistorical[A-Za-z]+RejectionProfile"))
                throw new IllegalArgumentException("Unexpected profile class name");
            Class<?> profile = Class.forName(name);
            Object[] entries = profile.getEnumConstants();
            if (entries == null) throw new IllegalArgumentException("Inspected rejection profile is no longer an enum");
            for (Object entry : entries) {
                Map<String,Object> row = new TreeMap<>();
                row.put("class", name); row.put("entry", entry.toString());
                row.put("command", key(call(entry, "getCommand")));
                rejectionProfiles.add(row);
            }
        }
        rejectionProfiles.sort(Comparator.comparing(x -> x.get("class") + ":" + x.get("entry")));
        for (Map<String,Object> command : commands) {
            List<Map<String,Object>> candidates = new ArrayList<>();
            for (Map<String,Object> profile : rejectionProfiles) {
                if (command.get("key").equals(profile.get("command"))) candidates.add(profile);
            }
            command.put("rejection_profile_candidates", candidates);
        }
        Map<String,Object> result = new TreeMap<>();
        result.put("schema", "pos-installed-registry-metadata/v1");
        result.put("rpc_selector_limits", "Eleven closed key-selector objects only. Supplier/warehouse profile selectors remain source-bound separately. A selected key still needs typed payload classification and current Master authority; no RPC was invoked.");
        result.put("commands", commands);result.put("reads", reads);result.put("history_producers", history);
        result.put("native_original_imports", imports);
        result.put("limits", "Real registry metadata instantiated with explicit unavailable collaborators; no handler, authorization, native factory, producer join, transaction or RPC invocation. Registration is not subtype support, security proof or runtime qualification. Private reflection reads registry metadata only.");
        Files.writeString(Path.of(args[0]), json(result) + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println("Registry metadata captured: " + commands.size() + " commands, " + reads.size() + " reads, " + history.size() + " producer objects, " + imports.size() + " native import domains");
    }
}
