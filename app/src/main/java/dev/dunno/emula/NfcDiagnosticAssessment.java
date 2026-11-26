package dev.dunno.emula;

/** Evidence-based report triage only. Never selects a writer or changes activation policy. */
final class NfcDiagnosticAssessment {
    private NfcDiagnosticAssessment() {}

    static final class Finding {
        final String code;
        final String evidence;

        Finding(String code, String evidence) {
            this.code = code;
            this.evidence = evidence;
        }

        String format(String scope) {
            return scope + ": " + code + (evidence == null ? "" : " — " + evidence) + "\n";
        }
    }

    static String format(boolean nfcEnabled, boolean permission, boolean oemApi, boolean rawApi,
            boolean recoveryPending, OemOperationReport oem,
            RawOperationReport raw, RawOperationReport probe, String probeFailure) {
        StringBuilder text = new StringBuilder("EMULATION ASSESSMENT\n");
        text.append(prerequisites(nfcEnabled, permission, oemApi, rawApi, recoveryPending)
                .format("Current prerequisites"));
        text.append(assessOem(oem, oemApi).format("Last OEM operation"));
        text.append(assessRaw(raw).format("Last raw operation"));
        text.append(assessProbe(probe, probeFailure).format("Current read-only probe"));
        text.append("External reader: not verified");
        return text.toString();
    }

    static Finding prerequisites(boolean enabled, boolean permission, boolean oemApi,
            boolean rawApi, boolean recoveryPending) {
        if (!enabled) return new Finding("NFC_DISABLED", null);
        if (recoveryPending) return new Finding("RECOVERY_PENDING", "Saved recovery remains with no active card.");
        if (!permission) return new Finding("APP_PERMISSION_MISSING", "WRITE_SECURE_SETTINGS is not granted.");
        if (!oemApi && !rawApi) return new Finding("FIRMWARE_API_MISSING", null);
        return new Finding("API_CHECKS_PASSED", null);
    }

    static Finding assessOem(OemOperationReport report, boolean available) {
        if (report == null) return new Finding(available ? "OEM_NOT_TESTED" : "OEM_API_UNAVAILABLE", null);
        String scope = report.operation + " / " + report.path + " / " + report.stage;
        if (report.rollbackFailure != null || Boolean.FALSE.equals(report.rollbackSucceeded)) {
            return new Finding("OEM_RECOVERY_FAILED", report.rollbackFailure == null
                    ? scope : scope + "; " + report.rollbackFailure);
        }
        if (report.failure != null) {
            return new Finding(report.failure.contains("SecurityException")
                            ? "OEM_PERMISSION_DENIED" : "OEM_OPERATION_FAILED",
                    scope + "; " + report.failure);
        }
        if (report.operation == OemOperationReport.Operation.ACTIVATE && "COMPLETE".equals(report.stage)) {
            if (report.path == OemOperationReport.Path.ST_VENDOR_HCE
                    && "ST_CONFIG_READBACK_VERIFIED_READER_UNVERIFIED".equals(report.result)) {
                return new Finding("OEM_READBACK_MATCH_READER_REQUIRED", scope + "; ST profile and NFC parameters matched");
            }
            if ("MISMATCH".equals(report.androidReadbackStatus)) {
                return new Finding("OEM_READBACK_MISMATCH", scope + "; Android service status=" + report.androidStatus);
            }
            if ("MATCH".equals(report.androidReadbackStatus)) {
                return new Finding("OEM_READBACK_MATCH_READER_REQUIRED", scope + "; written parameters matched");
            }
            if (report.androidReadbackStatus != null) {
                return new Finding("OEM_READBACK_INCONCLUSIVE", scope + "; " + report.androidReadbackStatus);
            }
            return new Finding("OEM_ACCEPTED_READER_REQUIRED", scope + "; " + report.result);
        }
        return new Finding("OEM_ACTIVATION_NOT_ESTABLISHED", scope);
    }

