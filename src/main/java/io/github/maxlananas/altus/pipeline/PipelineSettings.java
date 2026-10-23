package io.github.maxlananas.altus.pipeline;

import io.github.maxlananas.altus.AltusConfig;
import io.github.maxlananas.altus.translate.CommandYRewrite;

/** Configuration and extension state the pipeline reads on every packet. {@code AltusCore} implements it. */
public interface PipelineSettings {
    /** Whether the extension is enabled and started. Switches are only evaluated while this is true. */
    boolean running();

    AltusConfig config();

    CommandYRewrite.Config commandConfig();

    void reportQuarantinedType(String typeName, String reason);
}
