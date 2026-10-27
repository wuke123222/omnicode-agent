package dev.omnicode.tool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.omnicode.agent.AgentMode
import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import java.time.Duration

/** Host-owned read operations, never an arbitrary SSH command or configurable shell snippet. */
internal class InspectRemoteKubernetesTool(private val commandTool: AgentTool) : AgentTool {
    override val name = "inspect_remote_kubernetes"
    override val description = "Connect to an explicitly requested SSH host and inspect Kubernetes pods or logs. " +
        "Use action connection to test actual login, pods to find pod names, then logs for one pod. " +
        "Available for Agent diagnostics even during recovery. Uses existing SSH keys/agent and verified host keys; " +
        "requires command approval and network-enabled sandbox settings. Never accepts passwords or arbitrary commands."
    override val dangerous = true // Network and credential use still require approval.
    override val effect = ToolEffect.EXTERNAL
    override val executionTimeout: Duration = Duration.ofSeconds(60)
    override val inputSchema = objectSchema(required = listOf("host", "user", "action")) {
        stringProperty("host", "Explicit DNS hostname or IP, not an SSH config alias or URI.")
        stringProperty("user", "SSH login user already supplied by the user.")
        integerProperty("port", "SSH port.", 22, 1, 65535)
        stringProperty("action", "connection, pods, or logs. No arbitrary commands.")
        stringProperty("namespace", "Required for pods/logs.")
        stringProperty("selector", "Optional equality label selector for pods, e.g. app=advertising-api-service.")
        stringProperty("pod", "Required for logs: exact pod name returned by pods, not a deployment.")
        stringProperty("container", "Optional container name for logs; otherwise reads all containers.")
        integerProperty("since_minutes", "Log lookback in minutes.", 30, 1, 1440)
        integerProperty("tail", "Maximum log lines per container.", 500, 1, 2000)
    }

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
        require(context.mode == AgentMode.AGENT) { "Remote inspection is available only in Agent mode." }
        val argv = command(arguments)
        // The normal command path owns approval, executable identity revalidation, sandbox,
        // cancellation, output bounds and audit. Never replace its approval gate with 'true'.
        val result = commandTool.execute(JsonObject().apply {
            add("argv", JsonArray().apply { argv.forEach { add(it) } })
            addProperty("cwd", ".")
            addProperty("timeout_seconds", 45)
        }, context.copy(remoteReadInspection = true))
        // These fixed queries cannot replay a prior mutation. A lost read result does not
        // establish or clear the outcome of any earlier operation.
        val redacted = COOKIE_HEADER.replace(REDACTOR.redact(result.content)) { "${it.groupValues[1]}[REDACTED]" }
        return result.copy(content = "Remote inspection (${arguments.string("action")}):\n$redacted",
            sideEffectStateUnknown = false)
    }

    internal fun command(arguments: JsonObject): List<String> {
        require(arguments.keySet().all { it in FIELDS }) { "Unknown remote inspection field" }
        val host = arguments.string("host")
        require(host.length in 1..253 && (HOST.matches(host) || IPV6.matches(host))) { "Invalid SSH host" }
        val user = arguments.string("user")
        require(USER.matches(user)) { "Invalid SSH user" }
        val port = boundedInt(arguments, "port", 22, 1..65535)
        val action = arguments.string("action")
        require(action in setOf("connection", "pods", "logs")) { "Unsupported remote inspection action" }
        val namespace = arguments.string("namespace")
        val selector = arguments.string("selector")
        val pod = arguments.string("pod")
        val container = arguments.string("container")
        val since = boundedInt(arguments, "since_minutes", 30, 1..1440)
        val tail = boundedInt(arguments, "tail", 500, 1..2000)
        if (action == "connection") {
            require(namespace.isEmpty() && selector.isEmpty() && pod.isEmpty() && container.isEmpty()) {
                "connection accepts only host, user and port"
            }
        } else {
            require(namespace.length <= 63 && DNS_LABEL.matches(namespace)) { "Invalid Kubernetes namespace" }
        }
        val remote = when (action) {
            "connection" -> listOf("printf", "%s\\n", "OMNICODE_SSH_CONNECTED")
            "pods" -> {
                require(pod.isEmpty() && container.isEmpty()) { "pods does not accept a pod or container" }
                require(selector.isEmpty() || selector.length <= 512 && SELECTOR.matches(selector)) { "Invalid label selector" }
                listOf("kubectl", "--request-timeout=20s", "-n", namespace, "get", "pods", "-o", "wide") +
                    if (selector.isEmpty()) emptyList() else listOf("-l", selector)
            }
            else -> {
                require(pod.length <= 253 && DNS_SUBDOMAIN.matches(pod)) { "logs requires an exact pod name" }
                require(selector.isEmpty()) { "Use pods to resolve a selector before reading logs" }
                require(container.isEmpty() || container.length <= 63 && DNS_LABEL.matches(container)) { "Invalid container" }
                listOf("kubectl", "--request-timeout=20s", "-n", namespace, "logs", pod,
                    "--since=${since}m", "--tail=$tail", "--limit-bytes=24000", "--timestamps=true", "--prefix=true") +
                    if (container.isEmpty()) listOf("--all-containers=true") else listOf("-c", container)
            }
        }
        // SSH sends a single remote shell string, not argv. Quote every validated argument.
        // -F none also disables Match exec/Include/ProxyCommand hooks from user/system config.
        return listOf("ssh", "-F", "none", "-T", "-n",
            "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-o", "ConnectionAttempts=1",
            "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no",
            "-o", "ClearAllForwardings=yes", "-o", "ForwardAgent=no", "-o", "ForwardX11=no",
            "-o", "PermitLocalCommand=no", "-o", "ControlMaster=no", "-o", "ControlPath=none",
            "-o", "ProxyCommand=none", "-o", "ProxyJump=none", "-o", "AddKeysToAgent=no",
            "-p", port.toString(), "-l", user, host, remote.joinToString(" ", transform = ::quote))
    }

    private fun boundedInt(args: JsonObject, name: String, default: Int, range: IntRange): Int {
        val raw = args.get(name) ?: return default
        require(raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber && raw.toString().matches(Regex("[0-9]{1,5}"))) {
            "Invalid $name"
        }
        return raw.asInt.also { require(it in range) { "$name is out of range" } }
    }

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"

    private companion object {
        val REDACTOR = DefaultSensitiveDataRedactor()
        val COOKIE_HEADER = Regex("(?im)(\\b(?:set-cookie|cookie)\\s*:\\s*)[^\\r\\n]+")
        val FIELDS = setOf("host", "user", "port", "action", "namespace", "selector", "pod", "container", "since_minutes", "tail")
        val HOST = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
        val IPV6 = Regex("[0-9A-Fa-f]*:[0-9A-Fa-f:]+")
        val USER = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,63}")
        val DNS_LABEL = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
        val DNS_SUBDOMAIN = Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
        val SELECTOR = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?=[A-Za-z0-9_.-]+(?:,[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?=[A-Za-z0-9_.-]+)*")
    }
}
