// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.

package fr.buildtheearth.skywindow.brigadier.exceptions;

import fr.buildtheearth.skywindow.brigadier.ImmutableStringReader;
import fr.buildtheearth.skywindow.brigadier.Message;

public class SimpleCommandExceptionType implements CommandExceptionType {
    private final Message message;

    public SimpleCommandExceptionType(final Message message) {
        this.message = message;
    }

    public CommandSyntaxException create() {
        return new CommandSyntaxException(this, message);
    }

    public CommandSyntaxException createWithContext(final ImmutableStringReader reader) {
        return new CommandSyntaxException(this, message, reader.getString(), reader.getCursor());
    }

    @Override
    public String toString() {
        return message.getString();
    }
}
