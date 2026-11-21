package com.example.demo.tools;

import com.example.demo.GitHubMcpException;

public class GitHubIssueCreationException extends GitHubMcpException {

    public GitHubIssueCreationException(String message) {
        super(message);
    }

    public GitHubIssueCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
