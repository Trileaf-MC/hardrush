package dev.hardrush.lobby.pack;

/** A lobby pack that must not be used. The message is Chinese and meant for the server owner. */
public final class PackException extends Exception {
    public PackException(String message) { super(message); }
    public PackException(String message, Throwable cause) { super(message, cause); }
}
