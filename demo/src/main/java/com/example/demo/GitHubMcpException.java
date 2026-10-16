package com.example.demo;

public class GitHubMcpException extends RuntimeException {

    public GitHubMcpException(String message) {
        super(message);
    }

    public GitHubMcpException(String message, Throwable cause) {
        super(message, cause);
    }
}
