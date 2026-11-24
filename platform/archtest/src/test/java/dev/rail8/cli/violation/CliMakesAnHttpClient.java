package dev.rail8.cli.violation;

/** Deliberate violation: a command that calls a node with its own HTTP client, past the admin API's check of plain HTTP. */
public final class CliMakesAnHttpClient {
    private CliMakesAnHttpClient() {}
    public static java.net.http.HttpClient client() { return java.net.http.HttpClient.newHttpClient(); }
}
