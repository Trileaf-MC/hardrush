package dev.hardrush.core.lobby.plan;

/** One acceptance finding. {@code function} and {@code where} may be null; {@code message} is Chinese, for server owners. */
public record Problem(Severity severity, ProblemKind kind, LobbyFunction function, Cell where, String message) {
    public static Problem error(ProblemKind kind, LobbyFunction function, Cell where, String message) {
        return new Problem(Severity.ERROR, kind, function, where, message);
    }
    public static Problem warning(ProblemKind kind, LobbyFunction function, Cell where, String message) {
        return new Problem(Severity.WARNING, kind, function, where, message);
    }
}
