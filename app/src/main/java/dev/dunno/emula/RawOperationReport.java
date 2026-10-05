package dev.dunno.emula;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** In-memory context for the most recent raw NFC write operation. */
final class RawOperationReport {
    enum Operation {
        ACTIVATE,
        DEACTIVATE,
        ROLLBACK_AFTER_ACTIVATION_FAILURE,
        STARTUP_PENDING_RESTORE,
        POST_RESUME_REAPPLY,
        DIAGNOSTIC_GET_CONFIG
    }

    enum Candidate {
        UID_ONLY,
        LEGACY_PROFILE
    }

    static final class RequestRecord {
        final Operation operation;
        final Candidate candidate;
        final String stage;
        final String command;
        final byte[] tx;
        Integer frameworkStatus;
        byte[] rx;
        Integer controllerStatus;
        List<Integer> reportedParameterIds = new ArrayList<>();
        Long durationMillis;
        String failure;
        String unexpectedResponse;

        RequestRecord(Operation operation, Candidate candidate, String stage,
                String command, byte[] tx) {
            this.operation = operation;
            this.candidate = candidate;
            this.stage = stage;
            this.command = command;
            this.tx = Arrays.copyOf(tx, tx.length);
        }
    }

    final Operation operation;
    final List<RequestRecord> requests = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    Candidate candidate;
    String stage = "STARTING";
    String pollingMethod = "UNAVAILABLE";
    Boolean pauseSucceeded;
    Boolean resumeSucceeded;
    String pauseFailure;
    String resumeFailure;
    byte[] uidBefore;
    byte[] uidAfterSet;
    byte[] uidAfterResume;
    byte[] uidAfterReapply;
    String result;
    String primaryFailure;
    String rollbackFailure;
    RawOperationReport rollback;

    RawOperationReport(Operation operation) {
        this.operation = operation;
    }

    RequestRecord addRequest(Operation requestOperation, Candidate requestCandidate,
            String requestStage, String command, byte[] tx) {
        RequestRecord record = new RequestRecord(
                requestOperation, requestCandidate, requestStage, command, tx);
        requests.add(record);
        return record;
    }

    String formatForDisplay() {
        StringBuilder text = new StringBuilder("LAST NFC OPERATION\n\n");
        appendSection(text, "", false);
        return text.toString();
    }

    String formatDetailed() {
        StringBuilder text = new StringBuilder("LAST NFC OPERATION\n\n");
        appendSection(text, "", true);
        return text.toString();
    }

    String shortFailureSummary() {
        if (Boolean.FALSE.equals(pauseSucceeded)) return "polling pause failed";
        if ("UID_OVERWRITTEN_AFTER_RESUME".equals(result)) {
            return "UID was reset after NFC discovery resumed";
        }
        for (int i = requests.size() - 1; i >= 0; i--) {
            RequestRecord request = requests.get(i);
            if (!"SET_CONFIG".equals(request.command) || request.failure == null) continue;
            String subject = request.candidate == Candidate.UID_ONLY
                    ? "UID-only SET" : "legacy profile SET";
            if (request.frameworkStatus != null && request.frameworkStatus != 0) {
                return subject + " rejected by framework — "
                        + RawNciCodec.frameworkStatusName(request.frameworkStatus)
                        + " (" + request.frameworkStatus + ")";
            }
            if (request.controllerStatus != null && request.controllerStatus != 0) {
                return String.format(Locale.US, "%s rejected — %s (0x%02X)",
                        subject,
                        RawNciCodec.nciStatusName(request.controllerStatus),
                        request.controllerStatus);
            }
            return subject + " failed — " + request.failure;
        }
        return primaryFailure == null ? "raw NFC operation failed" : primaryFailure;
    }

