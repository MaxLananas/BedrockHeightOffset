// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.

package fr.buildtheearth.skywindow.brigadier;

import fr.buildtheearth.skywindow.brigadier.context.CommandContext;
import fr.buildtheearth.skywindow.brigadier.exceptions.CommandSyntaxException;

@FunctionalInterface
public interface Command<S> {
    int SINGLE_SUCCESS = 1;

    int run(CommandContext<S> context) throws CommandSyntaxException;
}
