// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.

package fr.buildtheearth.skywindow.brigadier.arguments;

import fr.buildtheearth.skywindow.brigadier.StringReader;
import fr.buildtheearth.skywindow.brigadier.context.CommandContext;
import fr.buildtheearth.skywindow.brigadier.exceptions.CommandSyntaxException;
import fr.buildtheearth.skywindow.brigadier.suggestion.Suggestions;
import fr.buildtheearth.skywindow.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

public interface ArgumentType<T> {
    T parse(StringReader reader) throws CommandSyntaxException;

    default <S> T parse(final StringReader reader, final S source) throws CommandSyntaxException {
        return parse(reader);
    }

    default <S> CompletableFuture<Suggestions> listSuggestions(final CommandContext<S> context, final SuggestionsBuilder builder) {
        return Suggestions.empty();
    }

    default Collection<String> getExamples() {
        return Collections.emptyList();
    }
}