    private void appendSection(StringBuilder text, String indent, boolean includeGets) {
        text.append(indent).append("Operation: ").append(operation).append('\n');
        String candidates = candidateHistory();
        if (!candidates.isEmpty()) {
            text.append(indent).append("Candidate: ").append(candidates).append('\n');
        }
        text.append(indent).append("Stage: ").append(valueOrDash(stage)).append('\n');
        if (result != null) {
            text.append(indent).append("Result: ").append(result).append('\n');
        }
        text.append('\n').append(indent).append("Polling method: ")
                .append(valueOrDash(pollingMethod)).append('\n');
        text.append(indent).append("Pause: ").append(outcome(pauseSucceeded)).append('\n');
        text.append(indent).append("Resume: ").append(outcome(resumeSucceeded)).append('\n');

        appendUid(text, indent, "UID before", uidBefore);
        appendUid(text, indent, "UID after SET", uidAfterSet);
        appendUid(text, indent, "UID after resume", uidAfterResume);
        appendUid(text, indent, "UID after reapply", uidAfterReapply);

        boolean headingWritten = false;
        for (RequestRecord request : requests) {
            if (!includeGets && !"SET_CONFIG".equals(request.command)) continue;
            if (!headingWritten) {
                text.append('\n').append(indent).append(includeGets
                        ? "RAW REQUESTS" : "SET_CONFIG REQUESTS").append('\n');
                headingWritten = true;
            }
            appendRequest(text, indent, request);
        }

        if (primaryFailure != null) {
            text.append('\n').append(indent).append("Primary failure: ")
                    .append(primaryFailure).append('\n');
        }
        if (includeGets) {
            if (pauseFailure != null) {
                text.append(indent).append("Pause failure: ").append(pauseFailure).append('\n');
            }
            if (resumeFailure != null) {
                text.append(indent).append("Resume failure: ").append(resumeFailure).append('\n');
            }
            for (String note : notes) {
                text.append(indent).append("Note: ").append(note).append('\n');
            }
        }

        if (rollback != null || rollbackFailure != null) {
            text.append('\n').append(indent).append("ROLLBACK\n");
            if (rollback != null) {
                rollback.appendSection(text, indent + "  ", includeGets);
            } else {
                text.append(indent).append("  Failure: ")
                        .append(valueOrDash(rollbackFailure)).append('\n');
            }
        }
    }

    private static void appendRequest(
            StringBuilder text, String indent, RequestRecord request) {
        text.append('\n').append(indent).append(request.command).append('\n');
        text.append(indent).append("Purpose: ").append(request.operation).append('\n');
        if (request.candidate != null) {
            text.append(indent).append("Candidate: ").append(request.candidate).append('\n');
        }
        text.append(indent).append("Stage: ").append(request.stage).append('\n');
        text.append(indent).append("TX: ").append(RawNciCodec.hex(request.tx)).append('\n');
        text.append(indent).append("Framework: ");
        if (request.frameworkStatus == null) {
            text.append("not sent");
        } else {
            text.append(RawNciCodec.frameworkStatusName(request.frameworkStatus))
                    .append(" (").append(request.frameworkStatus).append(')');
        }
        text.append('\n');
        text.append(indent).append("RX: ").append(RawNciCodec.hex(request.rx)).append('\n');
        text.append(indent).append("Controller: ");
        if (request.controllerStatus == null) {
            text.append("no response");
        } else {
            text.append(RawNciCodec.nciStatusName(request.controllerStatus))
                    .append(String.format(Locale.US, " (0x%02X)", request.controllerStatus));
        }
        text.append('\n');
        if (!request.reportedParameterIds.isEmpty()) {
            text.append(indent).append("Reported params: ");
            for (int i = 0; i < request.reportedParameterIds.size(); i++) {
                if (i > 0) text.append(", ");
                int id = request.reportedParameterIds.get(i);
                text.append(String.format(Locale.US, "0x%02X", id));
            }
            text.append('\n');
        }
        if (request.durationMillis != null) {
            text.append(indent).append("Duration: ")
                    .append(request.durationMillis).append(" ms\n");
        }
        if (request.unexpectedResponse != null) {
            text.append(indent).append("Unexpected response: ")
                    .append(request.unexpectedResponse).append('\n');
        }
        if (request.failure != null) {
            text.append(indent).append("Failure: ").append(request.failure).append('\n');
        }
    }

    private String candidateHistory() {
        Set<Candidate> candidates = new LinkedHashSet<>();
        for (RequestRecord request : requests) {
            if ("SET_CONFIG".equals(request.command) && request.candidate != null) {
                candidates.add(request.candidate);
            }
        }
        if (candidates.isEmpty() && candidate != null) candidates.add(candidate);
        StringBuilder result = new StringBuilder();
        for (Candidate item : candidates) {
            if (result.length() > 0) result.append(" -> ");
            result.append(item);
        }
        return result.toString();
    }

    private static void appendUid(
            StringBuilder text, String indent, String label, byte[] uid) {
        if (uid != null) {
            text.append(indent).append(label).append(": ")
                    .append(RawNciCodec.hex(uid)).append('\n');
        }
    }

    private static String outcome(Boolean success) {
        if (success == null) return "not used";
        return success ? "OK" : "FAILED";
    }

    private static String valueOrDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }
}
