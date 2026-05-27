package com.app.common.models;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PatchApplyLog {

    private Long id;
    private UUID patchId;
    private String fileName;
    private String appliedAt;

    public PatchApplyLog(UUID patchId, String fileName) {
        this.patchId  = patchId;
        this.fileName = fileName;
    }
}