    static Finding assessRaw(RawOperationReport report) {
        if (report == null) return new Finding("RAW_NOT_TESTED", null);
        String scope = report.operation + " / " + report.stage;
        if (report.rollbackFailure != null || report.resumeFailure != null
                || Boolean.FALSE.equals(report.resumeSucceeded)) {
            return new Finding("RAW_RECOVERY_FAILED", scope + "; rollback=" + report.rollbackFailure
                            + "; resume=" + report.resumeFailure);
        }
        if (Boolean.FALSE.equals(report.pauseSucceeded)) return new Finding("DISCOVERY_PAUSE_FAILED",
                scope + "; " + report.pollingMethod + "; " + report.pauseFailure);
        if ("PREPARATION_FAILED".equals(report.result)) return new Finding("RAW_PREPARATION_FAILED",
                scope + "; " + report.primaryFailure);
        if ("RESTART_REQUIRED".equals(report.result)) return new Finding("RAW_RESTART_REQUIRED", scope);
        if ("OLD_RECORD_ARCHIVED_AFTER_RESTART".equals(report.result)) {
            return new Finding("RAW_OLD_RECORD_ARCHIVED", scope + "; post-restart read matched all seven parameters.");
        }
        if (report.operation != RawOperationReport.Operation.ACTIVATE) {
            String detail = report.result == null ? scope : scope + "; " + report.result;
            return new Finding(report.primaryFailure == null ? "RAW_RESTORE_RECORDED" : "RAW_RESTORE_FAILED",
                    report.primaryFailure == null ? detail : detail + "; " + report.primaryFailure);
        }
        if (report.primaryFailure == null && "COMPLETE".equals(report.stage)) {
            boolean verified = "UID_ONLY_VERIFIED".equals(report.result)
                    || "UID_ONLY_VERIFIED_AFTER_REAPPLY".equals(report.result)
                    || "LEGACY_PROFILE_VERIFIED".equals(report.result)
                    || "LEGACY_UID_VERIFIED".equals(report.result);
            return new Finding(verified ? "RAW_READBACK_VERIFIED_READER_REQUIRED" : "RAW_READBACK_REQUIRED",
                    scope + "; " + report.result);
        }
        if ("UID_OVERWRITTEN_AFTER_RESUME".equals(report.result)
                || "UID_READBACK_MISMATCH".equals(report.result)) {
            return new Finding("UID_PERSISTENCE_FAILED", scope + "; " + report.result);
        }
        for (int i = report.requests.size() - 1; i >= 0; i--) {
            RawOperationReport.RequestRecord request = report.requests.get(i);
            // A successful fallback must not be diagnosed from an earlier rejected candidate.
            if (request.vendorStatus != null && request.vendorStatus != 0) {
                return new Finding("RAW_VENDOR_RF_REJECTED", request.stage + "; vendor status=" + request.vendorStatus);
            }
            if (request.failure == null && Integer.valueOf(0).equals(request.frameworkStatus)
                    && Integer.valueOf(0).equals(request.controllerStatus)) continue;
            Finding transport = transportFinding(request);
            if (transport != null) return transport;
            if ("SET_CONFIG".equals(request.command) && request.controllerStatus != null
                    && request.controllerStatus != 0) {
                return new Finding("RAW_CONFIG_REJECTED", request.stage + "; controller "
                                + RawNciCodec.nciStatusName(request.controllerStatus)
                                + "; reported parameters=" + request.reportedParameterIds);
            }
        }
        return new Finding("RAW_OPERATION_INCOMPLETE", scope + "; " + report.primaryFailure);
    }

    static Finding assessProbe(RawOperationReport report, String failure) {
        if (report == null) return new Finding(failure == null ? "PROBE_NOT_RUN" : "PROBE_UNAVAILABLE", failure);
        if ("OK".equals(report.result)) return new Finding("RAW_READABLE", null);
        for (int i = report.requests.size() - 1; i >= 0; i--) {
            RawOperationReport.RequestRecord request = report.requests.get(i);
            Finding transport = transportFinding(request);
            if (transport != null) return transport;
            if (request.controllerStatus != null && request.controllerStatus != 0) {
                return new Finding("RAW_GET_REJECTED", request.stage + "; "
                                + RawNciCodec.nciStatusName(request.controllerStatus));
            }
        }
        return new Finding("RAW_PROBE_INCOMPLETE", "GET_CONFIG result=" + report.result);
    }

    private static Finding transportFinding(RawOperationReport.RequestRecord request) {
        if (request.frameworkStatus == null) return null;
        String evidence = request.stage + "; framework " + RawNciCodec.frameworkStatusName(request.frameworkStatus)
                + "; controller " + (request.controllerStatus == null ? "no status"
                        : RawNciCodec.nciStatusName(request.controllerStatus));
        if (request.frameworkStatus == 3) return new Finding(request.rx == null
                        ? "RAW_FRAMEWORK_FAILED" : "RAW_FRAMEWORK_FAILED_WITH_RESPONSE", evidence);
        if (request.frameworkStatus != 0) return new Finding("RAW_FRAMEWORK_REJECTED", evidence);
        if (request.rx == null) return new Finding("RAW_RESPONSE_MISSING", evidence);
        return null;
    }
}
