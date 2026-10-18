package com.zuluindustries.agenten.agent;

import java.util.List;

/** Tool-, Schreib-, Lösch- und externe Zugriffe. */
public record PermissionConfig(
        Boolean toolUse,
        Boolean externalAccess,
        Boolean writeOperations,
        Boolean deleteOperations,
        List<String> requireConfirmationFor) {

    public PermissionConfig {
        toolUse = toolUse == null || toolUse;
        externalAccess = externalAccess != null && externalAccess;
        writeOperations = writeOperations != null && writeOperations;
        deleteOperations = deleteOperations != null && deleteOperations;
        requireConfirmationFor = requireConfirmationFor == null
                ? List.of("delete", "external_write")
                : List.copyOf(requireConfirmationFor);
    }

    public static PermissionConfig defaults() {
        return new PermissionConfig(null, null, null, null, null);
    }
}
