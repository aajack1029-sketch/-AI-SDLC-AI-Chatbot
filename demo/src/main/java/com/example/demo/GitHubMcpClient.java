package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class GitHubMcpClient {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper;
    private final String command;
    private final String packageName;
    private final String token;
    private Process process;
    private BufferedReader processOutput;
    private BufferedWriter processInput;
    private long nextRequestId = 1;

    public GitHubMcpClient(
            ObjectMapper objectMapper,
            @Value("${app.github.mcp.command:npx}") String command,
            @Value("${app.github.mcp.package:@modelcontextprotocol/server-github}") String packageName,
            @Value("${app.github.token:}") String token) {
        this.objectMapper = objectMapper;
        this.command = command;
        this.packageName = packageName;
        this.token = token;
    }

    public synchronized JsonNode listTools() {
        initializeIfNeeded();
        JsonNode result = request("tools/list", objectMapper.createObjectNode());
        if (!result.path("tools").isArray()) {
            throw new GitHubMcpException("GitHub MCP server returned an invalid tools/list response");
        }
        return result.path("tools");
    }

    public synchronized JsonNode callTool(String toolName, JsonNode arguments) {
        if (!StringUtils.hasText(toolName) || arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("An MCP tool name and JSON object arguments are required");
        }
        initializeIfNeeded();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        JsonNode result = request("tools/call", params);
        if (result.path("isError").asBoolean()) {
            throw new GitHubMcpException("GitHub MCP tool call failed: " + toolName);
        }
        return result;
    }

    private void initializeIfNeeded() {
        if (process != null && process.isAlive()) {
            return;
        }
        closeProcess();
        if (!StringUtils.hasText(token)) {
            throw new GitHubMcpException("GITHUB_PERSONAL_ACCESS_TOKEN is required for GitHub MCP requests");
        }
        if (!StringUtils.hasText(command) || !StringUtils.hasText(packageName)) {
            throw new GitHubMcpException("GitHub MCP command and package must be configured");
        }

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command, "-y", packageName)
                    .redirectError(ProcessBuilder.Redirect.INHERIT);
            processBuilder.environment().put("GITHUB_PERSONAL_ACCESS_TOKEN", token);
            process = processBuilder.start();
            processInput = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            processOutput = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));

            ObjectNode params = objectMapper.createObjectNode();
            params.put("protocolVersion", "2024-11-05");
            params.set("capabilities", objectMapper.createObjectNode());
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "spring-boot-rag-github-client");
            clientInfo.put("version", "1.0.0");
            request("initialize", params);
            sendNotification("notifications/initialized", objectMapper.createObjectNode());
        } catch (IOException exception) {
            closeProcess();
            throw new GitHubMcpException("Unable to start or initialize the GitHub MCP server", exception);
        } catch (RuntimeException exception) {
            closeProcess();
            throw exception;
        }
    }

    private JsonNode request(String method, JsonNode params) {
        long requestId = nextRequestId++;
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId);
        request.put("method", method);
        request.set("params", params);

        try {
            processInput.write(objectMapper.writeValueAsString(request));
            processInput.newLine();
            processInput.flush();

            long deadline = System.nanoTime() + RESPONSE_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (processOutput.ready()) {
                    String line = processOutput.readLine();
                    if (line == null) {
                        throw new GitHubMcpException("GitHub MCP server closed its output stream");
                    }
                    JsonNode response = objectMapper.readTree(line);
                    if (response.path("id").asLong(-1) != requestId) {
                        continue;
                    }
                    if (response.has("error")) {
                        throw new GitHubMcpException("GitHub MCP request failed: "
                                + response.path("error").path("message").asText("unknown MCP error"));
                    }
                    JsonNode result = response.get("result");
                    if (result == null || !result.isObject()) {
                        throw new GitHubMcpException("GitHub MCP server returned an invalid response for " + method);
                    }
                    return result;
                }
                if (!process.isAlive()) {
                    throw new GitHubMcpException("GitHub MCP server exited with code " + process.exitValue());
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
            closeProcess();
            throw new GitHubMcpException("Timed out waiting for GitHub MCP response to " + method);
        } catch (IOException exception) {
            closeProcess();
            throw new GitHubMcpException("Unable to communicate with the GitHub MCP server", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closeProcess();
            throw new GitHubMcpException("Interrupted while waiting for GitHub MCP response", exception);
        }
    }

    private void sendNotification(String method, JsonNode params) throws IOException {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        processInput.write(objectMapper.writeValueAsString(notification));
        processInput.newLine();
        processInput.flush();
    }

    @PreDestroy
    public synchronized void close() {
        closeProcess();
    }

    private void closeProcess() {
        if (process != null) {
            Process activeProcess = process;
            process = null;
            processInput = null;
            processOutput = null;
            activeProcess.descendants().forEach(ProcessHandle::destroy);
            activeProcess.destroy();
        }
    }
}
