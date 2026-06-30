package nz.co.eroad.qaisystem.trace;

/** A pluggable destination for copilot-CLI context traces (file or database). All methods are best-effort. */
public interface TraceSink {
    TraceHandle begin(String boundary, String taskType, String prId, String agent, String prompt);
    void rawLine(TraceHandle handle, String line);
    void finish(TraceHandle handle, String finalOutput, int exitCode, boolean success);
}
