package dev.checkwakeup.core;

/** An ACK may only release the exact revisions sent, never newer queued data. */
public final class ReportAck {
    public static final class Result {
        public final long index, revision;
        public final String kind;
        public Result(long index, long revision, String kind) {
            this.index = index;
            this.revision = revision;
            this.kind = kind;
        }
    }
    private ReportAck() {}

    public static boolean matches(String reportId, long[] sent, long schema, String ackId,
                                  Result[] results) {
        if (schema != 1 || !reportId.equals(ackId) || sent.length != results.length
                || sent.length == 0) return false;
        for (int i = 0; i < sent.length; i++) {
            Result result = results[i];
            if (result == null || result.index != i || sent[i] <= 0) return false;
            boolean accepted = ("created".equals(result.kind) || "updated".equals(result.kind)
                    || "unchanged".equals(result.kind)) && result.revision == sent[i];
            accepted |= "ignored_stale".equals(result.kind) && result.revision > sent[i];
            if (!accepted) return false;
        }
        return true;
    }
}
