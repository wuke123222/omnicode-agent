import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately tiny JSON-RPC fixture: no network, shell commands, model, or project reads. */
class FakeCodexDynamicToolServer {
    private static final Pattern METHOD = Pattern.compile("\\\"method\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern ID = Pattern.compile("\\\"id\\\":(\\\"[^\\\"]+\\\"|[0-9]+)");

    public static void main(String[] args) throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
            boolean toolResponseSeen = false;
            String line;
            while ((line = input.readLine()) != null) {
                Matcher idMatch = ID.matcher(line);
                String id = idMatch.find() ? idMatch.group(1) : null;
                Matcher methodMatch = METHOD.matcher(line);
                String method = methodMatch.find() ? methodMatch.group(1) : "";
                if (id != null && id.equals("\"server-tool-1\"")) {
                    if (!line.contains("\"success\":false")) System.exit(20);
                    toolResponseSeen = true;
                    continue;
                }
                if ("initialize".equals(method)) {
                    output.println("{\"id\":" + id + ",\"result\":{}}");
                } else if ("thread/start".equals(method)) {
                    if (!line.contains("\"dynamicTools\"") || !line.contains("mcp__probe__ping")) System.exit(21);
                    output.println("{\"id\":" + id + ",\"result\":{\"thread\":{\"id\":\"thread-1\"}}}");
                } else if ("turn/start".equals(method)) {
                    output.println("{\"id\":" + id + ",\"result\":{\"turn\":{\"id\":\"turn-1\"}}}");
                    output.println("{\"method\":\"turn/started\",\"params\":{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\"}}}");
                    output.println("{\"id\":\"server-tool-1\",\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"callId\":\"call-1\",\"tool\":\"mcp__probe__ping\",\"arguments\":{\"nonce\":\"safe\"}}}");
                } else if ("turn/interrupt".equals(method)) {
                    if (!toolResponseSeen) System.exit(22);
                    output.println("{\"id\":" + id + ",\"result\":{}}");
                    output.println("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\",\"status\":\"interrupted\"}}}");
                }
            }
        }
    }
}
