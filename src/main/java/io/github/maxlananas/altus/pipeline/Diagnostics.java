package io.github.maxlananas.altus.pipeline;

/** Where the pipeline reports what it did. Production forwards to Geyser's logger. */
public interface Diagnostics {
    void info(String message);

    void error(String message, Throwable cause);
}
