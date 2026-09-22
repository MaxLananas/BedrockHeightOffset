// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.

package fr.buildtheearth.skywindow.brigadier;

import fr.buildtheearth.skywindow.brigadier.context.CommandContext;
import fr.buildtheearth.skywindow.brigadier.exceptions.CommandSyntaxException;

import java.util.Collection;

@FunctionalInterface
public interface RedirectModifier<S> {
    Collection<S> apply(CommandContext<S> context) throws CommandSyntaxException;
}
