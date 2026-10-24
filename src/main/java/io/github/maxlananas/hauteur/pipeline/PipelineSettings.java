package io.github.maxlananas.hauteur.pipeline;

import io.github.maxlananas.hauteur.HauteurConfig;
import io.github.maxlananas.hauteur.translate.CommandYRewrite;

/** Configuration and extension state the pipeline reads on every packet. {@code HauteurCore} implements it. */
public interface PipelineSettings {
    /** Whether the extension is enabled and started. Switches are only evaluated while this is true. */
    boolean running();

    HauteurConfig config();

    CommandYRewrite.Config commandConfig();
}
