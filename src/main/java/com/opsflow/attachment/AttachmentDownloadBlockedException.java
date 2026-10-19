package com.opsflow.attachment;

import com.opsflow.common.BusinessException;

public class AttachmentDownloadBlockedException extends BusinessException {
    public AttachmentDownloadBlockedException(String message) {
        super(message);
    }
}
